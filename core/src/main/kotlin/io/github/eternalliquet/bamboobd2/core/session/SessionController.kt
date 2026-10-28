package io.github.eternalliquet.bamboobd2.core.session

import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionEvent
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.reader.DiagnosticReader
import io.github.eternalliquet.bamboobd2.core.reader.DtcRead
import io.github.eternalliquet.bamboobd2.core.reader.ReaderStoppedException
import io.github.eternalliquet.bamboobd2.core.reader.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Idle : SessionState

    /** A session in progress. [record] holds everything read so far. */
    data class Running(val record: SessionRecord, val step: Step) : SessionState

    /** Finished, either complete or cancelled with partial data. Not yet saved. */
    data class Finished(val record: SessionRecord) : SessionState
}

enum class Step { CONNECTING, SUPPORTED_PIDS, STORED_CODES, PENDING_CODES, READINGS }

/**
 * Runs one read-only diagnostic session at a time and builds its record. The
 * record's provenance always comes from the reader; callers cannot set it.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts a session. Returns false if one is already running. */
    fun start(reader: DiagnosticReader): Boolean {
        if (_state.value is SessionState.Running) return false
        val initial = SessionRecord(
            schemaVersion = SessionRecord.SCHEMA_VERSION,
            id = ids.newId(),
            startedAt = clock.nowMillis(),
            provenance = reader.provenance,
            sourceLabel = reader.sourceLabel,
            complete = false,
        )
        _state.value = SessionState.Running(initial, Step.CONNECTING)
        // ATOMIC: even if cancel() runs before the coroutine starts, it still
        // runs far enough to record the cancellation instead of staying Running.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            var record = initial
            // Reader notes are recorded as they arrive, and on the way out.
            fun withNotes(r: SessionRecord): SessionRecord {
                val notes = reader.takeNotes()
                if (notes.isEmpty()) return r
                val now = clock.nowMillis()
                return r.copy(events = r.events + notes.map { SessionEvent(now, it.kind, it.detail) })
            }
            try {
                reader.open()
                record = withNotes(record)
                emitRunning(record, Step.SUPPORTED_PIDS)

                record = withNotes(record.copy(supportedPids = reader.readSupportedPids()))
                emitRunning(record, Step.STORED_CODES)

                for (kind in listOf(DtcKind.STORED, DtcKind.PENDING)) {
                    record = when (val read = reader.readDtcs(kind)) {
                        is DtcRead.Read -> record.copy(dtcs = record.dtcs + read.codes, dtcKindsRead = record.dtcKindsRead + kind)
                        // Not marked as read, so an empty list is never shown as "no codes".
                        is DtcRead.NotRead -> record.copy(
                            events = record.events + SessionEvent(clock.nowMillis(), "codes_not_read", "${kind.name.lowercase()}: ${read.reason}"),
                        )
                    }
                    record = withNotes(record)
                    emitRunning(record, if (kind == DtcKind.STORED) Step.PENDING_CODES else Step.READINGS)
                }

                for (pid in ObdPid.entries) {
                    val results = when (val supported = record.supportedPids) {
                        // Never request a PID the vehicle says it lacks, or one we
                        // could not confirm.
                        is SupportedPids.Known ->
                            if (pid.code in supported.pids) reader.readPid(pid.code) else listOf(ReadingResult.Unsupported)
                        SupportedPids.Unknown -> listOf(ReadingResult.UnknownCapability)
                    }
                    val at = clock.nowMillis()
                    record = withNotes(record.copy(readings = record.readings + results.map { Reading(pid.code, it, at) }))
                    emitRunning(record, Step.READINGS)
                }
                finish(record, SessionOutcome.COMPLETE)
            } catch (e: CancellationException) {
                finish(withNotes(record), SessionOutcome.CANCELLED)
                throw e
            } catch (e: ReaderStoppedException) {
                // A stop is final: no retry, no reconnect. A new session starts from scratch.
                val outcome = if (e.reason == StopReason.DISCONNECTED) SessionOutcome.DISCONNECTED else SessionOutcome.FAILED
                finish(withNotes(record), outcome, e.message.orEmpty())
            } catch (e: Exception) {
                // Keep partial data and report the stop; no retry.
                finish(withNotes(record), SessionOutcome.FAILED, e::class.simpleName.orEmpty())
            }
        }
        return true
    }

    /** Stops the running session; the data read so far is kept with complete=false. */
    fun cancel() {
        job?.cancel()
    }

    /** Returns to Idle after a finished session has been handled. */
    fun reset() {
        if (_state.value is SessionState.Finished) _state.value = SessionState.Idle
    }

    private fun emitRunning(record: SessionRecord, step: Step) {
        _state.value = SessionState.Running(record, step)
    }

    private fun finish(record: SessionRecord, outcome: SessionOutcome, detail: String = "") {
        val now = clock.nowMillis()
        val finished = record.copy(
            endedAt = now,
            complete = outcome == SessionOutcome.COMPLETE,
            outcome = outcome,
            events = record.events + SessionEvent(now, outcome.name.lowercase(), detail),
        )
        _state.value = SessionState.Finished(finished)
    }
}
