package io.github.eternalliquet.bamboobd2.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.app.bluetooth.RfcommSocket
import io.github.eternalliquet.bamboobd2.app.ui.PROVENANCE_BANNER_TAG
import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.session.ReadScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowLooper

/**
 * The one-shot LIVE scopes, without a car or adapter: a scripted fake ELM socket,
 * and Robolectric's shadow socket for the real Android path. JVM, not a device.
 */
@RunWith(RobolectricTestRunner::class)
class LiveReadTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForSavedRow(text: String) {
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            try {
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
                true
            } catch (_: AssertionError) { false }
        }
        rule.onNodeWithText(text).assertExists()
    }

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
        val sensorReplies = carReplies + mapOf(
            "0104" to "7E8 03 41 04 80\r\r>",
            "0105" to "7E8 03 41 05 7B\r\r>",
            "010C" to "7E8 04 41 0C 1A F8\r\r>",
            "010D" to "7E8 03 41 0D 00\r\r>",
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
        assertEquals("CODES_ONLY", record.events.first { it.kind == SessionController.READ_SCOPE }.detail)

        idleUntil(5) { record.id in vm.captures.value }
        val capture = vm.captures.value.getValue(record.id)
        assertTrue(capture, "\"provenance\": \"${CaptureRecorder.RAW_CAPTURE}\"" in capture)
        val requests = Regex(""""request": "([^"]+)"""").findAll(capture).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "03", "07"), requests)
        assertTrue(capture.contains("codes-only (L2) session"))
        // No device identifiers in what is kept or shared.
        val saved = io.github.eternalliquet.bamboobd2.core.storage.SessionCodec.encode(record)
        for (identifier in listOf(device.address, device.name)) {
            assertTrue("$identifier in capture", identifier !in capture)
            assertTrue("$identifier in record", identifier !in saved)
        }
        assertTrue("connection closed after the session", socket.closed.await(5, TimeUnit.SECONDS))
        // With no screen to acknowledge it, the result hand-off stays pending; acknowledging consumes it.
        assertEquals(LiveState.Started, vm.live.value)
        vm.acknowledgeLiveStarted()
        assertTrue(vm.live.value is LiveState.Available)
    }

    @Test
    fun selectedSnapshotRequestsFourConfirmedPidsOnceAndSavesAllValues() {
        val socket = FakeElmSocket(sensorReplies)
        val vm = viewModel(socket)
        vm.startLive(device, ReadScope.FULL)
        idleUntil(20) { vm.session.value is SessionState.Finished }
        val record = (vm.session.value as SessionState.Finished).record
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r0104\r0105\r010C\r010D\r", socket.writtenText())
        assertEquals("FULL", record.events.first { it.kind == SessionController.READ_SCOPE }.detail)
        assertEquals(Provenance.LIVE, record.provenance)
        assertEquals(SessionOutcome.COMPLETE, record.outcome)
        assertEquals(4, record.readings.size)
        val byPid = record.readings.associateBy { it.pid }
        val load = byPid.getValue("04").result as ReadingResult.Value
        assertEquals(50.19607843137255, load.value, 0.000001) // raw 0x80; UI rounds to 50.2 %
        assertEquals("%", load.unit)
        assertEquals("7E8", load.ecu)
        assertEquals(ReadingResult.Value(83.0, "°C", "7E8"), byPid.getValue("05").result)
        assertEquals(ReadingResult.Value(1726.0, "rpm", "7E8"), byPid.getValue("0C").result)
        assertEquals(ReadingResult.Value(0.0, "km/h", "7E8"), byPid.getValue("0D").result)
        assertTrue(record.readings.all { it.atMillis >= record.startedAt && it.atMillis <= record.endedAt!! })
        idleUntil(5) { record.id in vm.captures.value }
        assertTrue(vm.captures.value.getValue(record.id).contains("sensor snapshot"))
        assertTrue(socket.closed.await(5, TimeUnit.SECONDS))

        val store = FileSessionStore(Files.createTempDirectory("snapshot-reopen").toFile())
        store.save(record)
        assertEquals(record, store.load(record.id))

        val activityVm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        val dir = activityVm.getApplication<Application>().filesDir.resolve("sessions").also { it.deleteRecursively() }
        FileSessionStore(dir).save(record)
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        val entry = "LIVE · bluetooth:classic · Complete"
        waitForSavedRow(entry)
        rule.onNodeWithText(entry).performClick()
        rule.onNodeWithText("Sensor snapshot (one pass)").performScrollTo()
        rule.onNodeWithText("50.2 % (7E8)").performScrollTo()
        rule.onAllNodes(hasText("Captured", substring = true)).assertCountEquals(4)
    }

    @Test
    fun partialAndUnknownMapsNeverCauseUnconfirmedSensorRequests() {
        val partial = FakeElmSocket(sensorReplies + ("0100" to "7E8 06 41 00 10 10 00 00\r\r>"))
        val partialVm = viewModel(partial)
        partialVm.startLive(device, ReadScope.FULL)
        idleUntil(20) { partialVm.session.value is SessionState.Finished }
        val partialRecord = (partialVm.session.value as SessionState.Finished).record
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r0104\r010C\r", partial.writtenText())
        assertEquals(ReadingResult.Unsupported, partialRecord.readings.single { it.pid == "05" }.result)
        assertEquals(ReadingResult.Unsupported, partialRecord.readings.single { it.pid == "0D" }.result)

        val unknown = FakeElmSocket(sensorReplies + ("0100" to "NO DATA\r\r>"))
        val unknownVm = viewModel(unknown)
        unknownVm.startLive(device, ReadScope.FULL)
        idleUntil(20) { unknownVm.session.value is SessionState.Finished }
        val unknownRecord = (unknownVm.session.value as SessionState.Finished).record
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r", unknown.writtenText())
        assertEquals(SupportedPids.Unknown, unknownRecord.supportedPids)
        assertTrue(unknownRecord.readings.all { it.result == ReadingResult.UnknownCapability })
        assertFalse(unknownRecord.readings.any { it.result is ReadingResult.Value })
    }

    @Test
    fun liveChoiceStartsCodesOnlyAndExplainsThatSnapshotHasNoVehicleTest() {
        rule.onNodeWithText("Read codes from the car (Bluetooth)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Codes only").assertIsSelected()
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE READ — car codes only, no sensor readings or clearing")
        rule.onNodeWithText("Codes + sensor snapshot").performScrollTo().performClick()
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE READ — codes + one sensor snapshot; no clearing")
        rule.onNodeWithText("Not yet tested on a vehicle", substring = true).assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Read codes from the car (Bluetooth)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Codes only").assertIsSelected()
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE READ — car codes only, no sensor readings or clearing")
    }

    @Test
    fun busInitFailureIsShownWithReasonsAndNeverAsNoCodes() {
        // Every vehicle request ends like the owner's BRZ captures, even after ATSP0.
        val busInitError = "BUS INIT: ...ERROR\r\r>"
        val socket = FakeElmSocket(
            carReplies + mapOf("0100" to busInitError, "ATDPN" to "3\r\r>", "03" to busInitError, "07" to busInitError),
        )
        val live = viewModel(socket)
        live.startLive(device)
        idleUntil(20) { live.session.value is SessionState.Finished }
        val record = (live.session.value as SessionState.Finished).record
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r", socket.writtenText())
        assertTrue(record.readNothing)
        assertTrue(!record.allCodesRead)

        // Save it where the activity's ViewModel reads, then reopen it on screen.
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        val dir = vm.getApplication<Application>().filesDir.resolve("sessions").also { it.deleteRecursively() }
        FileSessionStore(dir).save(record)
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        val entry = "LIVE · bluetooth:classic · Finished, but nothing was read from the car"
        waitForSavedRow(entry)
        rule.onNodeWithText(entry).performClick()
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            rule.onAllNodes(hasText("Adapter protocol: 3: ISO 9141-2 (5 baud init), fixed setting")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Finished, but nothing was read from the car").performScrollTo()
        rule.onNodeWithText("Code list not read (stored: adapter reported BUS INIT: ...ERROR)").performScrollTo()
        rule.onNodeWithText("Code list not read (pending: adapter reported BUS INIT: ...ERROR)").performScrollTo()
        rule.onNodeWithText("Supported-reading map not read: adapter reported BUS INIT: ...ERROR").performScrollTo()
        rule.onAllNodes(hasText("No generic emissions codes reported", substring = true)).assertCountEquals(0)
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

        rule.onNodeWithText("Read codes from the car (Bluetooth)", substring = true).performScrollTo().performClick()
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            rule.onAllNodes(hasText("Paired devices.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE READ — car codes only, no sensor readings or clearing")
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        // ATE0 gets no prompt: the reader waits its deadline and the resync window, then stops.
        rule.waitUntil(40_000) {
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            rule.onAllNodes(hasText("Stopped by an error: nothing was read")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("LIVE — data read from a connected adapter")
        rule.onNodeWithText("Copy raw capture for review").performScrollTo()
    }
}
