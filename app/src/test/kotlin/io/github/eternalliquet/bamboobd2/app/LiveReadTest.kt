package io.github.eternalliquet.bamboobd2.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.app.bluetooth.RfcommSocket
import io.github.eternalliquet.bamboobd2.app.ui.PROVENANCE_BANNER_TAG
import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowLooper

/**
 * The codes-only (L2) live read, without a car or adapter: a scripted fake ELM socket,
 * and Robolectric's shadow socket for the real Android path. JVM, not a device.
 */
@RunWith(RobolectricTestRunner::class)
class LiveReadTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Answers each CR-terminated command from [replies]; anything else gets no answer. */
    private class FakeElmSocket(private val replies: Map<String, String>, private val connectError: IOException? = null) : RfcommSocket {
        private val written = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        private val toApp = PipedOutputStream()
        override val input: InputStream = PipedInputStream(toApp, 8192)
        override fun connect() { connectError?.let { throw it } }
        override val output: OutputStream = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                synchronized(written) { written.write(b) }
                if (b == '\r'.code) {
                    replies[line.toString()]?.let { toApp.write(it.toByteArray(Charsets.US_ASCII)); toApp.flush() }
                    line.setLength(0)
                } else {
                    line.append(b.toChar())
                }
            }
        }
        override fun close() { runCatching { toApp.close() }; closed.countDown() }
        fun writtenText() = synchronized(written) { written.toString(Charsets.US_ASCII.name()) }
    }

    private class FakePlatform(private val socket: RfcommSocket) : BluetoothPlatform {
        override val connectPermission: String? = null
        override fun availability() = BluetoothAvailability.Ready(listOf(device))
        override fun openSocket(address: String) = socket
    }

    private companion object {
        val device = BondedDevice("VEEPEAK", "00:11:22:33:44:55")
        // Shaped like the ELM327 data sheet's CAN examples. Synthetic, not from a car.
        val carReplies = mapOf(
            "ATE0" to "ATE0\rOK\r\r>",
            "ATL0" to "OK\r\r>",
            "ATH1" to "OK\r\r>",
            "ATSP0" to "OK\r\r>",
            "0100" to "SEARCHING...\r7E8 06 41 00 BE 1F B8 10\r\r>",
            "ATDPN" to "A6\r\r>",
            "03" to "7E8 04 43 01 01 33\r\r>",
            "07" to "7E8 02 47 00\r\r>",
        )
    }

    private fun viewModel(socket: RfcommSocket): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = Files.createTempDirectory("live").toFile()
        return AppViewModel(app, FileSessionStore(dir), Dispatchers.IO, FakePlatform(socket))
    }

    private fun idleUntil(seconds: Long, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < end) { "condition not met in ${seconds}s" }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    @Test
    fun codesOnlyReadSendsOnlySetupMapAndCodesAndKeepsALiveRecordAndCapture() {
        val socket = FakeElmSocket(carReplies)
        val vm = viewModel(socket)
        vm.startLive(device)
        idleUntil(20) { vm.session.value is SessionState.Finished }
        val record = (vm.session.value as SessionState.Finished).record

        // Exactly the L2 sequence: no sensor PIDs, no other vehicle request.
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r", socket.writtenText())
        assertEquals(Provenance.LIVE, record.provenance)
        assertEquals(SessionOutcome.COMPLETE, record.outcome)
        assertEquals(listOf("P0133"), record.dtcs.map { it.code })
        assertTrue(record.allCodesRead)
        assertTrue(record.readings.isEmpty())
        assertTrue(record.events.any { it.kind == SessionController.READINGS_NOT_REQUESTED })

        idleUntil(5) { record.id in vm.captures.value }
        val capture = vm.captures.value.getValue(record.id)
        assertTrue(capture, "\"provenance\": \"${CaptureRecorder.RAW_CAPTURE}\"" in capture)
        val requests = Regex(""""request": "([^"]+)"""").findAll(capture).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "03", "07"), requests)
        // No device identifiers in what is kept or shared.
        val saved = io.github.eternalliquet.bamboobd2.core.storage.SessionCodec.encode(record)
        for (identifier in listOf(device.address, device.name)) {
            assertTrue("$identifier in capture", identifier !in capture)
            assertTrue("$identifier in record", identifier !in saved)
        }
        assertTrue("connection closed after the session", socket.closed.await(5, TimeUnit.SECONDS))
        idleUntil(5) { vm.live.value is LiveState.Available }
    }

    @Test
    fun aConnectFailureStartsNoSession() {
        val vm = viewModel(FakeElmSocket(carReplies, connectError = IOException("read failed")))
        vm.startLive(device)
        idleUntil(10) { vm.live.value is LiveState.Failed }
        assertTrue((vm.live.value as LiveState.Failed).reason.startsWith("could not connect"))
        assertEquals(SessionState.Idle, vm.session.value)
    }

    @Test
    fun anAdapterThatNeverAnswersEndsAsAFailedLiveSessionOnTheSessionScreen() {
        // The real Android socket path, to Robolectric's shadow socket, which never answers.
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        val app = vm.getApplication<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val paired = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55").also { shadowOf(it).setName("VEEPEAK") }
        shadowOf(adapter).setBondedDevices(setOf(paired))

        rule.onNodeWithText("Read codes from the car (Bluetooth)").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            rule.onAllNodes(hasText("Paired devices.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE READ — car codes only, no sensor readings or clearing")
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        // ATE0 gets no prompt: the reader waits its deadline and the resync window, then stops.
        rule.waitUntil(40_000) {
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            rule.onAllNodes(hasText("Stopped by an error: partial results")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE — data read from a connected adapter")
        rule.onNodeWithText("Copy raw capture for review").performScrollTo()
    }
}
