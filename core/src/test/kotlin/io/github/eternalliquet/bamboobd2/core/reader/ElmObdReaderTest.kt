package io.github.eternalliquet.bamboobd2.core.reader

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
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

    private fun step(request: String, vararg chunks: Pair<Long, String>, disconnectAfter: Long? = null) =
        ReplayStep(request, chunks.map { ReplayChunk(it.first, it.second) }, disconnectAfter)

    private fun script(steps: List<ReplayStep>, connected: Boolean = true) =
        ReplayScript("test", "test", "SYNTHETIC", "constructed", "built in ElmObdReaderTest", connected = connected, steps = steps)

    private val init = listOf(
        step("ATE0", 10L to "ATE0\rOK\r\r>"),
        step("ATL0", 10L to "OK\r\r>"),
        step("ATH1", 10L to "OK\r\r>"),
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
        controller.start(ElmObdReader(transport, "replay:${script.id}", profile))
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
        assertEquals(SupportedPids.Known(listOf("01", "03", "04", "05", "06", "07", "0C", "0D", "0E", "0F", "10", "11", "13", "14", "15", "1C")), r.supportedPids)
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
