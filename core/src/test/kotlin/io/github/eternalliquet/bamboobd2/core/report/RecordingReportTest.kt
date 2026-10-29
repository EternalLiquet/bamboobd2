package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RecordingReportTest {
    private fun sample(sequence: Long, ecu: String?, unit: String, outcome: LiveSampleOutcome,
        value: Double? = null, pid: String = "05") = LiveSample(sequence, 1000 + sequence, sequence * 100,
        pid, ecu, unit, outcome, value, "PRIVATE_REASON")
    private fun record(id: String, samples: List<LiveSample>) = SessionRecord(2, id, 1000, 2000,
        Provenance.REPLAY, "PRIVATE_SOURCE", false, SessionOutcome.DISCONNECTED,
        recording = LiveRecording(selectedPids = listOf("05"), samples = samples,
            events = listOf(LiveEvent(300, LiveEventKind.PAUSE), LiveEvent(600, LiveEventKind.END, LiveStopReason.DISCONNECTED)),
            stopReason = LiveStopReason.DISCONNECTED))

    @Test fun seriesKeepRespondersUnitsGapsAndFiniteCoverageSeparate() {
        val rows = RecordingReport.series(record("a", listOf(
            sample(1, "7E8", "°C", LiveSampleOutcome.VALUE, 80.0),
            sample(2, "7E8", "°C", LiveSampleOutcome.MISSING),
            sample(3, "7E8", "°C", LiveSampleOutcome.VALUE, 90.0),
            sample(4, "7E9", "°C", LiveSampleOutcome.FAILURE),
            sample(5, "7E9", "°C", LiveSampleOutcome.VALUE, Double.NaN),
        )))
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].observedCount)
        assertEquals(1, rows[0].missingCount)
        assertEquals(1001L, rows[0].firstAtMillis)
        assertEquals(1003L, rows[0].lastAtMillis)
        assertEquals(80.0, rows[0].minimum)
        assertEquals(90.0, rows[0].maximum)
        assertEquals(2, rows[1].failureCount)
        assertNull(rows[1].minimum)
        assertNull(rows[1].maximum)
    }

    @Test fun compareDoesNotCalculateRecordingDeltasAndExplainsAmbiguity() {
        val first = record("a", listOf(
            sample(1, "7E8", "°C", LiveSampleOutcome.VALUE, 80.0),
            sample(2, "7E9", "°C", LiveSampleOutcome.VALUE, 82.0),
            sample(3, null, "°C", LiveSampleOutcome.VALUE, 83.0),
            sample(4, "10", "°C", LiveSampleOutcome.VALUE, 84.0),
        ))
        val second = record("b", listOf(
            sample(1, "7E8", "°C", LiveSampleOutcome.MISSING),
            sample(2, "7E9", "K", LiveSampleOutcome.VALUE, 355.0),
            sample(3, null, "°C", LiveSampleOutcome.VALUE, 84.0),
        ))
        val result = SessionComparison.compare(first, second) as ComparisonResult.Ready
        assertTrue(result.readings.isEmpty())
        assertFalse(result.kindMismatch)
        assertEquals(RecordingNotComparable.MISSING_SIDE, result.recordingRows.single { it.key.ecu == "10" }.reason)
        assertEquals(RecordingNotComparable.UNIT_MISMATCH, result.recordingRows.single { it.key.ecu == "7E9" && it.key.unit == "K" }.reason)
        assertEquals(RecordingNotComparable.UNKNOWN_RESPONDER, result.recordingRows.single { it.key.ecu == null }.reason)
        val matched = result.recordingRows.single { it.key.ecu == "7E8" }
        assertNull(matched.reason)
        assertEquals(1, matched.first!!.observedCount)
        assertEquals(1, matched.second!!.missingCount)
        assertEquals(80.0, matched.first!!.minimum)
        assertNull(matched.second!!.minimum)
        val mixed = SessionComparison.compare(first, second.copy(id = "snapshot", recording = null)) as ComparisonResult.Ready
        assertTrue(mixed.kindMismatch)
        assertTrue(mixed.readings.isEmpty())
        assertTrue(mixed.recordingRows.isEmpty())
    }

    @Test fun summaryBoundsSeriesAndDoesNotExportRawOrPrivateFields() {
        val samples = (1L..40L).map { sample(it, "%02X".format(it), "°C", LiveSampleOutcome.VALUE, it.toDouble()) }
        val text = SessionSummary.render(record("PRIVATE_ID", samples), "test") { "T$it" }
        assertTrue(text.contains("recording", ignoreCase = true))
        assertTrue(text.contains("DISCONNECTED"))
        assertTrue(text.contains("Omitted 16 series"))
        assertFalse(text.contains("PRIVATE_"))
        assertFalse(text.contains("sample rows"))
    }

    @Test fun missingSideIsNotAUnitMismatchWithinThePresentSide() {
        val present = record("a", listOf(
            sample(1, "7E8", "°C", LiveSampleOutcome.VALUE, 80.0),
            sample(2, "7E8", "K", LiveSampleOutcome.VALUE, 353.0),
        ))
        val absent = record("b", emptyList())
        for ((first, second) in listOf(present to absent, absent to present)) {
            val rows = RecordingReport.compare(first, second)
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.reason == RecordingNotComparable.MISSING_SIDE })
        }
    }

    @Test fun preparationIncompleteMarkersRemainIncompleteInSummary() {
        for (marker in listOf("STORED", "stored")) {
            val incomplete = record("a", emptyList()).copy(events = listOf(
                SessionEvent(1100, "codes_incomplete", "$marker: PRIVATE_REASON"),
            ))
            val text = SessionSummary.render(incomplete, "test") { "T$it" }
            assertTrue(text.contains("Stored codes: Incomplete"))
            assertTrue(text.contains("Pending codes: Not read"))
            assertFalse(text.contains("No codes reported"))
            assertFalse(text.contains("PRIVATE_"))
        }
    }

    @Test fun preparationIncompleteMarkersRemainIncompleteInComparison() {
        for (marker in listOf("STORED", "stored")) {
            val incomplete = record("a", emptyList()).copy(events = listOf(
                SessionEvent(1100, "codes_incomplete", "$marker: PRIVATE_REASON"),
            ))
            val complete = record("b", emptyList()).copy(dtcKindsRead = listOf(DtcKind.STORED))
            val compared = SessionComparison.compare(incomplete, complete) as ComparisonResult.Ready
            val codes = compared.codeKinds.single { it.kind == DtcKind.STORED }
            assertEquals(CodeCoverage.INCOMPLETE, codes.first.coverage)
            assertNull(codes.differences)
        }
    }

    @Test fun recordingSummaryExplainsProvenanceWithoutClaimingSensorCollection() {
        for ((provenance, explanation) in listOf(
            Provenance.REPLAY to "scripted data, not a live vehicle scan",
            Provenance.SIMULATED to "synthetic data, not a vehicle scan",
            Provenance.LIVE to "adapter session",
        )) {
            val text = SessionSummary.render(record("a", emptyList()).copy(provenance = provenance), "test") { "T$it" }
            assertTrue(text.contains("recording", ignoreCase = true))
            assertTrue(text.contains("$provenance — $explanation"))
            assertFalse(text.contains("sensor samples continued afterward"))
            assertTrue(text.contains("No sensor outcomes recorded"))
        }
    }
}
