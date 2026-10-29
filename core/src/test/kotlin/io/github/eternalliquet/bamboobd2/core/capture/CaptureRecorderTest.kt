package io.github.eternalliquet.bamboobd2.core.capture

import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterTransport
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayChunk
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayStep
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayTransport
import io.github.eternalliquet.bamboobd2.core.protocol.Request
import io.github.eternalliquet.bamboobd2.core.protocol.TransportKind
import io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader
import io.github.eternalliquet.bamboobd2.core.session.ReadScope
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.SessionCodec
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Codes-only (L2) sessions and raw-capture recording, over synthetic replay in virtual time. */
class CaptureRecorderTest {

    /** Stands in for the Bluetooth transport's recording hooks: records every write and chunk. */
    private class RecordingTransport(private val inner: AdapterTransport, private val recorder: CaptureRecorder) : AdapterTransport {
        override val kind: TransportKind = TransportKind.REPLAY
        override suspend fun write(request: Request) {
            recorder.onWrite(request.text)
            inner.write(request)
        }
        override suspend fun read(): ByteArray? = inner.read()?.also(recorder::onRead)
    }

    private fun step(request: String, vararg chunks: Pair<Long, String>) = ReplayStep(request, chunks.map { ReplayChunk(it.first, it.second) })

    // Shaped like the data sheet's CAN examples; 7E8 reports one stored code, P0133.
    private val codesOnly = ReplayScript(
        "l2", "l2", "SYNTHETIC", "constructed", "built in CaptureRecorderTest",
        steps = listOf(
            step("ATE0", 10L to "ATE0\rOK\r\r>"),
            step("ATL0", 10L to "OK\r\r>"),
            step("ATH1", 10L to "OK\r\r>"),
            step("ATSP0", 10L to "OK\r\r>"),
            step("0100", 100L to "SEARCHING...\r", 400L to "7E8 06 41 00 BE 1F B8 10\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
            step("03", 30L to "7E8 04 43 01 01 33\r\r>"),
            step("07", 30L to "7E8 02 47 00\r\r>"),
        ),
    )

    private fun TestScope.session(transport: AdapterTransport): SessionRecord {
        val controller = SessionController(this, clock = { testScheduler.currentTime }, ids = { "live-1" })
        controller.start(ElmObdReader(transport, "bluetooth:classic"), ReadScope.CODES_ONLY)
        advanceUntilIdle()
        return (controller.state.value as SessionState.Finished).record
    }

    @Test
    fun codesOnlySessionSendsSetupMapAndCodeRequestsOnly() = runTest {
        val replay = ReplayTransport(codesOnly, testScheduler.timeSource)
        val r = session(replay)
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        // Any other request would diverge from the script and fail the session.
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "03", "07"), replay.writes)
        assertTrue(r.readings.isEmpty())
        assertTrue(r.events.any { it.kind == SessionController.READINGS_NOT_REQUESTED })
        assertTrue(r.allCodesRead)
        assertEquals(listOf("P0133"), r.dtcs.map { it.code })
    }

    @Test
    fun aRawCaptureIsRejectedUntilReviewedAndThenReplaysTheSameSession() {
        var captured = ""
        var original = ""
        runTest {
            val recorder = CaptureRecorder(testScheduler.timeSource)
            original = SessionCodec.encode(session(RecordingTransport(ReplayTransport(codesOnly, testScheduler.timeSource), recorder)))
            captured = recorder.json("live-1", "test capture")
        }
        val raw = ReplayScript.serializer().let { kotlinx.serialization.json.Json.decodeFromString(it, captured) }
        assertEquals(CaptureRecorder.RAW_CAPTURE, raw.provenance)
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "03", "07"), raw.steps.map { it.request })
        assertEquals(listOf(ReplayChunk(100, "SEARCHING...\r"), ReplayChunk(400, "7E8 06 41 00 BE 1F B8 10\r\r>")), raw.steps[4].chunks)
        try {
            ReplayScript.parse(captured)
            fail("a raw capture must not load as a fixture")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), "RAW_CAPTURE" in e.message.orEmpty())
        }

        // After review (hash and evidence log recorded), the same bytes replay to the same session.
        val reviewed = raw.copy(provenance = "SANITIZED_CAPTURE", sha256 = "0".repeat(64), evidenceLog = "docs/evidence/example.md")
        assertTrue(reviewed.problems().isEmpty())
        var replayed = ""
        runTest { replayed = SessionCodec.encode(session(ReplayTransport(reviewed, testScheduler.timeSource))) }
        assertEquals(original, replayed)
    }
}
