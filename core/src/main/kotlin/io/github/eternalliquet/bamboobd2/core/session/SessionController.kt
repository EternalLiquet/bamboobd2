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

/** What a session reads. Live stages widen this one step at a time (docs/LIVE_TEST_GATE.md). */
enum class ReadScope {
    /** Supported-PID map and stored and pending codes only (L2): no sensor readings are requested. */
    CODES_ONLY,

    /** Also the supported live readings (L3, and every offline mode). */
    FULL,
}

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
    fun start(reader: DiagnosticReader, readScope: ReadScope = ReadScope.FULL): Boolean {
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
                        // Codes kept, list not marked read: an incomplete list never counts as complete.
                        is DtcRead.Incomplete -> record.copy(
                            dtcs = record.dtcs + read.codes,
                            events = record.events + SessionEvent(clock.nowMillis(), CODES_INCOMPLETE, "${kind.name.lowercase()}: ${read.reason}"),
                        )
                        // Not marked as read, so an empty list is never shown as "no codes".
                        is DtcRead.NotRead -> record.copy(
                            events = record.events + SessionEvent(clock.nowMillis(), CODES_NOT_READ, "${kind.name.lowercase()}: ${read.reason}"),
                        )
                    }
                    record = withNotes(record)
                    emitRunning(record, if (kind == DtcKind.STORED) Step.PENDING_CODES else Step.READINGS)
                }

                if (readScope == ReadScope.CODES_ONLY) {
                    record = record.copy(
                        events = record.events + SessionEvent(clock.nowMillis(), READINGS_NOT_REQUESTED, "codes-only session"),
                    )
                }
                for (pid in ObdPid.entries.takeIf { readScope == ReadScope.FULL }.orEmpty()) {
                    val results = when (val supported = record.supportedPids) {
                        // Never request a PID the vehicle says it lacks, or one we
                        // could not confirm.
                        is SupportedPids.Known -> when {
                            pid.code in supported.pids -> reader.readPid(pid.code)
                            // Only a complete discovery can say a PID is not supported.
                            supported.complete -> listOf(ReadingResult.Unsupported)
                            else -> listOf(ReadingResult.UnknownCapability)
                        }
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

    companion object {
        /** Event kind recorded when a codes-only session skips the live readings. */
        const val READINGS_NOT_REQUESTED = "readings_not_requested"

        /** Event kinds for a code list that was not read, or read only in part; detail "<kind>: <reason>". */
        const val CODES_NOT_READ = "codes_not_read"
        const val CODES_INCOMPLETE = "codes_incomplete"
    }
}
