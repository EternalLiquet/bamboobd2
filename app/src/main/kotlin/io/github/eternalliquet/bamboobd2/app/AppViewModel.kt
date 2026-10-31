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
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
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

/** The codes-only (L2) live read before its session starts. */
sealed interface LiveState {
    data class Available(val availability: BluetoothAvailability, val permissionDenied: Boolean = false) : LiveState
    data class Connecting(val device: String) : LiveState
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

    fun startRecording(device: BondedDevice): Boolean {
        if (experimentReuseRequired.value) {
            _live.value = LiveState.Failed(device.name, EXPERIMENT_REUSE_MESSAGE)
            return false
        }
        if (_experiment.value.busy || recordingJob?.isActive == true || liveJob?.isActive == true || benchJob?.isActive == true ||
            session.value is SessionState.Running || liveResultPending() || _recordingPending.value) return false
        _recordingPending.value = true
        val connecting = LiveState.Connecting(device.name)
        _live.value = connecting
        recordingJob = viewModelScope.launch {
            val connection = try { connectClassic(bluetooth, device, io) }
                catch (e: CancellationException) {
                    // An older cancelled connect must not overwrite a newer attempt.
                    if (_live.value === connecting) {
                        _recordingPending.value = false
                        _live.value = LiveState.Available(bluetooth.availability())
                    }
                    throw e
                }
            if (connection is ClassicConnection.Failed) {
                _recordingPending.value = false
                _live.value = LiveState.Failed(device.name, connection.reason)
                return@launch
            }
            val transport = (connection as ClassicConnection.Connected).transport
            try {
                recordingStartedAt = SystemClock.elapsedRealtime()
                val prepared = liveController.prepare { gate ->
                    ElmObdReader(transport, "bluetooth:classic", requestGate = gate)
                }
                if (!prepared) {
                    _recordingPending.value = false
                    _live.value = LiveState.Failed(device.name, "another recording is running")
                    return@launch
                }
                _live.value = LiveState.Available(bluetooth.availability())
                // Ready and Paused do not read; an EOF must still end this connection.
                val disconnectWatch = launch {
                    transport.awaitDisconnect()
                    liveController.stop(LiveStopReason.DISCONNECTED)
                }
                try {
                    liveController.state.first { it is LiveReadState.Finished }
                } finally { disconnectWatch.cancel() }
            } finally { transport.close() }
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
        // pending for the screen on resume. Only recording setup is stopped here.
        if (_recordingPending.value && _live.value is LiveState.Connecting) cancelLiveConnect()
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
        _bench.value = BenchState.Connecting(device.name)
        benchJob = viewModelScope.launch {
            var check: AdapterBenchCheck? = null
            try {
                _bench.value = when (val run = BluetoothBench(bluetooth, io).run(device) { check = it; _bench.value = BenchState.Running(device.name) }) {
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

    private val _live = MutableStateFlow<LiveState>(LiveState.Available(BluetoothAvailability.PermissionNeeded))
    val live: StateFlow<LiveState> = _live.asStateFlow()
    private var liveJob: Job? = null

    // Raw captures by session id, in memory only. Copied out by the owner for review.
    private val _captures = MutableStateFlow<Map<String, String>>(emptyMap())
    val captures: StateFlow<Map<String, String>> = _captures.asStateFlow()

    fun refreshLive(permissionDenied: Boolean = false) {
        // Never drop a connection in progress or an unacknowledged result hand-off.
        if (_live.value is LiveState.Connecting || _live.value == LiveState.Started) return
        _live.value = LiveState.Available(bluetooth.availability(), permissionDenied)
    }

    /** On opening the live-read screen: keeps a connection failure or a pending hand-off until the user moves on. */
    fun refreshLiveIfIdle() {
        if (_live.value is LiveState.Available) refreshLive()
    }

    /**
     * Explicitly drops a pending live result: a read still running is cancelled (its
     * connection closes), then the session is cleared. Nothing reconnects.
     */
    fun discardPendingLive() {
        if (!liveResultPending()) return
        _live.value = LiveState.Available(bluetooth.availability())
        controller.cancel()
        viewModelScope.launch {
            controller.state.first { it !is SessionState.Running }
            finishSession()
        }
    }

    /** The live-read screen has navigated to the started session: the hand-off is consumed. */
    fun acknowledgeLiveStarted() {
        if (_live.value == LiveState.Started) _live.value = LiveState.Available(bluetooth.availability())
    }

    fun onLivePermissionResult(granted: Boolean) = refreshLive(permissionDenied = !granted)

    fun startLive(device: BondedDevice, readScope: ReadScope = ReadScope.CODES_ONLY) {
        if (experimentReuseRequired.value) {
            _live.value = LiveState.Failed(device.name, EXPERIMENT_REUSE_MESSAGE)
            return
        }
        // Also never while a finished result still waits to be shown: it may be unsaved.
        if (_experiment.value.busy || liveJob?.isActive == true || recordingJob?.isActive == true || _recordingPending.value || benchJob?.isActive == true || session.value is SessionState.Running ||
            _live.value == LiveState.Started
        ) return
        // This immutable value belongs to this connection attempt, even if UI state changes.
        val selectedScope = readScope
        _live.value = LiveState.Connecting(device.name)
        liveJob = viewModelScope.launch {
            val recorder = CaptureRecorder()
            val transport = when (val connection = connectClassic(bluetooth, device, io, recorder)) {
                is ClassicConnection.Failed -> {
                    _live.value = LiveState.Failed(device.name, connection.reason)
                    return@launch
                }
                is ClassicConnection.Connected -> connection.transport
            }
            try {
                // The controller gates any sensor request on the supported map; provenance
                // comes from the LIVE transport, not from the selected scope.
                if (!controller.start(ElmObdReader(transport, "bluetooth:classic"), selectedScope)) {
                    _live.value = LiveState.Failed(device.name, "another session is running")
                    return@launch
                }
                _live.value = LiveState.Started
                val finished = controller.state.first { it is SessionState.Finished } as SessionState.Finished
                val id = finished.record.id
                val scopeLabel = when (selectedScope) {
                    ReadScope.CODES_ONLY -> "codes-only (L2) session"
                    ReadScope.FULL -> "codes + supported sensor snapshot (L3 software; not vehicle-tested) session"
                }
                val source = "BambooBD2 ${appVersion()}, Bluetooth Classic, $scopeLabel, outcome " +
                    "${finished.record.outcome}. Unreviewed raw capture: see docs/CAPTURE_IMPORT.md."
                _captures.update { it + (id to recorder.json(id, source)) }
            } finally {
                // No reconnect: a new read is a new connection and a new session. The Started
                // hand-off stays until the screen acknowledges it.
                transport.close()
            }
        }
    }

    /**
     * On leaving the live-read screen: stops a connection attempt, or a session that
     * started in the same instant (it would otherwise run with no screen showing it).
     */
    fun cancelLiveConnect() {
        when (_live.value) {
            is LiveState.Connecting -> {
                recordingJob?.cancel()
                // A new connect has no new record yet; the controller may still hold
                // a previously finished record rather than Idle.
                _recordingPending.value = false
                liveJob?.cancel()
                _live.value = LiveState.Available(bluetooth.availability())
            }
            LiveState.Started -> controller.cancel()
            else -> Unit
        }
    }

    private val _experiment = MutableStateFlow(ExperimentUiState())
    val experiment: StateFlow<ExperimentUiState> = _experiment.asStateFlow()
    private val _experimentBluetooth = MutableStateFlow<BluetoothAvailability>(BluetoothAvailability.PermissionNeeded)
    val experimentBluetooth: StateFlow<BluetoothAvailability> = _experimentBluetooth.asStateFlow()
    private val experimentPreferences = application.getSharedPreferences("identification-safety", 0)
    private val _experimentReuseRequired = MutableStateFlow(experimentPreferences.getBoolean("power-cycle-required", false))
    val experimentReuseRequired: StateFlow<Boolean> = _experimentReuseRequired.asStateFlow()
    private var pendingExperimentExport: String? = null
    private var experimentId = "not-started"
    private var experimentStartedUtc = ""
    private var experimentJob: Job? = null
    private var experimentTransport: io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothClassicTransport? = null

    fun refreshExperimentBluetooth() {
        if (!_experiment.value.busy) _experimentBluetooth.value = bluetooth.availability()
    }

    /** All start gates run on the main dispatcher; busy stays set until the socket is closed. */
    fun startExperiment(device: BondedDevice, reviewed: Boolean): Boolean {
        if (!reviewed || experimentReuseRequired.value || _experiment.value.busy || listOf(benchJob, liveJob, recordingJob).any { it != null && !it.isCompleted } ||
            session.value is SessionState.Running || liveResultPending() || _recordingPending.value) return false
        // Persist before opening the socket. A killed process cannot accidentally reuse experimental adapter settings.
        if (!experimentPreferences.edit().putBoolean("power-cycle-required", true).commit()) {
            _experiment.value = ExperimentUiState(phase = "Could not save the adapter reuse guard. No connection attempted.")
            return false
        }
        _experimentReuseRequired.value = true
        experimentId = java.util.UUID.randomUUID().toString()
        experimentStartedUtc = java.time.Instant.now().toString()
        _experiment.value = ExperimentUiState(busy = true, phase = "Connecting to scanner…")
        experimentJob = viewModelScope.launch {
            try {
                withTimeout(60_000L) {
                    val connection = withTimeout(10_000L) {
                        connectClassic(bluetooth, device, io, receiveCapacity = 256).also {
                            // Own the transport before withTimeout can discard its result at a deadline boundary.
                            if (it is ClassicConnection.Connected) experimentTransport = it.transport
                        }
                    }
                    if (connection is ClassicConnection.Failed) {
                        _experiment.update { it.copy(phase = "Connection failed: " + connection.reason.replace(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "[adapter]")) }
                        return@withTimeout
                    }
                    val transport = (connection as ClassicConnection.Connected).transport
                    experimentTransport = transport
                    _experiment.update { it.copy(phase = "Connected. Waiting for an idle adapter prompt; no commands sent yet.") }
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
                    }
                }
                _experiment.update { it.copy(busy = false) }
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
        if (experimentPreferences.edit().putBoolean("power-cycle-required", false).commit())
            _experimentReuseRequired.value = false
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
    }

    private fun appVersion(): String {
        val app = getApplication<Application>()
        return app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown version"
    }
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
