package io.github.eternalliquet.bamboobd2.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.*
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.core.live.LiveReadState
import io.github.eternalliquet.bamboobd2.core.model.LiveStopReason
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.io.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class LiveRecordingFlowTest {
    private fun newSocket(gated: Boolean = false) = ScriptedElmSocket(mapOf(
        "ATE0" to "ATE0\rOK\r\r>", "ATL0" to "OK\r\r>", "ATH1" to "OK\r\r>", "ATSP0" to "OK\r\r>",
        "0100" to "7E8 06 41 00 18 1C 00 00\r\r>", "ATDPN" to "A6\r\r>", "0900" to "NO DATA\r\r>",
        "03" to "7E8 02 43 00\r\r>", "07" to "7E8 02 47 00\r\r>",
        "0104" to "7E8 03 41 04 80\r\r>", "0105" to "7E8 03 41 05 7B\r\r>",
        "010C" to "7E8 04 41 0C 1A F8\r\r>", "010D" to "7E8 03 41 0D 00\r\r>",
    ), gatedConnect = gated)
    private var socket = newSocket()
    @get:Rule(order = 0) val platform = object : ExternalResource() {
        private lateinit var previous: (android.app.Application) -> BluetoothPlatform
        override fun before() {
            previous = BluetoothPlatforms.create
            BluetoothPlatforms.create = { ScannerPlatform { socket } }
        }
        override fun after() { BluetoothPlatforms.create = previous }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun forgetSavedScanner() = forgetActivityScanner(rule)
    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun until(seconds: Long, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < end) { "condition not reached" }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }
    private fun assertWritesStayUnchanged(expected: String) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650)
        do {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            assertEquals(expected, socket.writes())
            Thread.sleep(5)
        } while (System.nanoTime() < end)
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        assertEquals(expected, socket.writes())
    }
    /** Home → choose scanner → identified → Scan → recording, all through the real screens. */
    private fun enter() {
        rule.onNodeWithText("Choose scanner").performScrollTo().performClick()
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        until(20) { vm.connection.value is ConnectionState.Ready }
        rule.onNodeWithText("Scan this car").performScrollTo().performClick()
        rule.onNodeWithText("Live recording (up to four readings)").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
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
        rule.onNodeWithTag("recording_pid_06").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("recording_pid_0E").performScrollTo().assertIsEnabled()
        selectFour()
        rule.onNodeWithTag("recording_pid_0E").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Start recording").performScrollTo().performClick()
        until(15) { (vm.recording.value as? LiveReadState.Recording)?.record?.recording?.samples.orEmpty().size >= 4 }
        rule.onNodeWithText("Pause").performScrollTo().performClick()
        until(10) { vm.recording.value is LiveReadState.Paused }
        val pausedWrites = socket.writes()
        val pausedId = (vm.recording.value as LiveReadState.Paused).record.id
        rule.activityRule.scenario.recreate()
        assertEquals(pausedId, (vm.recording.value as LiveReadState.Paused).record.id)
        assertWritesStayUnchanged(pausedWrites)
        rule.onNodeWithText("Resume").performScrollTo().performClick()
        rule.onNodeWithText("Stop and review").performScrollTo().performClick()
        until(5) { vm.recording.value is LiveReadState.Finished }
        val finalWrites = socket.writes()
        assertWritesStayUnchanged(finalWrites)
        val record = (vm.recording.value as LiveReadState.Finished).record
        assertEquals(LiveStopReason.USER, record.recording!!.stopReason)
        assertEquals(listOf("04", "05", "0C", "0D"), record.recording!!.selectedPids)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        until(5) { vm.saveStatus.value[record.id] is SaveStatus.Done }
        assertTrue((vm.saveStatus.value[record.id] as SaveStatus.Done).result is SaveResult.Saved)
        assertTrue(vm.recordingPending.value)
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        until(5) { vm.saved.value.filterIsInstance<StoredSession.Readable>().any { it.record.id == record.id } }
        val savedDate = DateFormat.getDateTimeInstance().format(Date(record.startedAt))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(savedDate))
        rule.onNodeWithText(savedDate).performClick()
        until(5) { rule.onAllNodes(hasText("Saved recording · elapsed", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("All recorded").performScrollTo().performClick()
        rule.onAllNodes(hasText("Inspect history", substring = true))[0].performScrollTo().performClick()
        rule.onNodeWithText("Value:", substring = true).performScrollTo().assertIsDisplayed()
        assertWritesStayUnchanged(finalWrites)
    }

    @Test fun cancellingANewConnectionAfterACompletedRecordingClearsOnlyTheNewAttempt() {
        enter()
        rule.runOnUiThread { vm.stopRecording(); vm.discardPendingRecording() }
        until(5) { vm.recording.value is LiveReadState.Finished && !vm.vehicle.leased }
        // The recording ended; the vehicle connection did not.
        assertFalse(socket.isClosed)
        val oldId = (vm.recording.value as LiveReadState.Finished).record.id
        listOf<() -> Unit>({ vm.disconnectVehicle() }, { vm.onAppBackgrounded() }).forEach { cancel ->
            rule.runOnUiThread { vm.disconnectVehicle(); vm.onAppForegrounded() }
            socket = newSocket(gated = true)
            rule.runOnUiThread { assertTrue(vm.connectVehicle()) }
            until(5) { socket.connectEntered.count == 0L }
            rule.runOnUiThread { cancel() }
            assertFalse(vm.recordingPending.value)
            until(5) { socket.isClosed }
            assertTrue(vm.connection.value is ConnectionState.Disconnected)
            assertEquals(oldId, (vm.recording.value as LiveReadState.Finished).record.id)
            rule.runOnUiThread { assertFalse(vm.startRecording()); vm.refreshLive() }
            assertTrue(socket.writes().isEmpty())
        }
        rule.runOnUiThread { vm.onAppForegrounded() }
        rule.onNodeWithText("Recording setup ended.", substring = true).assertExists()
        rule.onNodeWithText("Recording ended").assertDoesNotExist()
        rule.onNodeWithText("Preparing…").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Practice without a car").performScrollTo().assertExists()
        rule.runOnUiThread { assertTrue(vm.start(io.github.eternalliquet.bamboobd2.core.reader.FakeScenario.NO_CODES)) }
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
        assertWritesStayUnchanged(before)
        assertTrue(vm.recordingPending.value)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Recording ended").assertExists()
    }

    @Test fun disconnectWhileReadyEndsThePreparedSessionWithoutStartingPolls() {
        enter()
        val before = socket.writes()
        socket.disconnect()
        until(5) { vm.recording.value is LiveReadState.Finished && socket.closed.count == 0L }
        val record = (vm.recording.value as LiveReadState.Finished).record
        assertEquals(LiveStopReason.DISCONNECTED, record.recording!!.stopReason)
        assertTrue(record.recording!!.samples.isEmpty())
        assertTrue(vm.recordingPending.value)
        rule.onNodeWithText("Recording ended").assertExists()
        rule.onNodeWithText("Start recording").assertDoesNotExist()
        assertWritesStayUnchanged(before)
    }

    @Test fun disconnectWhilePausedRetainsSamplesAndCannotResumeTheEndedConnection() {
        enter()
        selectFour()
        rule.onNodeWithText("Start recording").performScrollTo().performClick()
        until(10) { (vm.recording.value as? LiveReadState.Recording)?.record?.recording?.samples.orEmpty().isNotEmpty() }
        rule.onNodeWithText("Pause").performScrollTo().performClick()
        until(5) { vm.recording.value is LiveReadState.Paused }
        val paused = (vm.recording.value as LiveReadState.Paused).record
        val before = socket.writes()
        socket.disconnect()
        until(5) { vm.recording.value is LiveReadState.Finished && socket.closed.count == 0L }
        val ended = (vm.recording.value as LiveReadState.Finished).record
        assertEquals(paused.id, ended.id)
        assertEquals(paused.recording!!.samples, ended.recording!!.samples)
        assertEquals(LiveStopReason.DISCONNECTED, ended.recording!!.stopReason)
        rule.runOnUiThread { assertFalse(vm.resumeRecording()) }
        assertTrue(vm.recordingPending.value)
        rule.onNodeWithText("Resume").assertDoesNotExist()
        assertWritesStayUnchanged(before)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        until(5) { vm.saveStatus.value[ended.id] is SaveStatus.Done }
        assertTrue((vm.saveStatus.value[ended.id] as SaveStatus.Done).result is io.github.eternalliquet.bamboobd2.core.storage.SaveResult.Saved)
    }
}
