package io.github.eternalliquet.bamboobd2.app.connection

import android.content.Context
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothClassicTransport
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.app.bluetooth.ClassicConnection
import io.github.eternalliquet.bamboobd2.app.bluetooth.connectClassic
import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerRegistry
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerService
import io.github.eternalliquet.bamboobd2.core.manufacturer.VehicleContext
import io.github.eternalliquet.bamboobd2.core.protocol.VehicleRequest
import io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader
import io.github.eternalliquet.bamboobd2.core.reader.StopReason
import io.github.eternalliquet.bamboobd2.core.vehicle.IdentificationResult
import io.github.eternalliquet.bamboobd2.core.vehicle.VehicleIdentifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The saved scanner. Identifies the adapter, never the car. The address is kept only
 * in app-private preferences (backup is off) and is left out of [toString].
 */
data class SavedAdapter(val name: String, internal val address: String) {
    internal fun device() = BondedDevice(name, address)
    override fun toString(): String = "SavedAdapter($name)"
}

/** The one remembered scanner. Forget removes both fields. */
class SavedAdapterStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved-adapter", Context.MODE_PRIVATE)

    fun load(): SavedAdapter? = prefs.getString(ADDRESS, null)?.let { SavedAdapter(prefs.getString(NAME, null) ?: "(no name)", it) }
    fun save(device: BondedDevice): Boolean = prefs.edit().putString(NAME, device.name).putString(ADDRESS, device.address).commit()
    fun forget(): Boolean = prefs.edit().clear().commit()

    private companion object {
        const val NAME = "name"
        const val ADDRESS = "address"
    }
}

/** Why a saved scanner cannot be connected right now. Each has one clear user action. */
enum class Blocker { NO_BLUETOOTH, PERMISSION_NEEDED, PERMISSION_DENIED, BLUETOOTH_OFF, NOT_PAIRED, POWER_CYCLE_REQUIRED, OTHER_TOOL_ACTIVE }

/** What the app knows about the car on the current connection. */
sealed interface VehicleStatus {
    /** Generic discovery answered. The context says what is known, including "make unknown". */
    data class Known(val context: VehicleContext) : VehicleStatus

    /** The scanner is connected but generic discovery stopped, e.g. ignition off. */
    data class NoAnswer(val reason: String, val detail: String) : VehicleStatus
}

sealed interface ConnectionState {
    data object NotSetUp : ConnectionState
    data class Blocked(val adapter: SavedAdapter, val blocker: Blocker) : ConnectionState
    /** Not connected. [reason] is plain text for the user; [detail] is the technical cause. */
    data class Disconnected(val adapter: SavedAdapter, val reason: String? = null, val detail: String? = null) : ConnectionState
    data class Connecting(val adapter: SavedAdapter) : ConnectionState
    data class Identifying(val adapter: SavedAdapter) : ConnectionState

    /**
     * Connected and identification finished, successfully or not. Generic scans may
     * run. [generation] names this connection's vehicle context; [transcript] is the
     * redacted identification exchange for the details view.
     */
    data class Ready(val adapter: SavedAdapter, val generation: Long, val vehicle: VehicleStatus, val transcript: String) : ConnectionState
}

/** Exclusive use of the shared transport for one operation; invalid once its generation ends. */
class Lease internal constructor(val transport: BluetoothClassicTransport, val generation: Long)

/**
 * Owns the single Bluetooth connection to the saved scanner, its vehicle context and
 * every state change between them. Runs on the main dispatcher.
 *
 * - One transport, one user at a time: identification, then [Lease]s for scans or
 *   recording. Nothing reads or writes concurrently.
 * - Every connect, identification attempt, disconnect or loss starts a new generation.
 *   A result from an older generation is discarded, and so is the vehicle context and
 *   manufacturer service cache: the next car always starts unidentified.
 * - No automatic reconnect and no background work: [startup] tries once, everything
 *   else is a user action.
 */
