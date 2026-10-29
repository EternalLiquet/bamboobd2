package io.github.eternalliquet.bamboobd2.core.reader

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.Capability
import io.github.eternalliquet.bamboobd2.core.model.PageStatus
import io.github.eternalliquet.bamboobd2.core.model.capability
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayChunk
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayStep
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayTransport
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.DecodeResult
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import io.github.eternalliquet.bamboobd2.core.storage.SessionCodec
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** ElmObdReader over ReplayTransport, in virtual time. Every script here is synthetic. */
class ElmObdReaderTest {

    @Test
    fun followsOnlyEachRespondersContinuationThrough40() = runTest {
        val steps = init + listOf(
            step("0100", 10L to "7E8 06 41 00 00 00 00 01\r7E9 06 41 00 00 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
            step("0120", 10L to "7E8 06 41 20 80 00 00 01\r7E9 06 41 20 FF FF FF FF\r\r>"),
            step("0140", 10L to "7E8 06 41 40 08 00 00 00\r\r>"),
        ) + noCodes
        val run = session(script(steps))
        val support = run.record.supportedPids as SupportedPids.Known
        assertEquals(listOf("0100", "0120", "0140"), run.transport.writes.filter { it.startsWith("01") })
        assertEquals(Capability.SUPPORTED_AND_DECODABLE, support.capability("45", "7E8").capability)
        assertEquals(Capability.UNSUPPORTED, support.capability("45", "7E9").capability)
        assertEquals(Capability.SUPPORTED_NOT_DECODED, support.capability("21", "7E8").capability)
        assertEquals(PageStatus.MALFORMED, support.pages.single { it.ecu == "7E9" && it.pageBase == "20" }.status)
        assertFalse(support.complete)
    }

    @Test
    fun missingContinuationDoesNotInventLaterSupport() = runTest {
        val steps = init + listOf(
            step("0100", 10L to "7E8 06 41 00 00 00 00 01\r7E9 06 41 00 00 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
            step("0120", 10L to "NO DATA\r\r>"),
        ) + noCodes
        val support = session(script(steps)).record.supportedPids as SupportedPids.Known
        assertEquals(PageStatus.NO_DATA, support.pages.single { it.pageBase == "20" }.status)
        assertEquals(Capability.UNKNOWN, support.capability("45", "7E8").capability)
        assertEquals(Capability.UNSUPPORTED, support.capability("45", "7E9").capability)
    }

    @Test
    fun eightPageWalkStopsWithoutWrappingEvenWhenLastContinuationIsSet() = runTest {
        val pages = listOf("00", "20", "40", "60", "80", "A0", "C0", "E0")
        val steps = init + listOf(step("0100", 10L to "7E8 06 41 00 00 00 00 01\r\r>"),
            step("ATDPN", 10L to "A6\r\r>")) +
            pages.drop(1).map { page -> step("01$page", 10L to "7E8 06 41 $page 00 00 00 01\r\r>") } + noCodes
        val run = session(script(steps))
        val support = run.record.supportedPids as SupportedPids.Known
        assertEquals(pages.map { "01$it" }, run.transport.writes.filter { it.startsWith("01") })
        assertEquals(8, support.pages.size)
        assertTrue(support.pages.all { it.continues && it.supportedIds.isEmpty() })
        assertFalse("100" in support.pids)
        assertFalse(support.pages.any { "100" in it.supportedIds })
        assertFalse(support.complete)
        assertEquals(Capability.UNSUPPORTED, support.capability("FF", "7E8").capability)
    }

    @Test
    fun discoveryWideDeadlineIncludesSetupAndPreservesEarlierPages() = runTest {
        val slow = AdapterProfile(50_000, 30_000, 30_000, 5_000, 5_000, 50)
        val steps = listOf("ATE0", "ATL0", "ATH1", "ATSP0").map {
            step(it, 30_000L to "OK\r\r>")
        } + listOf(
            step("0100", 20_000L to "7E8 06 41 00 00 00 00 01\r\r>"),
            step("ATDPN", 20_000L to "A6\r\r>"),
            step("0120", 25_000L to "7E8 06 41 20 00 00 00 00\r\r>"),
        )
        val run = session(script(steps), slow)
        assertEquals(SessionOutcome.FAILED, run.record.outcome)
        val support = run.record.supportedPids as SupportedPids.Known
        assertEquals(PageStatus.MISSING, support.pages.single { it.pageBase == "20" }.status)
        assertEquals(Capability.UNKNOWN, support.capability("21", "7E8").capability)
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "0120"), run.transport.writes)
        assertTrue(run.record.endedAt!! <= 180_100)
    }

