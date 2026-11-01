package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import android.os.Build
import android.os.SystemClock
import io.github.eternalliquet.bamboobd2.app.bluetooth.AndroidBluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BenchRun
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothBench
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.core.bench.AdapterBenchCheck
import io.github.eternalliquet.bamboobd2.core.bench.BenchReport
import io.github.eternalliquet.bamboobd2.core.bench.BenchResult
import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.session.ReadScope
import io.github.eternalliquet.bamboobd2.core.live.LiveReadController
import io.github.eternalliquet.bamboobd2.core.live.LiveReadState
import io.github.eternalliquet.bamboobd2.core.model.LiveStopReason
import io.github.eternalliquet.bamboobd2.app.bluetooth.ClassicConnection
import io.github.eternalliquet.bamboobd2.app.bluetooth.connectClassic
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.app.connection.SavedAdapter
import io.github.eternalliquet.bamboobd2.app.connection.SavedAdapterStore
import io.github.eternalliquet.bamboobd2.app.connection.VehicleConnection
import io.github.eternalliquet.bamboobd2.app.connection.VehicleStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.CapabilityStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayTransport
import io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.DiagnosticLog
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import io.github.eternalliquet.bamboobd2.core.storage.SessionStore
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.io.File
import io.github.eternalliquet.bamboobd2.core.experiment.IdentificationExperiment
import io.github.eternalliquet.bamboobd2.core.experiment.ExperimentResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/** Save progress for one session, keyed by session id in [AppViewModel.saveStatus]. */
sealed interface SaveStatus {
    data object Saving : SaveStatus
    data class Done(val result: SaveResult) : SaveStatus
}

/** The adapter-only bench check, as the bench screen shows it. */
sealed interface BenchState {
    /** Idle: whether Bluetooth can be used, and whether the user just denied the permission. */
    data class Available(val availability: BluetoothAvailability, val permissionDenied: Boolean = false) : BenchState
    data class Connecting(val device: String) : BenchState
    data class Running(val device: String) : BenchState
    data class Done(val device: String, val result: BenchResult) : BenchState
    data class Failed(val device: String, val reason: String) : BenchState
}

/** A scan or recording on the shared vehicle connection, before its own screen takes over. */
sealed interface LiveState {
    data object Idle : LiveState
    data class Failed(val device: String, val reason: String) : LiveState

    /**
     * A live session started and its screen hasn't been shown yet. It stays set,
     * even after the session finishes, until the live-read screen navigates to the
     * session and calls [AppViewModel.acknowledgeLiveStarted]. So a read that ends
     * while the app is in the background is still reachable on return.
     */
    data object Started : LiveState
}

data class ExperimentUiState(
    val busy: Boolean = false,
    val phase: String = "Choose your paired scanner",
    val result: ExperimentResult? = null,
    val saved: Boolean = false,
    val saveError: String? = null,
)