class VehicleConnection(
    private val scope: CoroutineScope,
    private val platform: BluetoothPlatform,
    private val io: CoroutineDispatcher,
    private val store: SavedAdapterStore,
    private val registry: ManufacturerRegistry = ManufacturerRegistry.BUNDLED,
    /** The experiment's persisted adapter-reuse guard. */
    private val powerCycleRequired: () -> Boolean,
    /** True while the bench check or experiment owns its own connection. */
    private val otherToolActive: () -> Boolean,
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var generation = 0L
    private var transport: BluetoothClassicTransport? = null
    private var job: Job? = null
    private var watch: Job? = null
    private var lease: Lease? = null
    /** Services created for this connection only; cleared with the generation. */
    private val services = mutableMapOf<String, ManufacturerService>()
    private var startupDone = false
    private var startupPending = false
    private var foreground = true
    private var closeWhenReleased = false

    val savedAdapter: SavedAdapter? get() = store.load()
    val leased: Boolean get() = lease != null

    private fun initialState(): ConnectionState = store.load()?.let { ConnectionState.Disconnected(it) } ?: ConnectionState.NotSetUp

    /** Normal foreground launch: one attempt with the saved scanner, at most once per ViewModel. */
    fun startup() {
        if (startupDone) return
        startupDone = true
        val adapter = store.load() ?: return
        if (blockerFor(adapter) != null) startupPending = true
        connect()
    }

    /** Saves [device] as the scanner and connects to it; replaces any attempt in progress. Refused while a scan holds the connection. */
    fun choose(device: BondedDevice): Boolean {
        if (lease != null) return false
        teardown(null)
        if (!store.save(device)) {
            _state.value = ConnectionState.Disconnected(SavedAdapter(device.name, device.address), "Couldn't save the scanner choice. Try again.")
            return false
        }
        return connect()
    }

    fun forget() {
        teardown(ConnectionState.NotSetUp)
        store.forget()
        startupPending = false
    }

    /** User-requested connection (or the startup attempt). Never retries by itself. */
    fun connect(permissionDenied: Boolean = false): Boolean {
        val adapter = store.load() ?: run { _state.value = ConnectionState.NotSetUp; return false }
        if (isBusy()) return false
        blockerFor(adapter, permissionDenied)?.let { _state.value = ConnectionState.Blocked(adapter, it); return false }
        startupPending = false
        val gen = ++generation
        _state.value = ConnectionState.Connecting(adapter)
        job = scope.launch {
            val result = connectClassic(platform, adapter.device(), io)
            if (gen != generation) { (result as? ClassicConnection.Connected)?.transport?.close(); return@launch }
            when (result) {
                is ClassicConnection.Failed -> _state.value = ConnectionState.Disconnected(adapter,
                    "Couldn't connect to ${adapter.name}. Check that it's plugged in and powered, then try again.", result.reason)
                is ClassicConnection.Connected -> {
                    val t = result.transport
                    transport = t
                    watch = scope.launch {
                        t.awaitDisconnect()
                        if (transport === t) teardown(ConnectionState.Disconnected(adapter, "The scanner connection ended."))
                    }
                    identify(adapter, t, gen)
                }
            }
        }
        return true
    }

    /** Re-runs identification on the open connection, e.g. after turning the ignition on. */
    fun identifyAgain(): Boolean {
        val ready = _state.value as? ConnectionState.Ready ?: return false
        val t = transport ?: return false
        if (lease != null) return false
        services.clear()
        val gen = ++generation
        job = scope.launch { identify(ready.adapter, t, gen) }
        return true
    }

    /** The user's model confirmation for this connection only. Null clears it. */
    fun confirmProfile(profileId: String?) {
        val ready = _state.value as? ConnectionState.Ready ?: return
        val known = ready.vehicle as? VehicleStatus.Known ?: return
        _state.value = ready.copy(vehicle = VehicleStatus.Known(registry.resolve(known.context.evidence, profileId, services)))
    }

    /** Stops a connection attempt or identification, or closes the connection. */
    fun disconnect(reason: String? = null) {
        val adapter = store.load() ?: return teardown(ConnectionState.NotSetUp)
        teardown(ConnectionState.Disconnected(adapter, reason))
    }

    /** Rereads Bluetooth state while idle; completes a startup attempt that was blocked. */
    fun refresh(permissionDenied: Boolean = false) {
        when (val s = _state.value) {
            is ConnectionState.NotSetUp, is ConnectionState.Blocked, is ConnectionState.Disconnected -> {
                val adapter = store.load() ?: run { _state.value = ConnectionState.NotSetUp; return }
                val blocker = blockerFor(adapter, permissionDenied)
                when {
                    blocker != null -> _state.value = ConnectionState.Blocked(adapter, blocker)
                    startupPending -> connect()
                    s is ConnectionState.Blocked || s is ConnectionState.NotSetUp -> _state.value = ConnectionState.Disconnected(adapter)
                }
            }
            else -> Unit
        }
    }

    fun acquire(): Lease? {
        val ready = _state.value as? ConnectionState.Ready ?: return null
        if (lease != null) return null
        val t = transport ?: return null
        return Lease(t, ready.generation).also { lease = it }
    }

    fun release(released: Lease) {
        if (lease !== released) return
        lease = null
        released.transport.recorder = null
        if (closeWhenReleased && !foreground) disconnect("Disconnected while the app was in the background.")
    }

    /** Before the bench check or experiment opens its own connection. False while a scan holds the transport. */
    fun releaseForOtherTool(): Boolean {
        if (lease != null) return false
        if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Identifying ||
            _state.value is ConnectionState.Ready) disconnect("Disconnected so another tool could use the scanner.")
        return true
    }

    fun onBackgrounded() {
        foreground = false
        when (_state.value) {
            is ConnectionState.Connecting, is ConnectionState.Identifying -> disconnect("Stopped when the app went to the background.")
            is ConnectionState.Ready -> if (lease == null) disconnect("Disconnected while the app was in the background.") else closeWhenReleased = true
            else -> Unit
        }
    }

    fun onForegrounded() {
        foreground = true
        closeWhenReleased = false
        refresh()
    }

    private fun isBusy() = when (_state.value) {
        is ConnectionState.Connecting, is ConnectionState.Identifying -> true
        is ConnectionState.Ready -> lease != null
        else -> false
    }

    private suspend fun identify(adapter: SavedAdapter, t: BluetoothClassicTransport, gen: Long) {
        _state.value = ConnectionState.Identifying(adapter)
        val recorder = CaptureRecorder()
        t.recorder = recorder
        val result = try { VehicleIdentifier(ElmObdReader(t, "bluetooth:classic")).identify() }
            finally { if (t.recorder === recorder) t.recorder = null }
        if (gen != generation) return
        val transcript = transcript(recorder)
        _state.value = when (result) {
            is IdentificationResult.Completed ->
                ConnectionState.Ready(adapter, gen, VehicleStatus.Known(registry.resolve(result.evidence, null, services)), transcript)
            is IdentificationResult.Stopped -> {
                if (result.reason == StopReason.DISCONNECTED)
                    return teardown(ConnectionState.Disconnected(adapter, "The scanner connection ended during identification.", result.detail))
                ConnectionState.Ready(adapter, gen, VehicleStatus.NoAnswer(noAnswer(result.reason), result.detail), transcript)
            }
        }
    }

    /** Ends the current generation: closes the transport and drops all vehicle state. */
    private fun teardown(next: ConnectionState?) {
        generation++
        job?.cancel(); job = null
        watch?.cancel(); watch = null
        transport?.close(); transport = null
        lease = null
        closeWhenReleased = false
        services.clear()
        if (next != null) _state.value = next
    }

    private fun blockerFor(adapter: SavedAdapter, permissionDenied: Boolean = false): Blocker? {
        if (powerCycleRequired()) return Blocker.POWER_CYCLE_REQUIRED
        if (otherToolActive()) return Blocker.OTHER_TOOL_ACTIVE
        return when (val a = platform.availability()) {
            BluetoothAvailability.NoAdapter -> Blocker.NO_BLUETOOTH
            BluetoothAvailability.PermissionNeeded -> if (permissionDenied) Blocker.PERMISSION_DENIED else Blocker.PERMISSION_NEEDED
            BluetoothAvailability.Off -> Blocker.BLUETOOTH_OFF
            is BluetoothAvailability.Ready -> if (a.bonded.none { it.address == adapter.address }) Blocker.NOT_PAIRED else null
        }
    }

    private fun noAnswer(reason: StopReason) = when (reason) {
        StopReason.NO_PROTOCOL -> "The scanner is connected, but no car answered. Turn the ignition on, then identify again."
        StopReason.NO_RESPONSE -> "The scanner stopped answering during identification."
        StopReason.ADAPTER_REJECTED -> "The scanner refused a setup command."
        StopReason.ADAPTER_RESET -> "The scanner restarted during identification. Check its power."
        StopReason.DISCONNECTED -> "The scanner connection ended."
    }

    companion object {
        /**
         * The identification exchange for the details view. The VIN reply is replaced by
         * its size, so this text is safe to show; it is still not saved anywhere.
         */
        fun transcript(recorder: CaptureRecorder): String =
            recorder.script("identification", "identification").steps.joinToString("\n") { step ->
                val reply = step.chunks.joinToString("") { it.text }
                val shown = if (step.request == VehicleRequest.VIN.text && reply.isNotBlank()) "[VIN reply hidden, ${reply.length} characters]"
                    else reply.replace("\r", " ").replace(">", "").trim().ifEmpty { "(no reply)" }
                "> ${step.request}\n  $shown"
            }
    }
}
