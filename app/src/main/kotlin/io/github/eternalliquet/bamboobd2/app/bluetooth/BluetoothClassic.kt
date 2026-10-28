package io.github.eternalliquet.bamboobd2.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.eternalliquet.bamboobd2.core.bench.AdapterBenchCheck
import io.github.eternalliquet.bamboobd2.core.bench.BenchResult
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterTransport
import io.github.eternalliquet.bamboobd2.core.protocol.Request
import io.github.eternalliquet.bamboobd2.core.protocol.TransportClosedException
import io.github.eternalliquet.bamboobd2.core.protocol.TransportKind
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Bluetooth Classic (RFCOMM, Serial Port Profile) connection to an already-paired
 * adapter. No discovery or scanning: the user pairs in system settings, and the
 * app only lists bonded devices. Sources and permissions: docs/BLUETOOTH_TRANSPORT.md.
 */

/** A paired device as listed to the user. The address is used to connect and is never stored. */
data class BondedDevice(val name: String, val address: String)

sealed interface BluetoothAvailability {
    data object NoAdapter : BluetoothAvailability
    data object PermissionNeeded : BluetoothAvailability
    data object Off : BluetoothAvailability
    data class Ready(val bonded: List<BondedDevice>) : BluetoothAvailability
}

/** The blocking socket surface the transport uses, so tests can stand in for the platform. */
interface RfcommSocket {
    /** Blocks until connected; throws [IOException] on failure or when closed from another thread. */
    fun connect()
    val input: InputStream
    val output: OutputStream
    fun close()
}

interface BluetoothPlatform {
    /** The runtime permission to request before connecting, or null when the OS grants it at install. */
    val connectPermission: String?
    fun availability(): BluetoothAvailability
    fun openSocket(address: String): RfcommSocket
}

class AndroidBluetoothPlatform(private val context: Context) : BluetoothPlatform {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    // Android 12+ (API 31): BLUETOOTH_CONNECT, a runtime permission. Android 11 and lower: the
    // install-time BLUETOOTH permission, declared with maxSdkVersion 30.
    override val connectPermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    private fun permitted(): Boolean = connectPermission?.let {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    } ?: true

    @SuppressLint("MissingPermission") // checked by permitted() first
    override fun availability(): BluetoothAvailability {
        val adapter = adapter ?: return BluetoothAvailability.NoAdapter
        if (!permitted()) return BluetoothAvailability.PermissionNeeded
        if (!adapter.isEnabled) return BluetoothAvailability.Off
        val bonded = adapter.bondedDevices.orEmpty().map { BondedDevice(it.name ?: "(no name)", it.address) }
        return BluetoothAvailability.Ready(bonded.sortedBy { it.name })
    }

    @SuppressLint("MissingPermission") // checked by permitted() first
    override fun openSocket(address: String): RfcommSocket {
        val adapter = adapter ?: throw IOException("no Bluetooth adapter")
        if (!permitted()) throw IOException("Bluetooth permission not granted")
        // No cancelDiscovery(): this app never starts discovery, and the call would need BLUETOOTH_SCAN.
        val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SPP_UUID)
        return object : RfcommSocket {
            override fun connect() = socket.connect()
            override val input: InputStream get() = socket.inputStream
            override val output: OutputStream get() = socket.outputStream
            override fun close() = socket.close()
        }
    }

    companion object {
        /** Serial Port Profile, the UUID Android's BluetoothDevice reference suggests for serial boards. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * The real transport (LIVE, pinned in transport-registry.txt). A reader thread pumps
 * the socket into a queue, so [read] can be cancelled by a deadline without losing
 * bytes: a chunk taken but not delivered goes back to the front.
 */
class BluetoothClassicTransport internal constructor(
    private val socket: RfcommSocket,
    private val io: CoroutineDispatcher,
) : AdapterTransport {
    override val kind: TransportKind = TransportKind.LIVE

    private val returned = ConcurrentLinkedDeque<ByteArray>()
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED) { returned.addFirst(it) }
    private val pump = CoroutineScope(SupervisorJob() + io)
    @Volatile private var closed = false

    init {
        pump.launch {
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                while (true) {
                    val n = socket.input.read(buffer)
                    if (n < 0) break
                    if (n > 0) chunks.send(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                // The connection ended; readers see the end of the stream.
            } finally {
                chunks.close()
            }
        }
    }

    override suspend fun write(request: Request) {
        if (closed) throw TransportClosedException("Bluetooth connection closed")
        try {
            withContext(io) {
                socket.output.write(request.encoded())
                socket.output.flush()
            }
        } catch (e: IOException) {
            throw TransportClosedException("Bluetooth write failed: ${e.message}")
        }
    }

    override suspend fun read(): ByteArray? = returned.pollFirst() ?: chunks.receiveCatching().getOrNull()

    /** Closes the socket; a blocked read ends. Never reopened: a new connection is a new transport. */
    fun close() {
        closed = true
        runCatching { socket.close() }
        pump.cancel()
    }

    private companion object {
        const val BUFFER_BYTES = 256
    }
}

sealed interface BenchRun {
    data class ConnectFailed(val reason: String) : BenchRun
    data class Finished(val result: BenchResult) : BenchRun
}

/**
 * Connects to one paired device, runs the adapter-only [AdapterBenchCheck] and
 * closes. The transport never leaves this class, so nothing else (in particular
 * the vehicle-read session) can use the Bluetooth connection in this stage.
 */
class BluetoothBench(
    private val platform: BluetoothPlatform,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Cancelling the caller closes the socket, including during the blocking connect.
     * [onCheck] receives the running check, so a caller can read its partial result
     * after cancelling.
     */
    suspend fun run(device: BondedDevice, onCheck: (AdapterBenchCheck) -> Unit = {}): BenchRun {
        val socket = try {
            platform.openSocket(device.address)
        } catch (e: IOException) {
            return BenchRun.ConnectFailed(e.message ?: "could not open a socket")
        } catch (e: SecurityException) {
            return BenchRun.ConnectFailed("Bluetooth permission not granted")
        }
        try {
            coroutineScope {
                val connecting = async(io) { socket.connect() }
                try {
                    connecting.await()
                } catch (e: CancellationException) {
                    // connect() blocks; closing the socket from here is how it is aborted.
                    runCatching { socket.close() }
                    throw e
                }
            }
        } catch (e: IOException) {
            runCatching { socket.close() }
            return BenchRun.ConnectFailed("could not connect: ${e.message ?: "no detail"}")
        }
        val transport = BluetoothClassicTransport(socket, io)
        try {
            val check = AdapterBenchCheck(transport)
            onCheck(check)
            return BenchRun.Finished(check.run())
        } finally {
            transport.close()
        }
    }
}
