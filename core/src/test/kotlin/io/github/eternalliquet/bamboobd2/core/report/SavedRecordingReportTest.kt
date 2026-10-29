package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.*
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedRecordingReportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun record(id: String, reason: LiveStopReason): SessionRecord {
        val outcome = if (reason == LiveStopReason.USER) SessionOutcome.COMPLETE else SessionOutcome.DISCONNECTED
        return SessionRecord(2, id, 1000, 1700, Provenance.REPLAY, "synthetic", outcome == SessionOutcome.COMPLETE,
            outcome, supportedPids = SupportedPids.Known(listOf("05")),
            dtcKindsRead = listOf(DtcKind.STORED),
            recording = LiveRecording(selectedPids = listOf("05"), writeCount = 3,
                samples = listOf(
                    LiveSample(1, 1100, 100, "05", "7E8", "°C", LiveSampleOutcome.VALUE, 81.0),
                    LiveSample(2, 1100, 150, "05", "7E9", "°C", LiveSampleOutcome.MISSING, reason = "silent"),
                    LiveSample(3, 1300, 300, "05", "7E8", "°C", LiveSampleOutcome.FAILURE, reason = "timeout"),
                ), events = listOf(LiveEvent(350, LiveEventKind.PAUSE), LiveEvent(450, LiveEventKind.RESUME),
                    LiveEvent(600, LiveEventKind.END, reason)), stopReason = reason))
    }

    @Test fun storeReopensNormalAndPartialWithEveryOutcomeAndPausePreserved() {
        val store = FileSessionStore(temporary.newFolder())
        val complete = record("normal", LiveStopReason.USER)
        val interrupted = record("partial", LiveStopReason.DISCONNECTED)
        assertTrue(store.save(complete) is SaveResult.Saved)
        assertTrue(store.save(interrupted) is SaveResult.Saved)
        assertEquals(complete, store.load("normal"))
        assertEquals(interrupted, store.load("partial"))
        assertEquals(2, store.list().filterIsInstance<StoredSession.Readable>().size)
        val report = RecordingReport.series(store.load("partial")!!)
        assertEquals(2, report.size)
        assertEquals(1, report.single { it.key.ecu == "7E8" }.failureCount)
        assertEquals(1, report.single { it.key.ecu == "7E9" }.missingCount)
        assertNull(report.single { it.key.ecu == "7E9" }.minimum)
    }
}
