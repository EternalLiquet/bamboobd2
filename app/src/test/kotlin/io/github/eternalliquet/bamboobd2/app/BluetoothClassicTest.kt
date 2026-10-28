package io.github.eternalliquet.bamboobd2.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.AndroidBluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BenchRun
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothBench
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothClassicTransport
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.app.bluetooth.RfcommSocket
import io.github.eternalliquet.bamboobd2.core.bench.BenchOutcome
import io.github.eternalliquet.bamboobd2.core.bench.BenchStop
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterCommand
import io.github.eternalliquet.bamboobd2.core.protocol.RequestPolicy
import io.github.eternalliquet.bamboobd2.core.protocol.TransportClosedException
import io.github.eternalliquet.bamboobd2.core.protocol.TransportKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

/**
 * The Bluetooth Classic layer without hardware: Robolectric's Bluetooth shadows for
 * availability and permissions, and a fake RFCOMM socket for the connection. This
 * checks wiring, not compatibility with any phone or adapter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BluetoothClassicTest {

    /** A scripted socket: an "adapter" thread answers each CR-terminated command it receives. */
    private class FakeSocket(
        private val replies: Map<String, String> = emptyMap(),
        private val blockConnect: Boolean = false,
        private val connectError: IOException? = null,
        /** Ends the input stream (a disconnect) when this command arrives, before answering. */
        private val dropOn: String? = null,
    ) : RfcommSocket {
        val written = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        private val toApp = PipedOutputStream()
        override val input: InputStream = PipedInputStream(toApp, 4096)
        private val release = CountDownLatch(1)

        override fun connect() {
            connectError?.let { throw it }
            if (blockConnect) {
                release.await()
                throw IOException("socket closed")
            }
        }

        override val output: OutputStream = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                synchronized(written) { written.write(b) }
                if (b == '\r'.code) {
                    val command = line.toString()
                    line.setLength(0)
                    if (command == dropOn) toApp.close() else replies[command]?.let { toApp.write(it.toByteArray(Charsets.US_ASCII)); toApp.flush() }
                } else {
                    line.append(b.toChar())
                }
            }
        }

        override fun close() {
            release.countDown()
            runCatching { toApp.close() }
            closed.countDown()
        }

        fun writtenText(): String = synchronized(written) { written.toString(Charsets.US_ASCII.name()) }
    }

    private class FakePlatform(private val socket: RfcommSocket, private val denied: Boolean = false) : BluetoothPlatform {
        override val connectPermission: String? = Manifest.permission.BLUETOOTH_CONNECT
        override fun availability() = BluetoothAvailability.Ready(listOf(device))
        override fun openSocket(address: String): RfcommSocket {
            if (denied) throw SecurityException("denied")
            return socket
        }
    }

    private companion object {
        val device = BondedDevice("VEEPEAK", "00:11:22:33:44:55")
        val adapterReplies = mapOf(
            "ATI" to "ATI\rELM327 v1.5\r\r>",
            "AT@1" to "OBDII to RS232 Interpreter\r\r>",
            "ATRV" to "12.6V\r\r>",
        )
    }

    @Test
    fun benchOverTheSocketSendsOnlyAdapterCommandsAndReadsReplies() = runBlocking {
        val socket = FakeSocket(adapterReplies)
        val run = BluetoothBench(FakePlatform(socket), Dispatchers.IO).run(device)
        val result = (run as BenchRun.Finished).result
        assertEquals(BenchStop.COMPLETE, result.stop)
        assertEquals("ATI\rAT@1\rATRV\r", socket.writtenText())
        assertEquals(BenchOutcome.Reply(listOf("ELM327 v1.5")), result.steps[0].outcome)
        assertEquals(BenchOutcome.Reply(listOf("12.6V")), result.steps[2].outcome)
        assertTrue("socket closed after the check", socket.closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun connectFailureAndPermissionDenialAreReportedWithoutRetry() = runBlocking {
        val failing = FakeSocket(connectError = IOException("read failed, socket might closed"))
        val failed = BluetoothBench(FakePlatform(failing), Dispatchers.IO).run(device)
        assertTrue(failed.toString(), (failed as BenchRun.ConnectFailed).reason.startsWith("could not connect"))
        assertEquals("", failing.writtenText())

        val denied = BluetoothBench(FakePlatform(FakeSocket(), denied = true), Dispatchers.IO).run(device)
        assertEquals(BenchRun.ConnectFailed("Bluetooth permission not granted"), denied)
    }

    @Test
    fun cancellingDuringABlockingConnectClosesTheSocket() = runBlocking {
        val socket = FakeSocket(blockConnect = true)
        val job = async(Dispatchers.Default) { BluetoothBench(FakePlatform(socket), Dispatchers.IO).run(device) }
        delay(200)
        job.cancel()
        assertTrue("socket closed on cancel", socket.closed.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals("", socket.writtenText())
    }

    @Test
    fun disconnectKeepsEarlierRepliesAndStops() = runBlocking {
        val socket = FakeSocket(adapterReplies, dropOn = "AT@1")
        val result = (BluetoothBench(FakePlatform(socket), Dispatchers.IO).run(device) as BenchRun.Finished).result
        assertEquals(BenchStop.DISCONNECTED, result.stop)
        assertEquals(BenchOutcome.Reply(listOf("ELM327 v1.5")), result.steps[0].outcome)
        assertEquals(BenchOutcome.NotRun, result.steps[2].outcome)
        assertEquals("ATI\rAT@1\r", socket.writtenText())
    }

    @Test
    fun transportIsLiveDeliversBytesAfterATimeoutAndRefusesWritesWhenClosed() = runBlocking {
        val pipe = PipedOutputStream()
        val socket = object : RfcommSocket {
            override fun connect() = Unit
            override val input: InputStream = PipedInputStream(pipe, 1024)
            override val output: OutputStream = ByteArrayOutputStream()
            override fun close() = pipe.close()
        }
        val transport = BluetoothClassicTransport(socket, Dispatchers.IO)
        assertEquals(TransportKind.LIVE, transport.kind)
        // A read that times out loses nothing that arrives later.
        assertNull(withTimeoutOrNull(100) { transport.read() })
        pipe.write("OK\r\r>".toByteArray()); pipe.flush()
        assertEquals("OK\r\r>", String(withTimeout(2_000) { transport.read() }!!))
        transport.close()
        assertNull(withTimeout(2_000) { transport.read() })
        try {
            transport.write(RequestPolicy.adapter(AdapterCommand.VERSION_ID))
            fail("write after close")
        } catch (e: TransportClosedException) {
            // expected
        }
    }

    @Test
    fun platformReportsPermissionOffAndPairedDevicesWithoutScanning() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val platform = AndroidBluetoothPlatform(app)
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, platform.connectPermission)
        assertEquals(BluetoothAvailability.PermissionNeeded, platform.availability())

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(false)
        assertEquals(BluetoothAvailability.Off, platform.availability())

        shadowOf(adapter).setEnabled(true)
        val paired = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55").also { shadowOf(it).setName("VEEPEAK") }
        shadowOf(adapter).setBondedDevices(setOf(paired))
        assertEquals(BluetoothAvailability.Ready(listOf(BondedDevice("VEEPEAK", "00:11:22:33:44:55"))), platform.availability())
    }
}
