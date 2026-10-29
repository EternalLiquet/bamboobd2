package io.github.eternalliquet.bamboobd2.core.live

import io.github.eternalliquet.bamboobd2.core.model.*
import io.github.eternalliquet.bamboobd2.core.reader.*
import io.github.eternalliquet.bamboobd2.core.protocol.*
import io.github.eternalliquet.bamboobd2.core.storage.*
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import io.github.eternalliquet.bamboobd2.core.session.Clock
import io.github.eternalliquet.bamboobd2.core.session.IdGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class LiveReadControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun preparationOnceAndSelectionThenPausePreservesResponderGaps() = runTest {
        val calls = mutableListOf<String>()
        val c = LiveReadController(this, Clock { 1000 - testScheduler.currentTime },
            MonotonicClock { testScheduler.currentTime }, IdGenerator { "live-1" })
        val reader = object : DiagnosticReader {
            override val provenance = Provenance.SIMULATED
            override val sourceLabel = "fake"
            override suspend fun open() { calls += "open" }
            override suspend fun readSupportedPids(): SupportedPids { calls += "discover"; return SupportedPids.Known(listOf("04"), pages = listOf(
                PageEvidence("7E8", "00", PageStatus.VALID, listOf("04")),
                PageEvidence("7E9", "00", PageStatus.VALID, listOf("04")))) }
            override suspend fun readDtcs(kind: DtcKind): DtcRead { calls += kind.name; return DtcRead.Read(emptyList()) }
            override suspend fun readPid(pid: String): List<ReadingResult> {
                calls += pid; delay(20); return listOf(ReadingResult.Value(42.0, "%", "7E8"))
            }
        }
        assertTrue(c.prepare { reader })
        runCurrent()
        assertTrue(c.state.value is LiveReadState.Ready)
        assertFalse(c.start(listOf("04", "04")))
        assertFalse(c.start(listOf("05")))
        assertEquals(listOf("open", "discover", "STORED", "PENDING"), calls)
        assertTrue(c.start(listOf("04")))
        runCurrent()
        c.pause()
        advanceTimeBy(20); runCurrent()
        val paused = c.state.value as LiveReadState.Paused
        assertEquals(2, paused.record.recording!!.samples.size)
        assertEquals(listOf("7E8", "7E9"), paused.record.recording!!.samples.map { it.ecu })
        assertEquals(listOf(LiveSampleOutcome.VALUE, LiveSampleOutcome.MISSING), paused.record.recording!!.samples.map { it.outcome })
        assertEquals(listOf("open", "discover", "STORED", "PENDING", "04"), calls)
        c.stop()
        runCurrent()
        val record = (c.state.value as LiveReadState.Finished).record
        assertEquals(LiveStopReason.USER, record.recording!!.stopReason)
        assertTrue(record.readings.isEmpty())
    }

    @Test fun deadlineIncludesReadyAndPauseAndStopBeforeStart() = runTest {
        val c = LiveReadController(this, Clock { 5000 }, MonotonicClock { testScheduler.currentTime }, IdGenerator { "live-2" },
            LiveReadPolicy(durationMillis = 100))
        var opened = 0
        assertTrue(c.prepare { _ -> object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 0) {
            override suspend fun open() { opened++ }
        } })
        c.stop()
        advanceUntilIdle()
        assertEquals(0, opened)
        assertEquals(SessionOutcome.COMPLETE, (c.state.value as LiveReadState.Finished).record.outcome)
        assertTrue(c.prepare { FakeReader(FakeScenario.NO_CODES, 0) })
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(LiveStopReason.DURATION_LIMIT, (c.state.value as LiveReadState.Finished).record.recording!!.stopReason)
    }
    @Test fun elmGateSpacesSetupDiscoveryCodesAndRetryGlobally() = runTest {
        val steps = listOf(
            ReplayStep("ATE0", listOf(ReplayChunk(1, "ATE0\rOK\r\r>"))),
            ReplayStep("ATL0", listOf(ReplayChunk(1, "OK\r\r>"))),
            ReplayStep("ATH1", listOf(ReplayChunk(1, "OK\r\r>"))),
            ReplayStep("ATSP0", listOf(ReplayChunk(1, "OK\r\r>"))),
            ReplayStep("0100", listOf(ReplayChunk(1, "7E8 06 41 00 08 00 00 00\r\r>"))),
            ReplayStep("ATDPN", listOf(ReplayChunk(1, "A6\r\r>"))),
            ReplayStep("03", listOf(ReplayChunk(1, "7E8 02 43 00\r\r>"))),
            ReplayStep("07", listOf(ReplayChunk(1, "7E8 02 47 00\r\r>"))),
            ReplayStep("0105", listOf(ReplayChunk(11_000, "7E8 03 41 05 7C\r\r>"))),
            ReplayStep("0105", listOf(ReplayChunk(1, "7E8 03 41 05 7B\r\r>"))),
        )
        val replay = ReplayTransport(ReplayScript("synthetic", "bounded", "SYNTHETIC", "constructed", "test", steps = steps), testScheduler.timeSource)
        val times = mutableListOf<Long>()
        val c = LiveReadController(this, Clock { 1000 }, MonotonicClock { testScheduler.currentTime }, IdGenerator { "live-3" })
        assertTrue(c.prepare { gate ->
            val observed = object : RequestGate {
                override suspend fun beforeWrite(retry: Boolean) { gate.beforeWrite(retry); times += testScheduler.currentTime }
                override fun wrote(retry: Boolean) = gate.wrote(retry)
                override fun rejectedWrite() = gate.rejectedWrite()
            }
            ElmObdReader(replay, "synthetic", discoveryClockMillis = { testScheduler.currentTime }, requestGate = observed)
        })
        advanceTimeBy(5_000); runCurrent()
        assertTrue(c.state.value is LiveReadState.Ready)
        assertTrue(c.start(listOf("05")))
        advanceTimeBy(15_000); runCurrent()
        c.stop()
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "03", "07", "0105", "0105"), replay.writes)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 500 })
        assertEquals(10, (c.state.value as LiveReadState.Finished).record.recording!!.writeCount)
        assertEquals(1, (c.state.value as LiveReadState.Finished).record.recording!!.retryCount)
    }

    @Test fun realStoreRoundTripsPartialAndCompleteAndRejectsInvalidLiveSamples() {
        val base = SessionRecord(2, "r1", 100, 200, Provenance.REPLAY, "synthetic", true, SessionOutcome.COMPLETE,
            recording = LiveRecording(selectedPids = listOf("05"), writeCount = 8,
                samples = listOf(LiveSample(1, 120, 20, "05", "7E8", "°C", LiveSampleOutcome.VALUE, 83.0),
                    LiveSample(2, 120, 20, "05", "7E9", "°C", LiveSampleOutcome.MISSING, reason = "silent")),
                events = listOf(LiveEvent(100, LiveEventKind.END, LiveStopReason.USER)), stopReason = LiveStopReason.USER))
        val dir = tmp.newFolder()
        assertFalse(base.readNothing)
        val store = FileSessionStore(dir)
        assertTrue(store.save(base) is SaveResult.Saved)
        assertEquals(base, store.load("r1"))
        val partial = base.copy(id = "r2", complete = false, outcome = SessionOutcome.DISCONNECTED,
            recording = base.recording!!.copy(stopReason = LiveStopReason.DISCONNECTED,
                events = listOf(LiveEvent(100, LiveEventKind.END, LiveStopReason.DISCONNECTED))))
        assertTrue(store.save(partial) is SaveResult.Saved)
        assertEquals(partial, store.load("r2"))
        val bad = base.copy(recording = base.recording!!.copy(samples = listOf(base.recording!!.samples[0].copy(value = Double.NaN))))
        assertTrue(store.save(bad) is SaveResult.Failed)
        val outOfOrderEnd = base.copy(recording = base.recording!!.copy(
            events = listOf(LiveEvent(10, LiveEventKind.END, LiveStopReason.USER))))
        assertTrue(store.save(outOfOrderEnd) is SaveResult.Failed)
        val old = SessionRecord(1, "old", 100, 200, Provenance.SIMULATED, "fake", true, SessionOutcome.COMPLETE)
        val encoded = SessionCodec.encode(old)
        assertFalse(encoded.contains("\"recording\""))
        assertTrue(store.save(old) is SaveResult.Saved)
        assertEquals(encoded, File(dir, "old.1.json").readText())
        assertEquals(old, store.load("old"))
    }

    @Test fun fourPidsShareGateAndPausedTimeDoesNotGiveBurstCredit() = runTest {
        val times = mutableListOf<Long>()
        val c = LiveReadController(this, Clock { 1000 - testScheduler.currentTime },
            MonotonicClock { testScheduler.currentTime }, IdGenerator { "live-4" }, LiveReadPolicy(maxSamples = 4))
        val fake = FakeReader(FakeScenario.NO_CODES, 0)
        assertTrue(c.prepare { gate -> object : DiagnosticReader by fake {
            override suspend fun readPidObservations(pid: String): List<ReadingResult> {
                gate.beforeWrite(false); times += testScheduler.currentTime; gate.wrote(false)
                return listOf(ReadingResult.Value(1.0, Mode01Catalog.lookup(pid)!!.unit, "7E8"))
            }
        } })
        runCurrent()
        assertTrue(c.start(listOf("04", "05", "0C", "0D")))
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertTrue(c.pause())
        advanceTimeBy(700); runCurrent()
        assertEquals(listOf(0L), times)
        assertTrue(c.resume())
        runCurrent()
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(0L, 800L, 1_300L, 1_800L), times)
        val live = (c.state.value as LiveReadState.Finished).record.recording!!
        assertEquals(LiveStopReason.SAMPLE_LIMIT, live.stopReason)
        assertEquals(listOf(1L, 2L, 3L, 4L), live.samples.map { it.sequence })
        assertEquals(listOf(0L, 800L, 1_300L, 1_800L), live.samples.map { it.elapsedMillis })
        assertTrue(live.samples.zipWithNext().all { (a, b) -> b.atMillis < a.atMillis })
        assertEquals(listOf(LiveEventKind.PAUSE, LiveEventKind.RESUME, LiveEventKind.END), live.events.map { it.kind })
    }

    @Test fun writeAndRetryLimitsStopBeforeExtraWrite() = runTest {
        for ((limit, expected) in listOf(true to LiveStopReason.WRITE_LIMIT, false to LiveStopReason.RETRY_LIMIT)) {
            val c = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime },
                IdGenerator { if (limit) "writes" else "retries" },
                if (limit) LiveReadPolicy(maxWrites = 1) else LiveReadPolicy(maxRetries = 0))
            var actual = 0
            assertTrue(c.prepare { gate -> object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 0) {
                override suspend fun open() {
                    gate.beforeWrite(false); actual++; gate.wrote(false)
                    gate.beforeWrite(if (limit) false else true); actual++; gate.wrote(!limit)
                }
            } })
            advanceUntilIdle()
            assertEquals(1, actual)
            assertEquals(expected, (c.state.value as LiveReadState.Finished).record.recording!!.stopReason)
        }
    }

    @Test fun responderBatchAtSampleBoundKeepsCompletedFirstOutcome() = runTest {
        val c = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime },
            IdGenerator { "bound" }, LiveReadPolicy(maxSamples = 1))
        assertTrue(c.prepare { object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 0) {
            override suspend fun readPidObservations(pid: String) = listOf(
                ReadingResult.Value(10.0, "%", "7E8"), ReadingResult.Value(20.0, "%", "7E9"))
        } })
        runCurrent()
        assertTrue(c.start(listOf("04")))
        runCurrent()
        val live = (c.state.value as LiveReadState.Finished).record.recording!!
        assertEquals(LiveStopReason.SAMPLE_LIMIT, live.stopReason)
        assertEquals(listOf("7E8"), live.samples.map { it.ecu })
    }

    @Test fun disconnectAfterValueRetainsPartialAndStopMidReplyAddsNoValue() = runTest {
        val fake = FakeReader(FakeScenario.NO_CODES, 0)
        val c = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime }, IdGenerator { "disconnect" })
        var calls = 0
        assertTrue(c.prepare { object : DiagnosticReader by fake {
            override suspend fun readPidObservations(pid: String): List<ReadingResult> {
                calls++
                if (calls == 1) return listOf(ReadingResult.Value(1.0, "%", "7E8"))
                delay(100)
                throw ReaderStoppedException(StopReason.DISCONNECTED, "synthetic disconnect")
            }
        } })
        runCurrent(); assertTrue(c.start(listOf("04")))
        advanceTimeBy(101); runCurrent()
        val partial = (c.state.value as LiveReadState.Finished).record
        assertEquals(SessionOutcome.DISCONNECTED, partial.outcome)
        assertEquals(1, partial.recording!!.samples.size)
        assertEquals(1.0, partial.recording!!.samples.single().value!!, 0.0)

        val stopped = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime }, IdGenerator { "stop-mid" })
        assertTrue(stopped.prepare { object : DiagnosticReader by fake {
            override suspend fun readPidObservations(pid: String): List<ReadingResult> { delay(1000); return listOf(ReadingResult.Value(9.0, "%")) }
        } })
        runCurrent(); assertTrue(stopped.start(listOf("04")))
        runCurrent(); stopped.stop(LiveStopReason.CANCELLED)
        advanceTimeBy(1000); runCurrent()
        val final = (stopped.state.value as LiveReadState.Finished).record
        assertEquals(SessionOutcome.CANCELLED, final.outcome)
        assertTrue(final.recording!!.samples.isEmpty())
    }

    @Test fun pausedSessionExpiresFromPreparationStart() = runTest {
        val c = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime },
            IdGenerator { "paused-limit" }, LiveReadPolicy(durationMillis = 100))
        assertTrue(c.prepare { FakeReader(FakeScenario.NO_CODES, 0) })
        runCurrent(); assertTrue(c.start(listOf("04")))
        runCurrent(); assertTrue(c.pause())
        advanceTimeBy(1); runCurrent(); assertTrue(c.state.value is LiveReadState.Paused)
        advanceTimeBy(99); runCurrent()
        assertEquals(LiveStopReason.DURATION_LIMIT, (c.state.value as LiveReadState.Finished).record.recording!!.stopReason)
    }

    @Test fun elmInvalidSecondResponderIsFailureWithOwnIdentity() = runTest {
        val steps = listOf(
            ReplayStep("ATE0", listOf(ReplayChunk(1, "ATE0\rOK\r\r>"))),
            ReplayStep("ATL0", listOf(ReplayChunk(1, "OK\r\r>"))),
            ReplayStep("ATH1", listOf(ReplayChunk(1, "OK\r\r>"))),
            ReplayStep("ATSP0", listOf(ReplayChunk(1, "OK\r\r>"))),
            ReplayStep("0100", listOf(ReplayChunk(1, "7E8 06 41 00 08 00 00 00\r7E9 06 41 00 08 00 00 00\r\r>"))),
            ReplayStep("ATDPN", listOf(ReplayChunk(1, "A6\r\r>"))),
            ReplayStep("03", listOf(ReplayChunk(1, "7E8 02 43 00\r\r>"))),
            ReplayStep("07", listOf(ReplayChunk(1, "7E8 02 47 00\r\r>"))),
            ReplayStep("0105", listOf(ReplayChunk(1, "7E8 03 41 05 7B\r7E9 03 41 0C 7C\r\r>"))),
        )
        val replay = ReplayTransport(ReplayScript("bad-ecu", "invalid", "SYNTHETIC", "constructed", "test", steps = steps), testScheduler.timeSource)
        val c = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime },
            IdGenerator { "bad-ecu" }, LiveReadPolicy(maxSamples = 2))
        assertTrue(c.prepare { gate -> ElmObdReader(replay, "synthetic", discoveryClockMillis = { testScheduler.currentTime }, requestGate = gate) })
        advanceTimeBy(5_000); runCurrent(); assertTrue(c.start(listOf("05")))
        advanceTimeBy(1_000); runCurrent()
        val samples = (c.state.value as LiveReadState.Finished).record.recording!!.samples
        assertEquals(listOf("7E8", "7E9"), samples.map { it.ecu })
        assertEquals(listOf(LiveSampleOutcome.VALUE, LiveSampleOutcome.FAILURE), samples.map { it.outcome })
        assertNull(samples[1].value)
        assertTrue(samples[1].reason!!.contains("different PID"))
    }

    @Test fun cancellationDuringPassiveResyncPreventsRetry() = runTest {
        val steps = listOf("ATE0", "ATL0", "ATH1", "ATSP0").mapIndexed { i, cmd ->
            ReplayStep(cmd, listOf(ReplayChunk(1, if (i == 0) "ATE0\rOK\r\r>" else "OK\r\r>")))
        } + listOf(
            ReplayStep("0100", listOf(ReplayChunk(1, "7E8 06 41 00 08 00 00 00\r\r>"))),
            ReplayStep("ATDPN", listOf(ReplayChunk(1, "A6\r\r>"))),
            ReplayStep("03", listOf(ReplayChunk(1, "7E8 02 43 00\r\r>"))),
            ReplayStep("07", listOf(ReplayChunk(1, "7E8 02 47 00\r\r>"))),
            ReplayStep("0105", listOf(ReplayChunk(11_000, "7E8 03 41 05 7B\r\r>"))),
            ReplayStep("0105", listOf(ReplayChunk(1, "7E8 03 41 05 7C\r\r>"))),
        )
        val replay = ReplayTransport(ReplayScript("resync", "cancel", "SYNTHETIC", "constructed", "test", steps = steps), testScheduler.timeSource)
        val c = LiveReadController(this, Clock { 0 }, MonotonicClock { testScheduler.currentTime }, IdGenerator { "resync" })
        assertTrue(c.prepare { gate -> ElmObdReader(replay, "synthetic", discoveryClockMillis = { testScheduler.currentTime }, requestGate = gate) })
        advanceTimeBy(5_000); runCurrent(); assertTrue(c.start(listOf("05")))
        advanceTimeBy(10_500); runCurrent()
        c.stop(LiveStopReason.CANCELLED)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, replay.writes.count { it == "0105" })
        val final = (c.state.value as LiveReadState.Finished).record
        assertEquals(SessionOutcome.CANCELLED, final.outcome)
        assertTrue(final.recording!!.samples.isEmpty())
    }

}
