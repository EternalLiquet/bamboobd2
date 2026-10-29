package io.github.eternalliquet.bamboobd2.core.live

import io.github.eternalliquet.bamboobd2.core.model.*
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
import io.github.eternalliquet.bamboobd2.core.reader.*
import io.github.eternalliquet.bamboobd2.core.session.Clock
import io.github.eternalliquet.bamboobd2.core.session.IdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Time since an arbitrary origin; only differences are used for budgets and ages. */
fun interface MonotonicClock {
    fun nowMillis(): Long
    companion object {
        private val origin = TimeSource.Monotonic.markNow()
        val System = MonotonicClock { origin.elapsedNow().inWholeMilliseconds }
    }
}

/** Provisional bounded software policy; callers may lower bounds for deterministic tests. */
data class LiveReadPolicy(
    val spacingMillis: Long = 500,
    val durationMillis: Long = 600_000,
    val maxWrites: Int = 1_200,
    val maxRetries: Int = 3,
    val maxSamples: Int = 10_000,
) {
    init {
        require(spacingMillis == 500L && durationMillis in 1..600_000 &&
            maxWrites in 1..1_200 && maxRetries in 0..3 && maxSamples in 1..10_000)
    }
}

sealed interface LiveReadState {
    data object Idle : LiveReadState
    data class Preparing(val record: SessionRecord) : LiveReadState
    data class Ready(val record: SessionRecord) : LiveReadState
    data class Recording(val record: SessionRecord) : LiveReadState
    data class Pausing(val record: SessionRecord) : LiveReadState
    data class Paused(val record: SessionRecord) : LiveReadState
    data class Finished(val record: SessionRecord) : LiveReadState
}