    @Test
    fun malformedAndConflictingRespondersDoNotEraseIndependentValidPage() = runTest {
        val badFrame = script(init + listOf(
            step("0100", 10L to "7E8 07 41 00 08 00 00\r7E9 06 41 00 08 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
        ) + noCodes + listOf(step("0105", 10L to "7E9 03 41 05 7B\r\r>")))
        val first = session(badFrame).record
        val support = first.supportedPids as SupportedPids.Known
        assertEquals(PageStatus.MALFORMED, support.pages.single { it.ecu == "7E8" }.status)
        assertEquals(PageStatus.VALID, support.pages.single { it.ecu == "7E9" }.status)
        assertEquals(Capability.UNKNOWN, support.capability("05", "7E8").capability)
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E9")), first.reading("05"))

        val duplicate = session(script(init + listOf(
            step("0100", 10L to "7E8 06 41 00 08 00 00 00\r7E8 06 41 00 00 00 00 00\r7E9 06 41 00 00 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
        ) + noCodes)).record.supportedPids as SupportedPids.Known
        assertEquals(PageStatus.CONFLICT, duplicate.pages.single { it.ecu == "7E8" }.status)
        assertEquals(PageStatus.VALID, duplicate.pages.single { it.ecu == "7E9" }.status)
        assertEquals(Capability.UNKNOWN, duplicate.capability("05", "7E8").capability)

        val negative = session(script(init + listOf(
            step("0100", 10L to "7E8 03 7F 01 11\r7E9 06 41 00 00 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
        ) + noCodes)).record.supportedPids as SupportedPids.Known
        assertEquals(PageStatus.NEGATIVE, negative.pages.single { it.ecu == "7E8" }.status)
        assertEquals(PageStatus.VALID, negative.pages.single { it.ecu == "7E9" }.status)
    }

    private fun step(request: String, vararg chunks: Pair<Long, String>, disconnectAfter: Long? = null) =
        ReplayStep(request, chunks.map { ReplayChunk(it.first, it.second) }, disconnectAfter)

    private fun script(steps: List<ReplayStep>, connected: Boolean = true) =
        ReplayScript("test", "test", "SYNTHETIC", "constructed", "built in ElmObdReaderTest", connected = connected, steps = steps)

    private val init = listOf(
        step("ATE0", 10L to "ATE0\rOK\r\r>"),
        step("ATL0", 10L to "OK\r\r>"),
        step("ATH1", 10L to "OK\r\r>"),
        step("ATSP0", 10L to "OK\r\r>"),
    )
    private val protocol = listOf(
        step("0100", 20L to "7E8 06 41 00 BE 1F B8 10\r\r>"),
        step("ATDPN", 10L to "A6\r\r>"),
    )
    private val noCodes = listOf(step("03", 10L to "7E8 02 43 00\r\r>"), step("07", 10L to "7E8 02 47 00\r\r>"))
    private val load = step("0104", 10L to "7E8 03 41 04 80\r\r>")
    private val upToCoolant = init + protocol + noCodes + load

    private class Run(val record: SessionRecord, val transport: ReplayTransport)

    private fun TestScope.session(script: ReplayScript, profile: AdapterProfile = AdapterProfile.PROVISIONAL): Run {
        val transport = ReplayTransport(script, testScheduler.timeSource)
        val controller = SessionController(this, clock = { testScheduler.currentTime }, ids = { "replay-1" })
        controller.start(ElmObdReader(transport, "replay:${script.id}", profile, discoveryClockMillis = { testScheduler.currentTime }))
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue("session did not finish: $state", state is SessionState.Finished)
        return Run((state as SessionState.Finished).record, transport)
    }

    private fun SessionRecord.reading(pid: String) = readings.filter { it.pid == pid }.map { it.result }
    private fun SessionRecord.eventKinds() = events.map { it.kind }

    @Test
    fun bundledScriptsAreSyntheticAllowlistedAndFreeOfVinShapes() {
        val vinShaped = Regex("(?<![A-Za-z0-9])[A-HJ-NPR-Za-hj-npr-z0-9]{17}(?![A-Za-z0-9])")
        for (id in ReplayTransport.bundledIds) {
            val script = ReplayTransport.bundled(id)
            assertEquals(id, script.id)
            assertEquals("SYNTHETIC", script.provenance)
            assertTrue(id, script.problems().isEmpty())
            assertFalse(id, script.steps.any { it.request.startsWith("09") })
            assertFalse(id, script.steps.flatMap { it.chunks }.any { vinShaped.containsMatchIn(it.text) })
        }
    }

    @Test
    fun scriptsWithUnapprovedRequestsOrIncompleteCaptureMetadataAreRejected() {
        assertTrue(script(listOf(step("ATZ"))).problems().any { "ATZ" in it })
        assertTrue(script(listOf(step("0902"))).problems().isNotEmpty())
        assertTrue(script(emptyList()).copy(provenance = "SANITIZED_CAPTURE").problems().any { "sha256" in it })
        assertTrue(script(emptyList()).copy(provenance = "LIVE").problems().isNotEmpty())
    }

    @Test
    fun canReplayCompletesWithResponderIdsAndPendingLineDropped() = runTest {
        val r = session(ReplayTransport.bundled("can-complete")).record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertEquals(Provenance.REPLAY, r.provenance)
        assertEquals(listOf("01", "03", "04", "05", "06", "07", "0C", "0D", "0E", "0F", "10", "11", "13", "14", "15", "1C"), (r.supportedPids as SupportedPids.Known).pids)
        assertEquals(listOf(Dtc("P0133", DtcKind.STORED, "7E8")), r.dtcs)
        assertTrue(r.allCodesRead)
        // The adapter itself waited out "7F 01 78" (p45); only the final reply counts.
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E8")), r.reading("05"))
        assertEquals(listOf(ReadingResult.Value(1726.0, "rpm", "7E8")), r.reading("0C"))
        assertEquals(listOf(ReadingResult.Value(0.0, "km/h", "7E8")), r.reading("0D"))
    }

    @Test
    fun nonCanReplayCompletesInTheNonCanForm() = runTest {
        val r = session(ReplayTransport.bundled("non-can-complete")).record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertTrue(r.dtcs.isEmpty())
        assertTrue(r.allCodesRead)
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "10")), r.reading("05"))
        assertEquals(listOf(ReadingResult.Value(20.0, "%", "10")), r.reading("04"))
    }

