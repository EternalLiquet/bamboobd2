package io.github.eternalliquet.bamboobd2.core.session

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.reader.DiagnosticReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    private fun TestScope.controller() = SessionController(
        scope = this,
        clock = { testScheduler.currentTime },
        ids = { "session-1" },
    )

    private fun TestScope.runToEnd(scenario: FakeScenario): SessionRecord {
        val c = controller()
        assertTrue(c.start(FakeReader(scenario, stepDelayMillis = 100)))
        advanceUntilIdle()
        return (c.state.value as SessionState.Finished).record
    }

    @Test
    fun noCodesScenarioCompletesWithReadingsAndNoCodes() = runTest {
        val r = runToEnd(FakeScenario.NO_CODES)
        assertTrue(r.complete)
        assertEquals(SessionOutcome.COMPLETE, r.outcome)
        assertEquals(Provenance.SIMULATED, r.provenance)
        assertTrue(r.dtcs.isEmpty())
        assertEquals(4, r.readings.size)
        assertTrue(r.readings.all { it.result is ReadingResult.Value })
    }

    @Test
    fun twoCodesScenarioReportsStoredAndPendingCode() = runTest {
        val r = runToEnd(FakeScenario.TWO_CODES)
        assertEquals(listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0171", DtcKind.PENDING)), r.dtcs)
        assertTrue(r.complete)
    }

    @Test
    fun unsupportedPidIsMarkedUnsupportedAndNeverRequested() = runTest {
        val requested = mutableListOf<String>()
        val reader = object : DiagnosticReader by FakeReader(FakeScenario.UNSUPPORTED_PID, 100) {
            private val inner = FakeReader(FakeScenario.UNSUPPORTED_PID, 100)
            override suspend fun readPid(pid: String): ReadingResult {
                requested += pid
                return inner.readPid(pid)
            }
        }
        val c = controller()
        c.start(reader)
        advanceUntilIdle()
        val r = (c.state.value as SessionState.Finished).record
        assertEquals(ReadingResult.Unsupported, r.readings.single { it.pid == "0D" }.result)
        assertFalse("0D" in requested)
        assertEquals(listOf("04", "05", "0C"), requested)
    }

    @Test
    fun unknownSupportMapRequestsNothingAndReportsUnknownCapability() = runTest {
        val requested = mutableListOf<String>()
        val reader = object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 100) {
            override suspend fun readSupportedPids(): SupportedPids = SupportedPids.Unknown
            override suspend fun readPid(pid: String): ReadingResult {
                requested += pid
                return ReadingResult.Value(1.0, "x")
            }
        }
        val c = controller()
        c.start(reader)
        advanceUntilIdle()
        val r = (c.state.value as SessionState.Finished).record
        assertTrue(requested.isEmpty())
        assertTrue(r.readings.all { it.result == ReadingResult.UnknownCapability })
    }

    @Test
    fun cancelKeepsPartialResultsAndStopsReading() = runTest {
        val c = controller()
        c.start(FakeReader(FakeScenario.TWO_CODES, stepDelayMillis = 100))
        // Supported map and stored codes are read by t=200; cancel during the pending-code read.
        advanceTimeBy(250)
        runCurrent()
        c.cancel()
        advanceUntilIdle()
        val r = (c.state.value as SessionState.Finished).record
        assertFalse(r.complete)
        assertEquals(SessionOutcome.CANCELLED, r.outcome)
        assertEquals(listOf(Dtc("P0133", DtcKind.STORED)), r.dtcs)
        assertTrue(r.readings.isEmpty())
        assertTrue(r.supportedPids is SupportedPids.Known)
        assertEquals(250L, r.endedAt)
    }

    @Test
    fun secondStartWhileRunningIsRefused() = runTest {
        val c = controller()
        assertTrue(c.start(FakeReader(FakeScenario.NO_CODES, 100)))
        assertFalse(c.start(FakeReader(FakeScenario.TWO_CODES, 100)))
        c.cancel()
        advanceUntilIdle()
    }
}
