package io.github.eternalliquet.bamboobd2.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.*
import io.github.eternalliquet.bamboobd2.core.live.LiveReadState
import io.github.eternalliquet.bamboobd2.core.model.LiveStopReason
import java.io.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class LiveRecordingFlowTest {
    private class Socket : RfcommSocket {
        private val written = ByteArrayOutputStream()
        private val toApp = PipedOutputStream()
        override val input: InputStream = PipedInputStream(toApp, 8192)
        private val replies = mapOf(
            "ATE0" to "ATE0\rOK\r\r>", "ATL0" to "OK\r\r>", "ATH1" to "OK\r\r>", "ATSP0" to "OK\r\r>",
            "0100" to "7E8 06 41 00 18 18 00 00\r\r>", "ATDPN" to "A6\r\r>",
            "03" to "7E8 02 43 00\r\r>", "07" to "7E8 02 47 00\r\r>",
            "0104" to "7E8 03 41 04 80\r\r>", "0105" to "7E8 03 41 05 7B\r\r>",
            "010C" to "7E8 04 41 0C 1A F8\r\r>", "010D" to "7E8 03 41 0D 00\r\r>",
        )
        override fun connect() = Unit
        override val output: OutputStream = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                synchronized(written) { written.write(b) }
                if (b == '\r'.code) {
                    replies[line.toString()]?.let { toApp.write(it.toByteArray(Charsets.US_ASCII)); toApp.flush() }
                    line.setLength(0)
                } else line.append(b.toChar())
            }
        }
        override fun close() { runCatching { toApp.close() } }
        fun writes(): String = synchronized(written) { written.toString(Charsets.US_ASCII.name()) }
    }
    private val socket = Socket()
    @get:Rule(order = 0) val platform = object : ExternalResource() {
        private lateinit var previous: (android.app.Application) -> BluetoothPlatform
        override fun before() {
            previous = BluetoothPlatforms.create
            BluetoothPlatforms.create = { object : BluetoothPlatform {
                override val connectPermission: String? = null
                override fun availability() = BluetoothAvailability.Ready(listOf(BondedDevice("VEEPEAK", "00:11:22:33:44:55")))
                override fun openSocket(address: String): RfcommSocket = socket
            } }
        }
        override fun after() { BluetoothPlatforms.create = previous }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun until(seconds: Long, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < end) { "condition not reached" }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }
    private fun enter() {
        rule.onNodeWithText("Read codes from the car (Bluetooth)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Live recording (up to four readings)").performScrollTo().performClick()
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        until(20) { vm.recording.value is LiveReadState.Ready }
    }
    private fun selectFour() {
        listOf("04", "05", "0C", "0D").forEach { pid ->
            rule.onNodeWithTag("recording_pid_$pid").performScrollTo().performClick()
        }
    }

    @Test fun fourReadingControlsStopWritesAndSavePartial() {
        enter()
        rule.onNodeWithText("Start recording").performScrollTo().assertIsNotEnabled()
        selectFour()
        rule.onNodeWithText("Start recording").performScrollTo().performClick()
        until(15) { (vm.recording.value as? LiveReadState.Recording)?.record?.recording?.samples.orEmpty().size >= 4 }
        rule.onNodeWithText("Pause").performScrollTo().performClick()
        until(10) { vm.recording.value is LiveReadState.Paused }
        val pausedWrites = socket.writes()
        Thread.sleep(650)
        assertEquals(pausedWrites, socket.writes())
        rule.onNodeWithText("Resume").performScrollTo().performClick()
        rule.onNodeWithText("Stop and review").performScrollTo().performClick()
        until(5) { vm.recording.value is LiveReadState.Finished }
        val finalWrites = socket.writes()
        Thread.sleep(650)
        assertEquals(finalWrites, socket.writes())
        val record = (vm.recording.value as LiveReadState.Finished).record
        assertEquals(LiveStopReason.USER, record.recording!!.stopReason)
        assertEquals(listOf("04", "05", "0C", "0D"), record.recording!!.selectedPids)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        until(5) { vm.saveStatus.value[record.id] is SaveStatus.Done }
        assertTrue(vm.recordingPending.value)
    }

    @Test fun backgroundEndsRecordingWithoutReconnectingAndRetainsResult() {
        enter()
        selectFour()
        rule.onNodeWithText("Start recording").performScrollTo().performClick()
        until(10) { (vm.recording.value as? LiveReadState.Recording)?.record?.recording?.samples.orEmpty().isNotEmpty() }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        until(5) { vm.recording.value is LiveReadState.Finished }
        assertEquals(LiveStopReason.CANCELLED, (vm.recording.value as LiveReadState.Finished).record.recording!!.stopReason)
        val before = socket.writes()
        Thread.sleep(650)
        assertEquals(before, socket.writes())
        assertTrue(vm.recordingPending.value)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Recording ended").assertExists()
    }
}