/** One prepared connection, one serial polling job, and one global write gate. */
class LiveReadController(
    private val scope: CoroutineScope,
    private val wallClock: Clock = Clock.System,
    private val monotonicClock: MonotonicClock = MonotonicClock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val policy: LiveReadPolicy = LiveReadPolicy(),
) {
    private val _state = MutableStateFlow<LiveReadState>(LiveReadState.Idle)
    val state: StateFlow<LiveReadState> = _state.asStateFlow()
    private lateinit var record: SessionRecord
    private var reader: DiagnosticReader? = null
    private var startedMono = 0L
    private var lastWrite: Long? = null
    private var previousWrite: Long? = null
    private var job: Job? = null
    private var deadlineJob: Job? = null
    private var pauseRequested = false
    private var resumeSignal: CompletableDeferred<Unit>? = null

    private class BoundReached(val reason: LiveStopReason) : RuntimeException()
    private fun elapsed() = (monotonicClock.nowMillis() - startedMono).coerceAtLeast(0)
    private fun remaining() = policy.durationMillis - elapsed()

    /** The factory must pass this gate to its ElmObdReader; it is also usable by scripted readers. */
    fun prepare(readerFactory: (RequestGate) -> DiagnosticReader): Boolean {
        if (_state.value !is LiveReadState.Idle && _state.value !is LiveReadState.Finished) return false
        job?.cancel(); deadlineJob?.cancel()
        startedMono = monotonicClock.nowMillis()
        lastWrite = null
        previousWrite = null
        pauseRequested = false
        resumeSignal = null
        val initialWall = wallClock.nowMillis()
        val gate = object : RequestGate {
            override suspend fun beforeWrite(retry: Boolean) = this@LiveReadController.beforeWrite(retry)
            override fun wrote(retry: Boolean) = this@LiveReadController.wrote(retry)
            override fun rejectedWrite() = this@LiveReadController.rejectedWrite()
        }
        val preparedReader = readerFactory(gate)
        reader = preparedReader
        record = SessionRecord(SessionRecord.SCHEMA_VERSION, ids.newId(), initialWall,
            provenance = preparedReader.provenance, sourceLabel = preparedReader.sourceLabel,
            complete = false, recording = LiveRecording())
        _state.value = LiveReadState.Preparing(record)
        deadlineJob = scope.launch {
            delay(policy.durationMillis)
            if (_state.value !is LiveReadState.Finished) stop(LiveStopReason.DURATION_LIMIT)
        }
        job = scope.launch {
            try {
                bounded { preparedReader.open() }
                notes(preparedReader)
                val support = bounded { preparedReader.readSupportedPids() }
                update { it.copy(supportedPids = support) }
                notes(preparedReader)
                for (kind in listOf(DtcKind.STORED, DtcKind.PENDING)) {
                    when (val result = bounded { preparedReader.readDtcs(kind) }) {
                        is DtcRead.Read -> update { it.copy(dtcs = it.dtcs + result.codes, dtcKindsRead = it.dtcKindsRead + kind) }
                        is DtcRead.Incomplete -> update { it.copy(dtcs = it.dtcs + result.codes, events = it.events + SessionEvent(wallClock.nowMillis(), "codes_incomplete", "${kind.name}: ${result.reason}")) }
                        is DtcRead.NotRead -> update { it.copy(events = it.events + SessionEvent(wallClock.nowMillis(), "codes_not_read", "${kind.name}: ${result.reason}")) }
                    }
                    notes(preparedReader)
                }
                if (_state.value !is LiveReadState.Finished) _state.value = LiveReadState.Ready(record)
            } catch (e: CancellationException) {
                if (_state.value !is LiveReadState.Finished) finish(LiveStopReason.CANCELLED)
            } catch (e: BoundReached) {
                finish(e.reason)
            } catch (e: ReaderStoppedException) {
                finish(if (e.reason == StopReason.DISCONNECTED) LiveStopReason.DISCONNECTED else LiveStopReason.FAILED)
            } catch (e: Exception) {
                finish(LiveStopReason.FAILED)
            }
        }
        return true
    }

    /** Reject unsupported, unknown, duplicate and arbitrary PIDs before any sensor request. */
    fun start(selectedPids: List<String>): Boolean {
        if (_state.value !is LiveReadState.Ready || selectedPids.size !in 1..4 ||
            selectedPids.distinct().size != selectedPids.size ||
            selectedPids.any { Mode01Catalog.lookup(it) == null ||
                record.supportedPids.capability(it).capability != Capability.SUPPORTED_AND_DECODABLE }) return false
        if (remaining() <= 0) { stop(LiveStopReason.DURATION_LIMIT); return false }
        update { it.copy(recording = it.recording!!.copy(selectedPids = selectedPids.toList())) }
        _state.value = LiveReadState.Recording(record)
        val selected = selectedPids.toList()
        job = scope.launch {
            try {
                var index = 0
                while (true) {
                    while (pauseRequested) {
                        _state.value = LiveReadState.Paused(record)
                        resumeSignal?.await()
                    }
                    if (remaining() <= 0) throw BoundReached(LiveStopReason.DURATION_LIMIT)
                    val pid = selected[index++ % selected.size]
                    val results = bounded { checkNotNull(reader).readPidObservations(pid) }
                    notes(checkNotNull(reader))
                    append(pid, results)
                    if (record.recording!!.samples.size >= policy.maxSamples) throw BoundReached(LiveStopReason.SAMPLE_LIMIT)
                    if (pauseRequested) _state.value = LiveReadState.Paused(record)
                    else _state.value = LiveReadState.Recording(record)
                    // A non-ELM fake still yields cooperatively; real spacing is enforced at the write seam.
                    delay(1)
                }
            } catch (e: CancellationException) {
                if (_state.value !is LiveReadState.Finished) finish(LiveStopReason.CANCELLED)
            } catch (e: BoundReached) {
                finish(e.reason)
            } catch (e: ReaderStoppedException) {
                finish(if (e.reason == StopReason.DISCONNECTED) LiveStopReason.DISCONNECTED else LiveStopReason.FAILED)
            } catch (e: Exception) {
                finish(LiveStopReason.FAILED)
            }
        }
        return true
    }

    fun pause(): Boolean {
        if (_state.value !is LiveReadState.Recording) return false
        pauseRequested = true
        resumeSignal = CompletableDeferred()
        addEvent(LiveEventKind.PAUSE)
        _state.value = LiveReadState.Pausing(record)
        return true
    }

    fun resume(): Boolean {
        if (_state.value !is LiveReadState.Paused) return false
        pauseRequested = false
        addEvent(LiveEventKind.RESUME)
        _state.value = LiveReadState.Recording(record)
        resumeSignal?.complete(Unit)
        return true
    }

    /** Synchronous terminal state even when called before a launched coroutine has run. */
    fun stop(reason: LiveStopReason = LiveStopReason.USER) {
        if (_state.value is LiveReadState.Idle || _state.value is LiveReadState.Finished) return
        finish(reason)
        job?.cancel()
        deadlineJob?.cancel()
        resumeSignal?.complete(Unit)
    }

    private suspend fun beforeWrite(retry: Boolean) {
        while (true) {
            if (_state.value is LiveReadState.Finished) throw CancellationException("live session stopped")
            if (remaining() <= 0) throw BoundReached(LiveStopReason.DURATION_LIMIT)
            if (pauseRequested) {
                _state.value = LiveReadState.Paused(record)
                resumeSignal?.await()
                continue
            }
            val live = record.recording!!
            if (live.writeCount >= policy.maxWrites) throw BoundReached(LiveStopReason.WRITE_LIMIT)
            if (retry && live.retryCount >= policy.maxRetries) throw BoundReached(LiveStopReason.RETRY_LIMIT)
            val wait = lastWrite?.let { policy.spacingMillis - (monotonicClock.nowMillis() - it) } ?: 0
            if (wait > 0) {
                delay(minOf(wait, remaining()))
                continue
            }
            previousWrite = lastWrite
            lastWrite = monotonicClock.nowMillis()
            return
        }
    }

    private fun wrote(retry: Boolean) {
        previousWrite = null
        update { it.copy(recording = it.recording!!.copy(
            writeCount = it.recording!!.writeCount + 1,
            retryCount = it.recording!!.retryCount + if (retry) 1 else 0)) }
    }

    private fun rejectedWrite() {
        lastWrite = previousWrite
        previousWrite = null
    }

    private suspend fun <T> bounded(block: suspend () -> T): T {
        val time = remaining()
        if (time <= 0) throw BoundReached(LiveStopReason.DURATION_LIMIT)
        return withTimeoutOrNull(time) { block() } ?: throw BoundReached(LiveStopReason.DURATION_LIMIT)
    }

    private fun append(pid: String, results: List<ReadingResult>) {
        val live = record.recording!!
        val expected = (record.supportedPids as? SupportedPids.Known)?.pages.orEmpty()
            .map { it.ecu }.distinct().filter { record.supportedPids.capability(pid, it).capability == Capability.SUPPORTED_AND_DECODABLE }
        val unit = checkNotNull(Mode01Catalog.lookup(pid)).unit
        val at = wallClock.nowMillis()
        val age = elapsed().coerceAtMost(policy.durationMillis)
        val rows = mutableListOf<LiveSample>()
        fun add(ecu: String?, outcome: LiveSampleOutcome, value: Double? = null, reason: String? = null) {
            rows += LiveSample((live.samples.size + rows.size + 1).toLong(), at, age, pid, ecu, unit, outcome, value, reason)
        }
        val seen = mutableSetOf<String?>()
        for (result in results) when (result) {
            is ReadingResult.Value -> {
                seen += result.ecu
                if (result.value.isFinite() && result.unit == unit) add(result.ecu, LiveSampleOutcome.VALUE, result.value)
                else add(result.ecu, LiveSampleOutcome.FAILURE, reason = "invalid value or unit")
            }
            is ReadingResult.Failed -> { seen += result.ecu; add(result.ecu, LiveSampleOutcome.FAILURE, reason = result.reason) }
            ReadingResult.NoData -> if (expected.isEmpty()) add(null, LiveSampleOutcome.MISSING, reason = "NO DATA")
            else -> add(null, LiveSampleOutcome.FAILURE, reason = "unexpected reading result")
        }
        for (ecu in expected) if (ecu !in seen) add(ecu, LiveSampleOutcome.MISSING, reason = "responder did not return a value")
        if (rows.isEmpty()) add(null, LiveSampleOutcome.MISSING, reason = "no responder")
        val room = policy.maxSamples - live.samples.size
        update { it.copy(recording = live.copy(samples = live.samples + rows.take(room))) }
        if (rows.size > room) throw BoundReached(LiveStopReason.SAMPLE_LIMIT)
    }

    private fun notes(reader: DiagnosticReader) {
        val notes = reader.takeNotes()
        if (notes.isNotEmpty()) update { it.copy(events = it.events + notes.map { note -> SessionEvent(wallClock.nowMillis(), note.kind, note.detail) }) }
    }

    private fun addEvent(kind: LiveEventKind, reason: LiveStopReason? = null) {
        update { it.copy(recording = it.recording!!.copy(events = it.recording!!.events + LiveEvent(elapsed().coerceAtMost(policy.durationMillis), kind, reason))) }
    }

    private fun update(change: (SessionRecord) -> SessionRecord) {
        if (_state.value is LiveReadState.Finished) return
        record = change(record)
        _state.value = when (_state.value) {
            is LiveReadState.Preparing -> LiveReadState.Preparing(record)
            is LiveReadState.Ready -> LiveReadState.Ready(record)
            is LiveReadState.Pausing -> LiveReadState.Pausing(record)
            is LiveReadState.Paused -> LiveReadState.Paused(record)
            else -> LiveReadState.Recording(record)
        }
    }

    private fun finish(reason: LiveStopReason) {
        if (_state.value is LiveReadState.Finished) return
        val outcome = when (reason) {
            LiveStopReason.CANCELLED -> SessionOutcome.CANCELLED
            LiveStopReason.DISCONNECTED -> SessionOutcome.DISCONNECTED
            LiveStopReason.FAILED -> SessionOutcome.FAILED
            else -> SessionOutcome.COMPLETE
        }
        val live = record.recording!!
        record = record.copy(endedAt = wallClock.nowMillis(), complete = outcome == SessionOutcome.COMPLETE,
            outcome = outcome, recording = live.copy(stopReason = reason,
                events = live.events + LiveEvent(elapsed().coerceAtMost(policy.durationMillis), LiveEventKind.END, reason)))
        _state.value = LiveReadState.Finished(record)
        deadlineJob?.cancel()
    }
}