    @Test
    fun noBytesStopsAfterTheDeadlineAndResyncWithoutResending() = runTest {
        val p = AdapterProfile.PROVISIONAL
        val run = session(ReplayTransport.bundled("no-response"))
        val r = run.record
        assertEquals(SessionOutcome.FAILED, r.outcome)
        assertTrue(r.events.last().detail, r.events.last().detail.startsWith("no_response: no prompt after '0105'"))
        assertEquals(listOf(ReadingResult.Value(128 * 100.0 / 255, "%", "7E8")), r.reading("04"))
        assertTrue(r.allCodesRead)
        assertEquals(1, run.transport.writes.count { it == "0105" })
        assertFalse("010C" in run.transport.writes)
        // Bounded: the stop comes within the request deadline plus the resync wait.
        val writtenAt = r.endedAt!! - p.vehicleRequestMillis - p.resyncMillis
        assertTrue("stopped at ${r.endedAt}", writtenAt in 0..2_000)
    }

    @Test
    fun partialReplyWithoutPromptStopsWithoutResending() = runTest {
        val r = session(script(upToCoolant + step("0105", 10L to "7E8 03 41"))).let {
            assertEquals(1, it.transport.writes.count { w -> w == "0105" })
            it.record
        }
        assertEquals(SessionOutcome.FAILED, r.outcome)
        assertTrue(r.events.last().detail.startsWith("no_response"))
    }

    @Test
    fun latePromptAllowsExactlyOneRetry() = runTest {
        val late = AdapterProfile.PROVISIONAL.vehicleRequestMillis + 1_000
        val steps = upToCoolant + listOf(
            step("0105", late to "7E8 03 41 05 7C\r\r>"), // late reply: discarded, not trusted
            step("0105", 20L to "7E8 03 41 05 7B\r\r>"),
        )
        val run = session(script(steps))
        assertEquals(2, run.transport.writes.count { it == "0105" })
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E8")), run.record.reading("05"))
        assertTrue(run.record.eventKinds().containsAll(listOf("late_reply_discarded", "retry")))
    }

