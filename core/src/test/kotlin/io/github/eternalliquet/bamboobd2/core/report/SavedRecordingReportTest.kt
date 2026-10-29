package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.*
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.io.File
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
        val directory = temporary.newFolder()
        val store = FileSessionStore(directory)
        val complete = record("normal", LiveStopReason.USER)
        val interrupted = record("partial", LiveStopReason.DISCONNECTED)
        assertTrue(store.save(complete) is SaveResult.Saved)
        assertTrue(store.save(interrupted) is SaveResult.Saved)
        val savedBytes = directory.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals(complete, store.load("normal"))
        assertEquals(interrupted, store.load("partial"))
        assertEquals(2, store.list().filterIsInstance<StoredSession.Readable>().size)
        val report = RecordingReport.series(store.load("partial")!!)
        assertEquals(2, report.size)
        assertEquals(1, report.single { it.key.ecu == "7E8" }.failureCount)
        assertEquals(1, report.single { it.key.ecu == "7E9" }.missingCount)
        assertNull(report.single { it.key.ecu == "7E9" }.minimum)
        for (record in listOf(complete, interrupted)) SessionSummary.render(store.load(record.id)!!, "test") { "T$it" }
        SessionComparison.compare(store.load("normal")!!, store.load("partial")!!)
        savedBytes.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
    }

    @Test fun legacyAndCurrentSnapshotsRemainImmutableWhenReopenedAndReportedAlongsideRecordings() {
        val directory = temporary.newFolder()
        // Fixed pre-recording shapes, including an older record without code completeness metadata.
        val legacy = """{"schemaVersion":1,"id":"legacy","startedAt":1000,"endedAt":1500,"provenance":"REPLAY","sourceLabel":"synthetic","complete":true,"outcome":"COMPLETE","readings":[{"pid":"05","result":{"type":"value","value":80.0,"unit":"°C","ecu":"7E8"},"atMillis":1200}]}""" + "\n"
        val current = """{"schemaVersion":2,"id":"current","startedAt":2000,"endedAt":2500,"provenance":"REPLAY","sourceLabel":"synthetic","complete":true,"outcome":"COMPLETE","dtcKindsRead":["STORED","PENDING"],"readings":[{"pid":"05","result":{"type":"value","value":85.0,"unit":"°C","ecu":"7E8"},"atMillis":2200}]}""" + "\n"
        File(directory, "legacy.1.json").writeText(legacy)
        File(directory, "current.1.json").writeText(current)
        File(directory, "future.1.json").writeText("""{"schemaVersion":3}""")
        File(directory, "broken.1.json").writeText("{")
        val store = FileSessionStore(directory)
        assertTrue(store.save(record("partial", LiveStopReason.DISCONNECTED)) is SaveResult.Saved)
        val before = directory.listFiles()!!.associate { it.name to it.readBytes() }
        val reopenedLegacy = store.load("legacy")!!
        val reopenedCurrent = store.load("current")!!
        assertNull(reopenedLegacy.recording)
        assertNull(reopenedCurrent.recording)
        assertTrue(SessionSummary.render(reopenedLegacy, "test") { "T$it" }.contains("Stored codes: Not read"))
        assertTrue(SessionSummary.render(reopenedCurrent, "test") { "T$it" }.contains("Stored codes: Read"))
        val snapshots = SessionComparison.compare(reopenedLegacy, reopenedCurrent) as ComparisonResult.Ready
        assertFalse(snapshots.kindMismatch)
        assertEquals(5.0, snapshots.readings.single { it.key.pid == "05" && it.key.ecu == "7E8" }.delta)
        val mixed = SessionComparison.compare(reopenedCurrent, store.load("partial")!!) as ComparisonResult.Ready
        assertTrue(mixed.kindMismatch)
        assertTrue(mixed.readings.isEmpty())
        val listed = store.list()
        assertEquals(setOf("legacy", "current", "partial"), listed.filterIsInstance<StoredSession.Readable>().map { it.record.id }.toSet())
        assertEquals(setOf("future.1.json", "broken.1.json"), listed.filterIsInstance<StoredSession.Unreadable>().map { it.fileName }.toSet())
        assertNull(store.load("future"))
        assertNull(store.load("broken"))
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
    }
}
