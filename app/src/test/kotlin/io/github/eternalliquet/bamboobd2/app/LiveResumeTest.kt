package io.github.eternalliquet.bamboobd2.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.session.ReadScope
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * A live read that finishes while the app is in the background must still be
 * reachable on return, and shown only once. Fake socket, Robolectric (JVM, not a device).
 */
@RunWith(RobolectricTestRunner::class)
class LiveResumeTest {

    // This lifecycle fixture implements exactly these replies; Mode 09 is unanswered.
    private val socket = ScriptedElmSocket(TestCars.setup + mapOf(
        "0100" to "SEARCHING...\r7E8 06 41 00 18 18 00 00\r\r>",
        "0900" to "NO DATA\r\r>",
        "03" to "7E8 04 43 01 01 33\r\r>",
        "07" to "7E8 02 47 00\r\r>",
        "0104" to "7E8 03 41 04 80\r\r>",
        "0105" to "7E8 03 41 05 7B\r\r>",
        "010C" to "7E8 04 41 0C 1A F8\r\r>",
        "010D" to "7E8 03 41 0D 00\r\r>",
    ), hold = setOf("07"))

    // Before the activity (and its ViewModel) is created, route Bluetooth to the fake socket.
    @get:Rule(order = 0)
    val fakeBluetooth = object : ExternalResource() {
        private val original = BluetoothPlatforms.create
        override fun before() {
            BluetoothPlatforms.create = { ScannerPlatform { socket } }
        }
        override fun after() {
            BluetoothPlatforms.create = original
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun forgetSavedScanner() = forgetActivityScanner(rule)

    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]


    private fun waitForText(text: String) = rule.waitUntil(10_000) {
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** Connects, starts a codes read, backgrounds the app mid-read, and lets the read finish while stopped. */
    private fun readFinishesWhileStopped(): AppViewModel {
        val model = vm
        model.connectForTest()
        rule.onNodeWithText("Scan this car").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        // The read waits for its pending-codes reply (held by the socket) when the app stops.
        idleUntil(10) { socket.writes().endsWith("03\r07\r") }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        socket.release.countDown()
        idleUntil(20) { model.session.value is SessionState.Finished }
        idleUntil(5) { model.captures.value.isNotEmpty() }
        // The hand-off waits, unconsumed, for the screen to come back.
        assertEquals(LiveState.Started, model.live.value)
        // Backgrounded: once the read released it, the connection closed. Nothing reconnects.
        idleUntil(5) { socket.isClosed }
        return model
    }

    @Test
    fun aReadThatFinishesInTheBackgroundIsShownOnResume() {
        val model = readFinishesWhileStopped()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitForText("Copy raw capture for review")
        rule.onNodeWithText("Complete").performScrollTo()
        rule.onNodeWithText("P0133 (stored)").performScrollTo()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        waitForText("Saved")
        assertEquals(LiveState.Idle, model.live.value)
        assertEquals(TestCars.IDENTIFY_NO_VIN + "ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r", socket.writes())
        assertTrue(model.connection.value is ConnectionState.Disconnected)
    }

    @Test
    fun theResultSurvivesRecreationAndIsShownOnlyOnce() {
        readFinishesWhileStopped()
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitForText("Copy raw capture for review")
        assertEquals(LiveState.Idle, vm.live.value)
        // Recreating again after the hand-off is consumed keeps the session screen, with no second navigation.
        rule.activityRule.scenario.recreate()
        waitForText("Copy raw capture for review")

        rule.onNodeWithText("Done").performScrollTo().performClick()
        waitForText("Practice without a car")
        repeat(10) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }
        assertEquals(0, rule.onAllNodes(hasText("Copy raw capture for review")).fetchSemanticsNodes().size)
        assertTrue(vm.session.value is SessionState.Idle)
    }

    @Test
    fun selectedSnapshotCannotBeReplacedWhileRunningOrPending() {
        val model = vm
        model.connectForTest()
        rule.onNodeWithText("Scan this car").performScrollTo().performClick()
        rule.onNodeWithText("Codes + sensor snapshot").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        idleUntil(10) { socket.writes().endsWith("03\r07\r") }
        // The connection has one user: a second scan cannot start while this one runs.
        rule.runOnUiThread { assertFalse(model.startLive(ReadScope.CODES_ONLY)); assertFalse(model.startRecording()) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        socket.release.countDown()
        idleUntil(20) { model.session.value is SessionState.Finished }
        val record = (model.session.value as SessionState.Finished).record
        assertEquals("FULL", record.events.first { it.kind == SessionController.READ_SCOPE }.detail)
        assertEquals(record.events.toString(), TestCars.IDENTIFY_NO_VIN + "ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r0104\r0105\r010C\r010D\r", socket.writes())
        assertEquals(SessionOutcome.COMPLETE, record.outcome)
        assertEquals(listOf("04", "05", "0C", "0D"), record.readings.map { it.pid })
        assertTrue(record.readings.all { it.result is ReadingResult.Value })
        assertEquals(LiveState.Started, model.live.value)
        assertFalse(model.start(FakeScenario.NO_CODES))
        assertFalse(model.startReplay("can-complete"))
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForText("Copy raw capture for review")
        assertEquals(record.id, (model.session.value as SessionState.Finished).record.id)
    }

    /** Home showing with an unacknowledged live result: the state Back leaves in the frame before navigation. */
    private fun pendingLiveResultOnHome(): Pair<AppViewModel, String> {
        val model = vm
        socket.release.countDown()
        model.connectForTest()
        assertTrue(model.startLive())
        idleUntil(20) { model.session.value is SessionState.Finished }
        idleUntil(5) { model.captures.value.isNotEmpty() }
        assertEquals(LiveState.Started, model.live.value)
        waitForText("A live read hasn't been opened yet")
        return model to (model.session.value as SessionState.Finished).record.id
    }

    @Test
    fun otherStartsCannotOverwriteAPendingLiveResultAndItStaysOpenable() {
        val (model, liveId) = pendingLiveResultOnHome()

        // Every other session entry point refuses, and nothing navigates.
        assertFalse(model.start(FakeScenario.NO_CODES))
        assertFalse(model.startReplay("can-complete"))
        rule.onNodeWithText("No codes").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Practice without a car").performScrollTo().assertExists()
        assertEquals(liveId, (model.session.value as SessionState.Finished).record.id)

        // The original result opens, and can be saved and its capture copied.
        rule.onNodeWithText("Open live result").performScrollTo().performClick()
        waitForText("Copy raw capture for review")
        assertEquals(liveId, (model.session.value as SessionState.Finished).record.id)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        waitForText("Saved")
        assertEquals(LiveState.Idle, model.live.value)
    }

    @Test
    fun discardingAPendingLiveResultClearsItAndAllowsOtherSessions() {
        val (model, _) = pendingLiveResultOnHome()
        rule.onNodeWithText("Discard live result").performScrollTo().performClick()
        idleUntil(5) { model.session.value is SessionState.Idle }
        assertEquals(LiveState.Idle, model.live.value)
        assertEquals(0, rule.onAllNodes(hasText("A live read hasn't been opened yet")).fetchSemanticsNodes().size)
        rule.onNodeWithText("Start").performScrollTo().assertIsEnabled()
    }
}