    @Test
    fun aSecondMissedDeadlineStopsTheSession() = runTest {
        val late = AdapterProfile.PROVISIONAL.vehicleRequestMillis + 1_000
        val steps = upToCoolant + listOf(step("0105", late to "NO DATA\r\r>"), step("0105"))
        val run = session(script(steps))
        assertEquals(2, run.transport.writes.count { it == "0105" })
        assertEquals(SessionOutcome.FAILED, run.record.outcome)
        assertTrue(run.record.events.last().detail.contains("after one retry"))
    }

    @Test
    fun responsePendingExtendsTheDeadlineOnceAndStaysBounded() = runTest {
        val p = AdapterProfile.PROVISIONAL
        // Final reply after the normal deadline but inside the one extension.
        val extended = session(script(upToCoolant + step("0105", 100L to "7E8 03 7F 01 78\r", p.vehicleRequestMillis + 2_000 to "7E8 03 41 05 7B\r\r>")))
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E8")), extended.record.reading("05"))
        assertTrue("response_pending_wait" in extended.record.eventKinds())
        assertEquals(1, extended.transport.writes.count { it == "0105" })

        // A pending line with nothing after it: one extension, then the normal stop.
        val never = session(script(upToCoolant + step("0105", 100L to "7E8 03 7F 01 78\r")))
        assertEquals(SessionOutcome.FAILED, never.record.outcome)
        assertEquals(1, never.transport.writes.count { it == "0105" })

        // The adapter gave up after the pending line: recorded as no final reply.
        val gaveUp = session(script(upToCoolant + step("0105", 100L to "7E8 03 7F 01 78\r\r>")))
        assertEquals(listOf(ReadingResult.Failed("response pending, no final reply")), gaveUp.record.reading("05"))
    }

    // Pending responses (7F xx 78, p45) are resolved only by a later final reply from the same responder.
    private val twoResponders = listOf(step("0100", 20L to "7E8 06 41 00 00 00 00 00\r\r>"), step("ATDPN", 10L to "A6\r\r>"))

