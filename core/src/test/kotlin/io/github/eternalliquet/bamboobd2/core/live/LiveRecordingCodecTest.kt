package io.github.eternalliquet.bamboobd2.core.live

import io.github.eternalliquet.bamboobd2.core.model.*
import io.github.eternalliquet.bamboobd2.core.storage.DecodeResult
import io.github.eternalliquet.bamboobd2.core.storage.RecordRejectedException
import io.github.eternalliquet.bamboobd2.core.storage.SessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiveRecordingCodecTest {
    private val valid = SessionRecord(2, "live-codec", 100, 200, Provenance.REPLAY, "synthetic",
        true, SessionOutcome.COMPLETE, recording = LiveRecording(selectedPids = listOf("05"),
            writeCount = 1, samples = listOf(LiveSample(1, 100, 30, "05", "7E8", "°C",
                LiveSampleOutcome.VALUE, 20.0)),
            events = listOf(LiveEvent(40, LiveEventKind.END, LiveStopReason.USER)),
            stopReason = LiveStopReason.USER))

    @Test fun rejectsResumeWithoutPauseAndRepeatedPause() {
        for (events in listOf(
            listOf(LiveEvent(10, LiveEventKind.RESUME), LiveEvent(40, LiveEventKind.END, LiveStopReason.USER)),
            listOf(LiveEvent(10, LiveEventKind.PAUSE), LiveEvent(20, LiveEventKind.PAUSE),
                LiveEvent(40, LiveEventKind.END, LiveStopReason.USER)),
        )) {
            val record = valid.copy(recording = valid.recording!!.copy(events = events))
            try {
                SessionCodec.encode(record)
                throw AssertionError("accepted invalid live event transition")
            } catch (expected: RecordRejectedException) {
                assertTrue(expected.message!!.contains("event"))
            }
        }
    }

    @Test fun preservesEqualWallTimestampsAndBackwardClockAcrossRoundTrip() {
        val record = valid.copy(recording = valid.recording!!.copy(samples = listOf(
            valid.recording.samples[0],
            LiveSample(2, 90, 35, "05", "7E9", "°C", LiveSampleOutcome.MISSING, reason = "silent"),
        )))
        assertEquals(record, (SessionCodec.decode(SessionCodec.encode(record)) as DecodeResult.Ok).record)
    }

    @Test fun rejectsCorruptRecordingFieldsOnSaveAndRead() {
        val live = valid.recording!!
        val invalid = listOf(
            valid.copy(recording = live.copy(selectedPids = listOf("05", "05"))),
            valid.copy(recording = live.copy(samples = listOf(live.samples[0].copy(sequence = 2)))),
            valid.copy(recording = live.copy(samples = listOf(live.samples[0].copy(value = Double.POSITIVE_INFINITY)))),
            valid.copy(recording = live.copy(samples = listOf(live.samples[0].copy(outcome = LiveSampleOutcome.MISSING, reason = "silent")))),
            valid.copy(recording = live.copy(samples = listOf(live.samples[0].copy(value = null)))),
            valid.copy(recording = live.copy(writeCount = 1_201)),
            valid.copy(recording = live.copy(retryCount = 4)),
            valid.copy(recording = live.copy(events = listOf(LiveEvent(20, LiveEventKind.END, LiveStopReason.USER)))),
            valid.copy(complete = false),
            valid.copy(schemaVersion = 1),
        )
        for (record in invalid) {
            try { SessionCodec.encode(record); fail("accepted invalid recording: $record") }
            catch (expected: RecordRejectedException) { /* rejected on write */ }
        }
        val encoded = SessionCodec.encode(valid)
        assertTrue(SessionCodec.decode(encoded.replace("\"sequence\": 1", "\"sequence\": 2")) is DecodeResult.Invalid)
        assertTrue(SessionCodec.decode(encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")) is DecodeResult.Invalid)
        assertTrue(SessionCodec.decode(encoded.replace("\"provenance\": \"REPLAY\"", "\"provenance\": \"UNKNOWN\"")) is DecodeResult.Invalid)
    }
}
