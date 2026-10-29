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
        if (recordingJob?.isActive == true || liveJob?.isActive == true || benchJob?.isActive == true ||
            session.value is SessionState.Running || liveResultPending() || _recordingPending.value) return false
        _recordingPending.value = true
        _live.value = LiveState.Connecting(device.name)
        recordingJob = viewModelScope.launch {
            val connection = try { connectClassic(bluetooth, device, io) }
                catch (e: CancellationException) { _live.value = LiveState.Available(bluetooth.availability()); throw e }
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
                liveController.state.first { it is LiveReadState.Finished }
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
        // One Bluetooth use at a time: never alongside a live read.
        if (benchJob?.isActive == true || liveJob?.isActive == true || recordingJob?.isActive == true || _recordingPending.value) return
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
        // Also never while a finished result still waits to be shown: it may be unsaved.
        if (liveJob?.isActive == true || recordingJob?.isActive == true || _recordingPending.value || benchJob?.isActive == true || session.value is SessionState.Running ||
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
                if (recording.value is LiveReadState.Idle) _recordingPending.value = false
                liveJob?.cancel()
                _live.value = LiveState.Available(bluetooth.availability())
            }
            LiveState.Started -> controller.cancel()
            else -> Unit
        }
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