    @Test
    fun pendingFromOneResponderIsNotResolvedByAnotherResponder() = runTest {
        val steps = init + twoResponders + listOf(
            step("03", 10L to "7E8 03 7F 03 78\r7E9 02 43 00\r\r>"),
            step("07", 10L to "7E8 02 47 00\r7E9 02 47 00\r\r>"),
        )
        val r = session(script(steps)).record
        // 7E8 never answered 03, so stored codes are not known to be empty.
        assertFalse(DtcKind.STORED in r.dtcKindsRead)
        assertFalse(r.allCodesRead)
        assertTrue(r.dtcs.isEmpty())
        assertTrue(r.events.any { it.kind == "codes_incomplete" && "7E8" in it.detail })

        // The saved record keeps the same status.
        val dir = Files.createTempDirectory("pending-mixed").toFile()
        try {
            val store = FileSessionStore(dir)
            assertTrue(store.save(r) is SaveResult.Saved)
            assertFalse(store.load(r.id)!!.allCodesRead)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingFollowedByTheSameRespondersFinalReplyResolves() = runTest {
        val steps = init + twoResponders + listOf(
            step("03", 10L to "7E8 03 7F 03 78\r7E8 04 43 01 01 33\r\r>"),
            step("07", 10L to "7E8 02 47 00\r\r>"),
        )
        val r = session(script(steps)).record
        assertEquals(listOf(Dtc("P0133", DtcKind.STORED, "7E8")), r.dtcs)
        assertTrue(r.allCodesRead)
    }

    @Test
    fun finalReplyBeforeThePendingOneDoesNotResolveIt() = runTest {
        val steps = init + twoResponders + listOf(
            step("03", 10L to "7E8 04 43 01 01 33\r7E8 03 7F 03 78\r\r>"),
            step("07", 10L to "7E8 02 47 00\r\r>"),
        )
        val r = session(script(steps)).record
        // The earlier codes are kept, but the list is not complete.
        assertEquals(listOf(Dtc("P0133", DtcKind.STORED, "7E8")), r.dtcs)
        assertFalse(DtcKind.STORED in r.dtcKindsRead)
        assertFalse(r.allCodesRead)
    }

    @Test
    fun readingKeepsOtherRespondersValuesAndMarksTheUnresolvedOne() = runTest {
        val steps = upToCoolant + step("0105", 10L to "7E8 03 7F 01 78\r7E9 03 41 05 7B\r\r>")
        val r = session(script(steps)).record
        assertEquals(
            listOf(ReadingResult.Value(83.0, "°C", "7E9"), ReadingResult.Failed("7E8: response pending, no final reply")),
            r.reading("05"),
        )
    }

    // 0100 discovery with several responders. 7E9's map "08 10 00 00" confirms PIDs 05 and 0C only.
    private fun discovery(reply0100: String, vararg readings: ReplayStep) = script(
        init + listOf(step("0100", 20L to reply0100), step("ATDPN", 10L to "A6\r\r>")) +
            listOf(step("03", 10L to "7E9 02 43 00\r\r>"), step("07", 10L to "7E9 02 47 00\r\r>")) + readings,
    )
    private val confirmedReadings = arrayOf(
        step("0105", 10L to "7E9 03 41 05 7B\r\r>"),
        step("010C", 10L to "7E9 04 41 0C 1A F8\r\r>"),
    )

    @Test
    fun incompleteDiscoveryRequestsConfirmedReadingsAndLeavesTheRestUnknown() = runTest {
        val run = session(discovery("7E8 03 7F 01 78\r7E9 06 41 00 08 10 00 00\r\r>", *confirmedReadings))
        val r = run.record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E9")), r.reading("05"))
        assertEquals(listOf(ReadingResult.Value(1726.0, "rpm", "7E9")), r.reading("0C"))
        // 7E8 never reported its map, so absence from 7E9's map is not "unsupported".
        assertEquals(listOf(ReadingResult.UnknownCapability), r.reading("04"))
        assertEquals(listOf(ReadingResult.UnknownCapability), r.reading("0D"))
        assertFalse("0104" in run.transport.writes)
        assertFalse("010D" in run.transport.writes)
        assertTrue(r.allCodesRead)
        assertTrue(r.events.any { it.kind == "supported_pids_incomplete" && "7E8" in it.detail })
        assertEquals(listOf("05", "0C"), (r.supportedPids as SupportedPids.Known).pids)
        assertFalse((r.supportedPids as SupportedPids.Known).complete)

        val dir = Files.createTempDirectory("discovery-incomplete").toFile()
        try {
            val store = FileSessionStore(dir)
            assertTrue(store.save(r) is SaveResult.Saved)
            val reopened = store.load(r.id)!!
            assertEquals(r, reopened)
            assertEquals(listOf(ReadingResult.UnknownCapability), reopened.reading("04"))
            assertEquals(false, (reopened.supportedPids as SupportedPids.Known).complete)
            // A file saved before the flag existed reads as a complete discovery, as it was.
            val older = SessionCodec.encode(r).replace(",\n        \"complete\": false", "")
            assertFalse("\"complete\": false" in older)
            val decoded = (SessionCodec.decode(older) as DecodeResult.Ok).record
            assertEquals(true, (decoded.supportedPids as SupportedPids.Known).complete)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun completeDiscoveryStillMarksAbsentPidsUnsupported() = runTest {
        val r = session(discovery("7E8 06 41 00 08 10 00 00\r7E9 06 41 00 00 00 00 00\r\r>",
            step("0105", 10L to "7E8 03 41 05 7B\r\r>"), step("010C", 10L to "7E8 04 41 0C 1A F8\r\r>"))).record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertEquals(listOf(ReadingResult.Unsupported), r.reading("04"))
        assertEquals(listOf(ReadingResult.Unsupported), r.reading("0D"))
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E8")), r.reading("05"))
        assertEquals(listOf("05", "0C"), (r.supportedPids as SupportedPids.Known).pids)
        assertTrue((r.supportedPids as SupportedPids.Known).complete)
    }

    @Test
    fun noUsableDiscoveryLeavesSupportUnknownAndStillReadsCodes() = runTest {
        val run = session(discovery("7E8 03 7F 01 78\r7E9 03 7F 01 78\r\r>"))
        val r = run.record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertEquals(SupportedPids.Unknown, r.supportedPids)
        assertTrue(r.readings.all { it.result == ReadingResult.UnknownCapability })
        assertFalse(run.transport.writes.any { it.startsWith("01") && it != "0100" })
        assertTrue(r.allCodesRead)
    }

    // ATSP0 (automatic protocol): the first 0100 may take much longer while the adapter searches.

    @Test
    fun autoSearchLongerThanANormalDeadlineIsReadWithoutRetryAndReportsTheProtocol() = runTest {
        val p = AdapterProfile.PROVISIONAL
        val found = p.vehicleRequestMillis + 5_000
        val steps = init + listOf(
            // Map with only PID 01 set, so this full-scope session requests no readings.
            step("0100", 100L to "SEARCHING...\r", found to "7E8 06 41 00 80 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
        ) + noCodes
        val run = session(script(steps))
        val r = run.record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertTrue(r.supportedPids is SupportedPids.Known)
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN"), run.transport.writes.take(6))
        assertEquals(1, run.transport.writes.count { it == "0100" })
        assertTrue(r.events.any { it.kind == ElmObdReader.PROTOCOL_NOTE && it.detail == "6: ISO 15765-4 CAN (11 bit ID, 500 kbaud), found by automatic search" })
        assertFalse(r.readNothing)
    }

    @Test
    fun autoSearchThatFindsNothingStopsBoundedAndReadsNothing() = runTest {
        val steps = init + listOf(
            step("0100", 100L to "SEARCHING...\r", 12_000L to "UNABLE TO CONNECT\r\r>"),
            step("ATDPN", 10L to "A0\r\r>"),
        )
        val r = session(script(steps)).record
        assertEquals(SessionOutcome.FAILED, r.outcome)
        assertTrue(r.events.last().detail, r.events.last().detail.startsWith("no_protocol"))
        assertTrue(r.events.any { it.kind == ElmObdReader.PROTOCOL_NOTE && it.detail == "0: automatic search found no protocol" })
        assertTrue(r.readNothing)
        assertFalse(r.allCodesRead)
    }

    @Test
    fun busInitFailureOnEveryRequestReadsNothingAndNeverClaimsNoCodes() = runTest {
        // Shaped like the owner's 2026-10-29 captures: each request ends in "BUS INIT: ...ERROR".
        val busInitError = arrayOf(30L to "BUS INIT: ", 750L to ".", 1_350L to ".", 1_950L to ".", 2_900L to "ERROR\r\r>")
        val steps = init + listOf(
            step("0100", *busInitError),
            // A bare "3" as in the capture: bus-init status shows only with automatic search off (p33).
            step("ATDPN", 10L to "3\r\r>"),
            step("03", *busInitError),
            step("07", *busInitError),
        )
        val r = session(script(steps)).record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertTrue(r.readNothing)
        assertFalse(r.allCodesRead)
        assertTrue(r.dtcKindsRead.isEmpty())
        assertEquals(SupportedPids.Unknown, r.supportedPids)
        val reasons = r.events.filter { it.kind == SessionController.CODES_NOT_READ || it.kind == ElmObdReader.SUPPORTED_PIDS_UNKNOWN }.map { it.detail }
        assertTrue(r.events.any { it.kind == ElmObdReader.PROTOCOL_NOTE && it.detail == "3: ISO 9141-2 (5 baud init), fixed setting" })
        assertEquals(3, reasons.size)
        assertTrue(reasons.toString(), reasons.all { "BUS INIT: ...ERROR" in it })
    }

    @Test
    fun staleBytesBeforeAWriteAreDiscarded() = runTest {
        val steps = init + protocol + noCodes + listOf(
            // A second reply in the same chunk and a stray one 30 ms later belong to no request.
            step("0104", 10L to "7E8 03 41 04 80\r\r>7E8 03 41 05 00\r\r>", 30L to "7E8 03 41 05 01\r\r>"),
            step("0105", 100L to "7E8 03 41 05 7B\r\r>"),
        )
        val r = session(script(steps)).record
        assertEquals(listOf(ReadingResult.Value(83.0, "°C", "7E8")), r.reading("05"))
        assertTrue("stale_reply_discarded" in r.eventKinds())
    }

    @Test
    fun disconnectBeforeConnectionDuringInitBetweenRequestsAndMidReply() = runTest {
        val before = session(script(init, connected = false))
        assertEquals(SessionOutcome.DISCONNECTED, before.record.outcome)
        assertTrue(before.transport.writes.isEmpty())

        val duringInit = session(script(listOf(step("ATE0", 10L to "ATE0\rOK\r\r>"), step("ATL0", disconnectAfter = 5))))
        assertEquals(SessionOutcome.DISCONNECTED, duringInit.record.outcome)

        val between = session(script(init + protocol + step("03", 10L to "7E8 02 43 00\r\r>", disconnectAfter = 20) + step("07")))
        assertEquals(SessionOutcome.DISCONNECTED, between.record.outcome)
        assertEquals(listOf(DtcKind.STORED), between.record.dtcKindsRead)
        assertFalse("07" in between.transport.writes)

        val midReply = session(ReplayTransport.bundled("disconnect-mid-reply")).record
        assertEquals(SessionOutcome.DISCONNECTED, midReply.outcome)
        assertFalse(midReply.complete)
        assertTrue(midReply.supportedPids is SupportedPids.Known)
        assertTrue(midReply.dtcKindsRead.isEmpty())
    }

    @Test
    fun adapterAnswersThatAreNotOkOrResetStopTheSession() = runTest {
        val rejected = session(script(listOf(step("ATE0", 10L to "ATE0\r?\r\r>"))))
        assertEquals(SessionOutcome.FAILED, rejected.record.outcome)
        assertTrue(rejected.record.events.last().detail.startsWith("adapter_rejected"))

        val noProtocol = session(script(init + step("0100", 10L to "UNABLE TO CONNECT\r\r>") + step("ATDPN", 10L to "A0\r\r>")))
        assertEquals(SessionOutcome.FAILED, noProtocol.record.outcome)
        assertTrue(noProtocol.record.events.last().detail.startsWith("no_protocol"))

        val reset = session(script(init + protocol + step("03", 10L to "LV RESET\r\r>")))
        assertTrue(reset.record.events.last().detail.startsWith("adapter_reset"))
        assertFalse("07" in reset.transport.writes)
    }

    @Test
    fun noDataAndNegativeRepliesAreRecordedNotGuessed() = runTest {
        val steps = init + protocol + listOf(
            step("03", 10L to "NO DATA\r\r>"),
            step("07", 10L to "7E8 03 7F 07 11\r\r>"),
            step("0104", 10L to "NO DATA\r\r>"),
            step("0105", 10L to "7E8 03 41 05 7B\r\r>"),
            step("010C", 10L to "7E8 03 41 0D 00\r\r>"),
            step("010D", 10L to "CAN ERROR\r\r>"),
        )
        val r = session(script(steps)).record
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertTrue(r.dtcKindsRead.isEmpty())
        assertEquals(listOf("codes_not_read", "codes_not_read"), r.eventKinds().filter { it == "codes_not_read" })
        assertEquals(listOf(ReadingResult.NoData), r.reading("04"))
        assertEquals(listOf(ReadingResult.Failed("malformed reply: reply is for a different PID")), r.reading("0C"))
        assertEquals(listOf(ReadingResult.Failed("adapter reported CAN ERROR")), r.reading("0D"))
    }

    @Test
    fun cancellationStopsReadsAndKeepsPartialData() = runTest {
        val transport = ReplayTransport(script(upToCoolant + step("0105")), testScheduler.timeSource)
        val controller = SessionController(this, clock = { testScheduler.currentTime }, ids = { "replay-1" })
        controller.start(ElmObdReader(transport, "replay:test"))
        advanceTimeBy(2_000)
        runCurrent()
        controller.cancel()
        advanceUntilIdle()
        val r = (controller.state.value as SessionState.Finished).record
        assertEquals(SessionOutcome.CANCELLED, r.outcome)
        assertTrue(r.allCodesRead)
        assertEquals(1, r.reading("04").size)
        assertEquals("0105", transport.writes.last())
        assertEquals(2_000L, r.endedAt)
    }

    @Test
    fun replayIsByteIdenticalAcrossRuns() {
        // F15: same script, clock and id give the same JSON. Each run gets its own virtual clock.
        fun encodeOnce(): String {
            var json = ""
            runTest { json = SessionCodec.encode(session(ReplayTransport.bundled("can-complete")).record) }
            return json
        }
        val first = encodeOnce()
        assertEquals(first, encodeOnce())
        assertTrue("\"provenance\": \"REPLAY\"" in first)
    }

    @Test
    fun replaySessionSurvivesSaveAndReopenWithoutAndroid() = runTest {
        // G3 over ElmObdReader and ReplayTransport, on a plain JVM.

        val dir = Files.createTempDirectory("replay-g3").toFile()
        try {
            val record = session(ReplayTransport.bundled("disconnect-mid-reply")).record
            val store = FileSessionStore(dir)
            assertTrue(store.save(record) is SaveResult.Saved)
            assertEquals(record, store.load(record.id))
            assertEquals(Provenance.REPLAY, store.load(record.id)?.provenance)
            assertNull(store.load("missing"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
