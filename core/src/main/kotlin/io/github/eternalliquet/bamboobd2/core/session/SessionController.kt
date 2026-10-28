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

enum class Step { SUPPORTED_PIDS, STORED_CODES, PENDING_CODES, READINGS }

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
        _state.value = SessionState.Running(initial, Step.SUPPORTED_PIDS)
        // ATOMIC: even if cancel() runs before the coroutine starts, it still
        // runs far enough to record the cancellation instead of staying Running.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            var record = initial
            try {
                record = record.copy(supportedPids = reader.readSupportedPids())
                emitRunning(record, Step.STORED_CODES)

                record = record.copy(
                    dtcs = record.dtcs + reader.readDtcs(DtcKind.STORED),
                    dtcKindsRead = record.dtcKindsRead + DtcKind.STORED,
                )
                emitRunning(record, Step.PENDING_CODES)

                record = record.copy(
                    dtcs = record.dtcs + reader.readDtcs(DtcKind.PENDING),
                    dtcKindsRead = record.dtcKindsRead + DtcKind.PENDING,
                )
                emitRunning(record, Step.READINGS)

                for (pid in ObdPid.entries) {
                    val result = when (val supported = record.supportedPids) {
                        // Never request a PID the vehicle says it lacks, or one we
                        // could not confirm.
                        is SupportedPids.Known ->
                            if (pid.code in supported.pids) reader.readPid(pid.code) else ReadingResult.Unsupported
                        SupportedPids.Unknown -> ReadingResult.UnknownCapability
                    }
                    record = record.copy(readings = record.readings + Reading(pid.code, result, clock.nowMillis()))
                    emitRunning(record, Step.READINGS)
                }
                finish(record, SessionOutcome.COMPLETE)
            } catch (e: CancellationException) {
                finish(record, SessionOutcome.CANCELLED)
                throw e
            } catch (e: Exception) {
                // Keep partial data and report the stop; no retry.
                finish(record, SessionOutcome.FAILED, e::class.simpleName.orEmpty())
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
