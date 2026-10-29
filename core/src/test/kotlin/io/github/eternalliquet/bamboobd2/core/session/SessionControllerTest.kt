package io.github.eternalliquet.bamboobd2.core.session

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.model.PageEvidence
import io.github.eternalliquet.bamboobd2.core.model.PageStatus
import io.github.eternalliquet.bamboobd2.core.reader.DiagnosticReader
import io.github.eternalliquet.bamboobd2.core.reader.DtcRead
import io.github.eternalliquet.bamboobd2.core.reader.FakeReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    @Test
    fun broadSnapshotRequestsOnlyPositiveCatalogIntersectionOnceAndKeepsResponderSamples() = runTest {
        val requested = mutableListOf<String>()
        val reader = object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 0) {
            override suspend fun readSupportedPids() = SupportedPids.Known(
                listOf("04", "05", "0B", "0C", "11", "12", "42", "45"), complete = false,
                pages = listOf(
                    PageEvidence("7E8", "00", PageStatus.VALID, listOf("04", "05", "0B", "0C", "11", "12"), true),
                    PageEvidence("7E9", "00", PageStatus.VALID, listOf("04", "05", "0B", "0C", "11", "12"), true),
                    PageEvidence("7E8", "20", PageStatus.VALID, emptyList(), true),
                    PageEvidence("7E9", "20", PageStatus.MISSING),
                    PageEvidence("7E8", "40", PageStatus.VALID, listOf("42", "45")),
                ),
            )
            override suspend fun readPid(pid: String): List<ReadingResult> {
                requested += pid
                delay(10)
                return if (pid == "04") listOf(ReadingResult.Value(50.0, "%", "7E8"), ReadingResult.Value(60.0, "%", "7E9"))
                else if (pid == "45") listOf(ReadingResult.NoData)
                else listOf(ReadingResult.Value(1.0, "kPa", "7E8"))
            }
        }
        val c = controller()
        c.start(reader)
        advanceUntilIdle()
        val record = (c.state.value as SessionState.Finished).record
        assertEquals(listOf("04", "05", "0B", "0C", "11", "42", "45"), requested)
        assertEquals(listOf("7E8", "7E9"), record.readings.filter { it.pid == "04" }.map { (it.result as ReadingResult.Value).ecu })
        assertEquals(listOf(10L, 10L), record.readings.filter { it.pid == "04" }.map { it.atMillis })
        assertEquals(ReadingResult.NoData, record.readings.single { it.pid == "45" }.result)
        assertFalse(record.readings.any { it.pid == "12" })
    }

    @Test
    fun broadSnapshotStopsAfterFailureAndCodesOnlyNeverRequestsSensors() = runTest {
        val requested = mutableListOf<String>()
        val reader = object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 0) {
            override suspend fun readSupportedPids() = SupportedPids.Known(listOf("04", "05", "0B", "42"))
            override suspend fun readPid(pid: String): List<ReadingResult> {
                requested += pid
                if (pid == "0B") throw IllegalStateException("later request failed")
                return listOf(ReadingResult.Value(1.0, "%", "7E8"))
            }
        }
        val c = controller()
        c.start(reader)
        advanceUntilIdle()
        val failed = (c.state.value as SessionState.Finished).record
        assertEquals(listOf("04", "05", "0B"), requested)
        assertEquals(listOf("04", "05"), failed.readings.filter { it.result is ReadingResult.Value }.map { it.pid })
        assertEquals(SessionOutcome.FAILED, failed.outcome)
        assertFalse(failed.complete)
        c.reset()
        c.start(reader, ReadScope.CODES_ONLY)
        advanceUntilIdle()
        assertEquals(listOf("04", "05", "0B"), requested)
        assertTrue((c.state.value as SessionState.Finished).record.readings.isEmpty())
    }

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
        assertTrue(r.allCodesRead)
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
            override suspend fun readPid(pid: String): List<ReadingResult> {
                requested += pid
                return inner.readPid(pid)
            }
        }
        val c = controller()
        c.start(reader)
        advanceUntilIdle()
        val r = (c.state.value as SessionState.Finished).record
        assertFalse(r.readings.any { it.pid == "0D" })
        assertFalse("0D" in requested)
        assertEquals(listOf("04", "05", "0C"), requested)
    }

    @Test
    fun unknownSupportMapRequestsNothingAndReportsUnknownCapability() = runTest {
        val requested = mutableListOf<String>()
        val reader = object : DiagnosticReader by FakeReader(FakeScenario.NO_CODES, 100) {
            override suspend fun readSupportedPids(): SupportedPids = SupportedPids.Unknown
            override suspend fun readPid(pid: String): List<ReadingResult> {
                requested += pid
                return listOf(ReadingResult.Value(1.0, "x"))
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
    fun cancelBeforeTheSessionCoroutineStartsStillFinishesAsCancelled() = runTest {
        val c = controller()
        assertTrue(c.start(FakeReader(FakeScenario.NO_CODES, 100)))
        assertFalse(c.start(FakeReader(FakeScenario.TWO_CODES, 100)))
        c.cancel() // before the launched coroutine has been dispatched
        advanceUntilIdle()
        val r = (c.state.value as SessionState.Finished).record
        assertEquals(SessionOutcome.CANCELLED, r.outcome)
        assertFalse(r.complete)
        c.reset()
        assertTrue(c.start(FakeReader(FakeScenario.NO_CODES, 100)))
        advanceUntilIdle()
    }

    @Test
    fun selectedScopeIsRecordedBeforeAnyReadAndSurvivesImmediateCancellation() = runTest {
        val c = controller()
        assertTrue(c.start(FakeReader(FakeScenario.NO_CODES, 100), ReadScope.CODES_ONLY))
        val running = (c.state.value as SessionState.Running).record
        assertEquals(listOf("read_scope" to "CODES_ONLY"), running.events.map { it.kind to it.detail })
        c.cancel()
        advanceUntilIdle()
        val cancelled = (c.state.value as SessionState.Finished).record
        assertEquals(SessionOutcome.CANCELLED, cancelled.outcome)
        assertEquals("CODES_ONLY", cancelled.events.first { it.kind == "read_scope" }.detail)
    }

    @Test
    fun fullScopeRecordsItsChoiceBeforeTheFirstSensorRequest() = runTest {
        val c = controller()
        assertTrue(c.start(FakeReader(FakeScenario.NO_CODES, 100), ReadScope.FULL))
        assertEquals("FULL", (c.state.value as SessionState.Running).record.events.first { it.kind == "read_scope" }.detail)
        advanceUntilIdle()
        val finished = (c.state.value as SessionState.Finished).record
        assertEquals(4, finished.readings.size)
        assertEquals("FULL", finished.events.first { it.kind == "read_scope" }.detail)
    }

    @Test
    fun readerErrorFinishesAsFailedWithPartialData() = runTest {
        val reader = object : DiagnosticReader by FakeReader(FakeScenario.TWO_CODES, 100) {
            override suspend fun readDtcs(kind: DtcKind): DtcRead =
                if (kind == DtcKind.PENDING) throw IllegalStateException("boom") else DtcRead.Read(listOf(Dtc("P0133", DtcKind.STORED)))
        }
        val c = controller()
        c.start(reader)
        advanceUntilIdle()
        val r = (c.state.value as SessionState.Finished).record
        assertEquals(SessionOutcome.FAILED, r.outcome)
        assertFalse(r.complete)
        assertEquals(listOf(DtcKind.STORED), r.dtcKindsRead)
        assertFalse(r.allCodesRead)
    }
}