/** Wiring only: connects the core controller, store, bench check and live read to the screens. */
class AppViewModel @JvmOverloads constructor(
    application: Application,
    private val store: SessionStore = FileSessionStore(File(application.filesDir, "sessions")),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val bluetooth: BluetoothPlatform = BluetoothPlatforms.create(application),
) : AndroidViewModel(application) {

    /**
     * The one connection to the saved scanner and its per-connection vehicle context.
     * Scans and recording borrow its transport; the bench check and experiment close it
     * before opening their own. See docs/VEHICLE_STARTUP.md.
     */
    /**
     * Persistent, redacted log of every Bluetooth connection (vehicle, bench, experiment).
     * It only queues work, so it adds no request or delay. See docs/DIAGNOSTIC_LOG.md.
     */
    val diagnostics = DiagnosticLog(File(application.filesDir, "diagnostics"), buildInfo(application))

    val vehicle = VehicleConnection(viewModelScope, bluetooth, io, SavedAdapterStore(application),
        powerCycleRequired = { experimentReuseRequired.value },
        otherToolActive = { _experiment.value.busy || benchJob?.isActive == true },
        diagnostics = diagnostics)
    val connection: StateFlow<ConnectionState> get() = vehicle.state

    /** For the scanner setup screen; reads state only and never connects. */
    fun bluetoothAvailability(): BluetoothAvailability = bluetooth.availability()

    /** Activity start: the one startup attempt the first time, then only a state refresh. */
    fun onAppForegrounded() {
        vehicle.startup()
        vehicle.onForegrounded()
    }

    private fun adapterName() = vehicle.savedAdapter?.name ?: "scanner"

    // Plain user requests from the home and setup screens.
    fun chooseScanner(device: BondedDevice) = vehicle.choose(device)
    fun connectVehicle() = vehicle.connect()
    fun disconnectVehicle() = vehicle.disconnect()
    fun forgetScanner() = vehicle.forget()
    fun identifyAgain() = vehicle.identifyAgain()
    fun confirmProfile(profileId: String?) = vehicle.confirmProfile(profileId)

    /** After the permission prompt: a grant continues the same connection flow. */
    fun onConnectionPermissionResult(granted: Boolean) {
        if (granted) vehicle.connect() else vehicle.refresh(permissionDenied = true)
    }

    /** Profiles the user may confirm for the current connection's evidence. */
    fun selectableProfiles() = ((connection.value as? ConnectionState.Ready)?.vehicle as? VehicleStatus.Known)
        ?.context?.selectableProfiles(ManufacturerRegistry.BUNDLED).orEmpty()

    private val controller = SessionController(viewModelScope)

    val session: StateFlow<SessionState> = controller.state

    private val _saved = MutableStateFlow<List<StoredSession>>(emptyList())
    val saved: StateFlow<List<StoredSession>> = _saved.asStateFlow()

    // Keyed by session id, so a save that finishes after the user has moved on
    // updates only its own session, never the one on screen.
    private val _saveStatus = MutableStateFlow<Map<String, SaveStatus>>(emptyMap())
    val saveStatus: StateFlow<Map<String, SaveStatus>> = _saveStatus.asStateFlow()

    /** The synthetic replay scripts bundled with `:core`. */
    val replays: List<ReplayScript> by lazy { ReplayTransport.bundledIds.map(ReplayTransport::bundled) }

    /** Starts a simulated session. False (nothing started) while a live result waits to be opened. */
    fun start(scenario: FakeScenario): Boolean =
        !liveResultPending() && !recordingPending.value && controller.start(FakeReader(scenario))

    /** Runs a bundled script through the real protocol path; the session is REPLAY, never live. */
    fun startReplay(id: String): Boolean {
        if (liveResultPending() || recordingPending.value) return false
        val script = replays.first { it.id == id }
        return controller.start(ElmObdReader(ReplayTransport(script, UptimeTimeSource), "replay:$id"))
    }

    // An unacknowledged live session may be unsaved: no other session may replace it.
    private fun liveResultPending() = _live.value == LiveState.Started

    // The controller and its operations stay on the ViewModel main dispatcher; transport I/O
    // is asynchronous and never grants another command stream for this connection.
    private val liveController = LiveReadController(viewModelScope)
    val recording: StateFlow<LiveReadState> = liveController.state
    private val _recordingPending = MutableStateFlow(false)
    val recordingPending: StateFlow<Boolean> = _recordingPending.asStateFlow()
    private var recordingJob: Job? = null
    private var recordingStartedAt = 0L
    fun recordingElapsedMillis(): Long = (SystemClock.elapsedRealtime() - recordingStartedAt).coerceIn(0L, 600_000L)

    /** Prepares a recording on the shared connection; nothing is read until the user picks readings. */
    fun startRecording(): Boolean {
        if (experimentReuseRequired.value) {
            _live.value = LiveState.Failed(adapterName(), EXPERIMENT_REUSE_MESSAGE)
            return false
        }
        if (_experiment.value.busy || recordingJob?.isActive == true || liveJob?.isActive == true || benchJob?.isActive == true ||
            session.value is SessionState.Running || liveResultPending() || _recordingPending.value) return false
        val lease = vehicle.acquire() ?: run {
            _live.value = LiveState.Failed(adapterName(), NOT_CONNECTED_MESSAGE)
            return false
        }
        val transport = lease.transport
        recordingStartedAt = SystemClock.elapsedRealtime()
        val prepared = liveController.prepare { gate -> ElmObdReader(transport, "bluetooth:classic", requestGate = gate) }
        if (!prepared) {
            vehicle.release(lease)
            _live.value = LiveState.Failed(adapterName(), "another recording is running")
            return false
        }
        _recordingPending.value = true
        _live.value = LiveState.Idle
        lease.log?.operationStart("recording")
        recordingJob = viewModelScope.launch {
            try {
                // Ready and Paused do not read; an EOF must still end this recording.
                val disconnectWatch = launch {
                    transport.awaitDisconnect()
                    liveController.stop(LiveStopReason.DISCONNECTED)
                }
                try {
                    liveController.state.first { it is LiveReadState.Finished }
                } finally { disconnectWatch.cancel() }
            } finally {
                (liveController.state.value as? LiveReadState.Finished)?.record?.let { record ->
                    logRecord(lease, "recording", record.recording?.stopReason?.name ?: record.outcome?.name ?: "UNKNOWN", record)
                } ?: lease.log?.operationEnd("recording", "CANCELLED")
                // USER and duration-limit finishes can interrupt a reply even when
                // the record's outcome is COMPLETE. Never reuse that byte stream.
                vehicle.release(lease, reusable = false)
            }
        }
        return true
    }

    fun startSelectedRecording(pids: List<String>) = liveController.start(pids)
    fun pauseRecording() = liveController.pause()
    fun resumeRecording() = liveController.resume()
    fun stopRecording(reason: LiveStopReason = LiveStopReason.USER) {
        liveController.stop(reason)
        // Cancellation also releases a blocked transport read promptly. The core already
        // published its terminal partial record synchronously before this job is cancelled.
        recordingJob?.cancel()
    }
    fun onAppBackgrounded() {
        stopExperiment()
        if (recording.value !is LiveReadState.Idle && recording.value !is LiveReadState.Finished)
            stopRecording(LiveStopReason.CANCELLED)
        // Codes/snapshot reads may finish while stopped; their Started hand-off remains
        // pending for the screen on resume. The vehicle connection closes when idle.
        vehicle.onBackgrounded()
    }
    fun finishRecording() {
        if (recording.value !is LiveReadState.Finished) return
        _recordingPending.value = false
        _saveStatus.update { statuses -> statuses.filterValues { it == SaveStatus.Saving } }
    }
    fun discardPendingRecording() {
        stopRecording(LiveStopReason.CANCELLED)
        _recordingPending.value = false
    }
    fun saveRecording() {
        val record = (recording.value as? LiveReadState.Finished)?.record ?: return
        val id = record.id
        val current = _saveStatus.value[id]
        if (current == SaveStatus.Saving || (current as? SaveStatus.Done)?.result is SaveResult.Saved) return
        _saveStatus.update { it + (id to SaveStatus.Saving) }
        viewModelScope.launch {
            val result = try { withContext(io) { store.save(record) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { SaveResult.Failed(e.message ?: e::class.simpleName.orEmpty()) }
            _saveStatus.update { it + (id to SaveStatus.Done(result)) }
            refreshSaved()
        }
    }

    fun cancel() = controller.cancel()

    fun save() {
        val record = (session.value as? SessionState.Finished)?.record ?: return
        val id = record.id
        // One save at a time per session, and none after it has been saved.
        val current = _saveStatus.value[id]
        if (current == SaveStatus.Saving || (current as? SaveStatus.Done)?.result is SaveResult.Saved) return
        _saveStatus.update { it + (id to SaveStatus.Saving) }
        viewModelScope.launch {
            val result = try {
                withContext(io) { store.save(record) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SaveResult.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            _saveStatus.update { it + (id to SaveStatus.Done(result)) }
            refreshSaved()
        }
    }

    fun finishSession() {
        controller.reset()
        // Forget finished results; an in-flight save keeps its entry until it completes.
        _saveStatus.update { statuses -> statuses.filterValues { it == SaveStatus.Saving } }
    }

    fun refreshSaved() {
        viewModelScope.launch { _saved.value = withContext(io) { store.list() } }
    }

    suspend fun load(id: String): SessionRecord? = withContext(io) { store.load(id) }

    // Bench check (L1 path). Only BluetoothBench touches the Bluetooth connection.

    /** "Android 15 (API 35)": the OS version for the test handoff. No device identifiers. */
    val platform: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    val bluetoothPermission: String? get() = bluetooth.connectPermission

    private val _bench = MutableStateFlow<BenchState>(BenchState.Available(BluetoothAvailability.PermissionNeeded))
    val bench: StateFlow<BenchState> = _bench.asStateFlow()
    private var benchJob: Job? = null

    /** Rereads Bluetooth state; never while a check is connecting or running. */
    fun refreshBluetooth(permissionDenied: Boolean = false) {
        if (_bench.value is BenchState.Connecting || _bench.value is BenchState.Running) return
        _bench.value = BenchState.Available(bluetooth.availability(), permissionDenied)
    }

    /** On opening the bench screen: rereads state only when idle, so a result or failure stays until the user moves on. */
    fun refreshBluetoothIfIdle() {
        if (_bench.value is BenchState.Available) refreshBluetooth()
    }

    fun onPermissionResult(granted: Boolean) = refreshBluetooth(permissionDenied = !granted)

    fun startBench(device: BondedDevice) {
        if (experimentReuseRequired.value) {
            _bench.value = BenchState.Failed(device.name, EXPERIMENT_REUSE_MESSAGE)
            return
        }
        // One Bluetooth use at a time: never alongside a live read.
        if (_experiment.value.busy || benchJob?.isActive == true || liveJob?.isActive == true || recordingJob?.isActive == true || _recordingPending.value) return
        // The vehicle connection closes first; a scan holding it blocks the check.
        if (!vehicle.releaseForOtherTool()) return
        _bench.value = BenchState.Connecting(device.name)
        benchJob = viewModelScope.launch {
            var check: AdapterBenchCheck? = null
            try {
                _bench.value = when (val run = BluetoothBench(bluetooth, io, diagnostics).run(device) { check = it; _bench.value = BenchState.Running(device.name) }) {
                    is BenchRun.ConnectFailed -> BenchState.Failed(device.name, run.reason)
                    is BenchRun.Finished -> BenchState.Done(device.name, run.result)
                }
            } catch (e: CancellationException) {
                // Keep whatever the check had read; nothing reconnects.
                _bench.value = check?.let { BenchState.Done(device.name, it.partial()) }
                    ?: BenchState.Failed(device.name, "cancelled before a connection was made")
                throw e
            }
        }
    }

    fun cancelBench() {
        benchJob?.cancel()
    }

    fun benchReport(result: BenchResult): String {
        return BenchReport.text(result, appVersion(), platform)
    }

    // One selected read scope over Bluetooth Classic; no automatic reconnect.

    private val _live = MutableStateFlow<LiveState>(LiveState.Idle)
    val live: StateFlow<LiveState> = _live.asStateFlow()
    private var liveJob: Job? = null

    // Raw captures by session id, in memory only. Copied out by the owner for review.
    private val _captures = MutableStateFlow<Map<String, String>>(emptyMap())
    val captures: StateFlow<Map<String, String>> = _captures.asStateFlow()

    /** Clears a shown failure; never drops an unacknowledged result hand-off. */
    fun refreshLive() {
        if (_live.value is LiveState.Failed) _live.value = LiveState.Idle
    }

    /**
     * Explicitly drops a pending live result: a read still running is cancelled, then
     * the session is cleared. The vehicle connection stays as it is.
     */
    fun discardPendingLive() {
        if (!liveResultPending()) return
        _live.value = LiveState.Idle
        controller.cancel()
        viewModelScope.launch {
            controller.state.first { it !is SessionState.Running }
            finishSession()
        }
    }

    /** The live-read screen has navigated to the started session: the hand-off is consumed. */
    fun acknowledgeLiveStarted() {
        if (_live.value == LiveState.Started) _live.value = LiveState.Idle
    }

    /**
     * One codes or codes+snapshot session on the shared connection. Its raw capture
     * starts with the session's own setup commands; identification is not included.
     * False when nothing started.
     */
    fun startLive(readScope: ReadScope = ReadScope.CODES_ONLY): Boolean {
        if (experimentReuseRequired.value) {
            _live.value = LiveState.Failed(adapterName(), EXPERIMENT_REUSE_MESSAGE)
            return false
        }
        // Also never while a finished result still waits to be shown: it may be unsaved.
        if (_experiment.value.busy || liveJob?.isActive == true || recordingJob?.isActive == true || _recordingPending.value || benchJob?.isActive == true || session.value is SessionState.Running ||
            _live.value == LiveState.Started
        ) return false
        val lease = vehicle.acquire() ?: run {
            _live.value = LiveState.Failed(adapterName(), NOT_CONNECTED_MESSAGE)
            return false
        }
        // This immutable value belongs to this session, even if UI state changes.
        val selectedScope = readScope
        val recorder = CaptureRecorder()
        lease.transport.recorder = recorder
        // The controller gates any sensor request on the supported map; provenance
        // comes from the LIVE transport, not from the selected scope.
        lease.log?.operationStart("scan", selectedScope.name)
        if (!controller.start(ElmObdReader(lease.transport, "bluetooth:classic"), selectedScope)) {
            lease.log?.operationEnd("scan", "NOT_STARTED", "another session is running")
            vehicle.release(lease)
            _live.value = LiveState.Failed(adapterName(), "another session is running")
            return false
        }
        _live.value = LiveState.Started
        liveJob = viewModelScope.launch {
            var completed = false
            var logged = false
            try {
                val finished = controller.state.first { it is SessionState.Finished } as SessionState.Finished
                completed = finished.record.outcome == SessionOutcome.COMPLETE
                logRecord(lease, "scan", finished.record.outcome?.name ?: "UNKNOWN", finished.record)
                logged = true
                val id = finished.record.id
                val scopeLabel = when (selectedScope) {
                    ReadScope.CODES_ONLY -> "codes-only (L2) session"
                    ReadScope.FULL -> "codes + supported sensor snapshot (L3 software; not vehicle-tested) session"
                }
                val source = "BambooBD2 ${appVersion()}, Bluetooth Classic, $scopeLabel, outcome " +
                    "${finished.record.outcome}. Unreviewed raw capture: see docs/CAPTURE_IMPORT.md."
                _captures.update { it + (id to recorder.json(id, source)) }
            } finally {
                if (!logged) lease.log?.operationEnd("scan", "CANCELLED")
                // A cancelled/failed reader may still be awaiting a terminal prompt.
                // Do not give that socket to a new reader or its capture recorder.
                vehicle.release(lease, reusable = completed)
            }
        }
        return true
    }

    /** On leaving the live-read screen: stops a session that started with no screen showing it. */
    fun cancelLiveConnect() {
        if (_live.value == LiveState.Started) controller.cancel()
    }

    private val _experiment = MutableStateFlow(ExperimentUiState())
    val experiment: StateFlow<ExperimentUiState> = _experiment.asStateFlow()
    private val experimentPreferences = application.getSharedPreferences("identification-safety", 0)
    private val _experimentReuseRequired = MutableStateFlow(experimentPreferences.getBoolean("power-cycle-required", false))
    val experimentReuseRequired: StateFlow<Boolean> = _experimentReuseRequired.asStateFlow()
    private var pendingExperimentExport: String? = null
    private var experimentId = "not-started"
    private var experimentStartedUtc = ""
    private var experimentJob: Job? = null
    private var experimentTransport: io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothClassicTransport? = null

    /**
     * The saved scanner, when this connection's identity offers the research experiment
     * (a Porsche whose owner confirmed the 92A Cayenne). Null otherwise: a remembered
     * adapter or an earlier car never enables it.
     */
    fun experimentAdapter(): SavedAdapter? {
        val ready = connection.value as? ConnectionState.Ready ?: return null
        val context = (ready.vehicle as? VehicleStatus.Known)?.context ?: return null
        return ready.adapter.takeIf {
            context.capabilities.any { it.definition.id == EXPERIMENT_CAPABILITY && it.status == CapabilityStatus.RESEARCH_ONLY }
        }
    }

    /**
     * All start gates run on the main dispatcher; busy stays set until the socket is closed.
     * The experiment closes the shared connection and opens its own, unchanged from #64.
     */
    fun startExperiment(reviewed: Boolean): Boolean {
        val adapter = experimentAdapter() ?: return false
        val device = adapter.device()
        if (!reviewed || experimentReuseRequired.value || _experiment.value.busy || listOf(benchJob, liveJob, recordingJob).any { it != null && !it.isCompleted } ||
            session.value is SessionState.Running || liveResultPending() || _recordingPending.value) return false
        if (!vehicle.releaseForOtherTool()) return false
        // Persist before opening the socket. A killed process cannot accidentally reuse experimental adapter settings.
        if (!experimentPreferences.edit().putBoolean("power-cycle-required", true).commit()) {
            _experiment.value = ExperimentUiState(phase = "Could not save the adapter reuse guard. No connection attempted.")
            return false
        }
        _experimentReuseRequired.value = true
        experimentId = java.util.UUID.randomUUID().toString()
        experimentStartedUtc = java.time.Instant.now().toString()
        _experiment.value = ExperimentUiState(busy = true, phase = "Connecting to scanner…")
        val log = diagnostics.startSession("experiment", device.name)
        log.app("connect_start")
        experimentJob = viewModelScope.launch {
            try {
                withTimeout(60_000L) {
                    val connection = withTimeout(10_000L) {
                        connectClassic(bluetooth, device, io, receiveCapacity = 256, traffic = log).also {
                            // Own the transport before withTimeout can discard its result at a deadline boundary.
                            if (it is ClassicConnection.Connected) experimentTransport = it.transport
                        }
                    }
                    if (connection is ClassicConnection.Failed) {
                        log.app("connect_failed", connection.reason)
                        _experiment.update { it.copy(phase = "Connection failed: " + connection.reason.replace(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "[adapter]")) }
                        return@withTimeout
                    }
                    val transport = (connection as ClassicConnection.Connected).transport
                    experimentTransport = transport
                    log.app("connected")
                    log.operationStart("cayenne-part-number-experiment", IdentificationExperiment.PLAN_ID)
                    _experiment.update { it.copy(phase = "Connected. Checking startup stream, then sending ATPC.") }
                    IdentificationExperiment.runLive(transport, onUpdate = { result ->
                        _experiment.update { it.copy(result = result, phase = result.explanation) }
                    }).also { result -> _experiment.update { it.copy(result = result, phase = result.explanation) } }
                }
            } catch (e: TimeoutCancellationException) {
                _experiment.update { it.copy(phase = "Deadline reached. Connection closed; no retry.") }
                if (!currentCoroutineContext().isActive) throw e
            } catch (e: CancellationException) {
                _experiment.update { it.copy(phase = "Stopped. Connection closed; partial recording retained.") }
                throw e
            } catch (_: Exception) {
                _experiment.update { it.copy(phase = "Connection failed. Partial recording retained; no retry.") }
            } finally {
                experimentTransport?.close()
                experimentTransport = null
                _experiment.value.result?.let { log.operationEnd("cayenne-part-number-experiment", it.outcome.name, it.explanation) }
                log.interpretation("experiment", _experiment.value.phase)
                log.end("experiment finished")
                // Every attempted write is published before transport.write. If none was
                // attempted, this run could not have changed adapter settings. Keep the
                // pre-connect persisted guard for process death or uncertain writes.
                if (_experiment.value.result?.recording?.steps.isNullOrEmpty() &&
                    experimentPreferences.edit().putBoolean("power-cycle-required", false).commit()) {
                    _experimentReuseRequired.value = false
                }
                // Persist after closing, including cancellation. Never upload or include Bluetooth addresses.
                val report = experimentReport()
                withContext(NonCancellable + io) {
                    try {
                        val dir = File(getApplication<Application>().filesDir, "identification")
                        check(dir.mkdirs() || dir.isDirectory)
                        File(dir, "$experimentId.json").writeText(report)
                        val target = File(dir, "latest-report.json")
                        val temp = File(dir, "latest-report.tmp")
                        temp.writeText(report)
                        check(temp.renameTo(target))
                        _experiment.update { it.copy(saved = true) }
                    } catch (_: Exception) {
                        _experiment.update { it.copy(saveError = "Could not save privately. Export the report before closing the app.") }
                    } finally {
                        // Returning to a cancelled Main context can throw before any
                        // following statement. Release ownership inside this cleanup.
                        _experiment.update { it.copy(busy = false) }
                        // Shows the power-cycle requirement on the home screen if it applies.
                        // VehicleConnection runs on the main dispatcher, never on this IO context.
                        viewModelScope.launch { vehicle.refresh() }
                    }
                }
            }
        }
        return true
    }

    fun stopExperiment() {
        if (!_experiment.value.busy) return
        // Close synchronously so leaving/backgrounding prevents the next write, even before cancellation dispatch.
        experimentTransport?.close()
        experimentJob?.cancel()
    }

    fun confirmExperimentAdapterPowerCycle() {
        if (_experiment.value.busy) return
        if (experimentPreferences.edit().putBoolean("power-cycle-required", false).commit()) {
            _experimentReuseRequired.value = false
            vehicle.refresh()
        }
    }

    /** Keep the owner-reviewed snapshot across Activity recreation while the document picker is open. */
    fun prepareExperimentExport(report: String): Boolean {
        if (report.isBlank()) return false
        pendingExperimentExport = report
        return true
    }

    fun takeExperimentExport(): String? = pendingExperimentExport.also { pendingExperimentExport = null }

    suspend fun savedExperimentReport(): String? = withContext(io) {
        val file = File(getApplication<Application>().filesDir, "identification/latest-report.json")
        if (!file.isFile || file.length() > 2_000_000) null else runCatching { file.readText() }.getOrNull()
    }

    fun experimentReport(): String = JSONObject().apply {
        put("plan", IdentificationExperiment.PLAN_ID)
        put("captureId", experimentId)
        put("startedAtUtc", experimentStartedUtc)
        put("appVersion", appVersion())
        put("transport", "Bluetooth Classic RFCOMM")
        put("connectionStatus", _experiment.value.phase)
        val result = _experiment.value.result
        if (result != null) put("experiment", JSONObject(result.privateReport()))
        else {
            put("outcome", "NOT_STARTED")
            put("vehicleRequestsAttempted", 0)
        }
    }.toString()

    private companion object {
        const val EXPERIMENT_REUSE_MESSAGE = "Unplug and reconnect the scanner after the identification experiment, then confirm this on the Identification experiment screen."
        const val NOT_CONNECTED_MESSAGE = "Not connected. Connect to your scanner on the home screen first."
        const val EXPERIMENT_CAPABILITY = "porsche.cayenne92a.part-number-experiment"
    }

    /** Writes one diagnostic export for sharing or saving. No file when nothing is stored. */
    suspend fun prepareDiagnosticExport(): DiagnosticExportFile = withContext(io) {
        val export = diagnostics.export()
        val json = export.json ?: return@withContext DiagnosticExportFile(null, 0, false, 0)
        val dir = File(getApplication<Application>().cacheDir, "diagnostic-exports")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "bamboobd2-diagnostic-log-$stamp.json").apply { writeText(json) }
        DiagnosticExportFile(file, export.sessions, export.truncated, export.unfinished)
    }

    private fun logRecord(lease: io.github.eternalliquet.bamboobd2.app.connection.Lease, operation: String, outcome: String, record: SessionRecord) {
        val log = lease.log ?: return
        // The reader's own notes (retries, discarded replies, decode failures) are its
        // interpretation of the traffic, which the transport has already logged verbatim.
        record.events.forEach { log.interpretation(operation, "${it.kind}: ${it.detail}") }
        log.operationEnd(operation, outcome, record.events.lastOrNull()?.detail?.ifEmpty { null })
    }

    private fun appVersion(): String {
        val app = getApplication<Application>()
        return app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown version"
    }
}

/** A prepared diagnostic export: [file] is null when no log is stored. */
class DiagnosticExportFile(val file: File?, val sessions: Int, val truncated: Boolean, val unfinished: Int)

/** Version facts included with every diagnostic export. No device identifiers. */
private fun buildInfo(app: Application): Map<String, String> {
    val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
    @Suppress("DEPRECATION")
    val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
    return mapOf(
        "appVersion" to (info?.versionName ?: "unknown"),
        "versionCode" to (code?.toString() ?: "unknown"),
        "android" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )
}

/** Main-dispatcher delays are scheduled on uptime, so replay timing reads the same clock. */
private object UptimeTimeSource : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
    override fun read(): Long = SystemClock.uptimeMillis()
}

/** Where the ViewModel gets its Bluetooth platform. Tests replace it before the activity starts. */
internal object BluetoothPlatforms {
    @Volatile
    var create: (Application) -> BluetoothPlatform = ::AndroidBluetoothPlatform
}
