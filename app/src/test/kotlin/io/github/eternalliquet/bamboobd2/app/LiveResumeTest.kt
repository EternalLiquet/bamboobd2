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
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.app.bluetooth.RfcommSocket
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.session.ReadScope
import io.github.eternalliquet.bamboobd2.core.session.SessionController
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

    /** An ELM-like socket whose connect() waits for [release], then answers the codes-only sequence. */
    private class GatedElmSocket : RfcommSocket {
        val release = CountDownLatch(1)
        private val written = ByteArrayOutputStream()
        private val toApp = PipedOutputStream()
        override val input: InputStream = PipedInputStream(toApp, 8192)
        private val replies = mapOf(
            "ATE0" to "ATE0\rOK\r\r>",
            "ATL0" to "OK\r\r>",
            "ATH1" to "OK\r\r>",
            "ATSP0" to "OK\r\r>",
            "0100" to "SEARCHING...\r7E8 06 41 00 BE 1F B8 10\r\r>",
            "ATDPN" to "A6\r\r>",
            "03" to "7E8 04 43 01 01 33\r\r>",
            "07" to "7E8 02 47 00\r\r>",
            "0104" to "7E8 03 41 04 80\r\r>",
            "0105" to "7E8 03 41 05 7B\r\r>",
            "010C" to "7E8 04 41 0C 1A F8\r\r>",
            "010D" to "7E8 03 41 0D 00\r\r>",
        )

        override fun connect() {
            if (!release.await(30, TimeUnit.SECONDS)) throw IOException("test never released connect")
        }

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

        override fun close() {
            release.countDown()
            runCatching { toApp.close() }
        }

        fun writtenText() = synchronized(written) { written.toString(Charsets.US_ASCII.name()) }
    }

    private val socket = GatedElmSocket()

    // Before the activity (and its ViewModel) is created, route Bluetooth to the fake socket.
    @get:Rule(order = 0)
    val fakeBluetooth = object : ExternalResource() {
        private val original = BluetoothPlatforms.create
        override fun before() {
            BluetoothPlatforms.create = {
                object : BluetoothPlatform {
                    override val connectPermission: String? = null
                    override fun availability() = BluetoothAvailability.Ready(listOf(BondedDevice("VEEPEAK", "00:11:22:33:44:55")))
                    override fun openSocket(address: String): RfcommSocket = socket
                }
            }
        }
        override fun after() {
            BluetoothPlatforms.create = original
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    private fun idleUntil(seconds: Long, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < end) { "condition not met in ${seconds}s" }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    private fun waitForText(text: String) = rule.waitUntil(10_000) {
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** Starts a read, backgrounds the app while connecting, and lets the read finish while stopped. */
    private fun readFinishesWhileStopped(): AppViewModel {
        rule.onNodeWithText("Read codes from the car (Bluetooth)", substring = true).performScrollTo().performClick()
        waitForText("Paired devices.")
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        waitForText("Connecting to VEEPEAK")
        val model = vm

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        socket.release.countDown()
        idleUntil(20) { model.session.value is SessionState.Finished }
        idleUntil(5) { model.captures.value.isNotEmpty() }
        // The hand-off waits, unconsumed, for the screen to come back.
        assertEquals(LiveState.Started, model.live.value)
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
        assertTrue(model.live.value is LiveState.Available)
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r", socket.writtenText())
    }

    @Test
    fun theResultSurvivesRecreationAndIsShownOnlyOnce() {
        readFinishesWhileStopped()
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitForText("Copy raw capture for review")
        assertTrue(vm.live.value is LiveState.Available)
        // Recreating again after the hand-off is consumed keeps the session screen, with no second navigation.
        rule.activityRule.scenario.recreate()
        waitForText("Copy raw capture for review")

        rule.onNodeWithText("Done").performScrollTo().performClick()
        waitForText("Start an offline session")
        repeat(10) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }
        assertEquals(0, rule.onAllNodes(hasText("Copy raw capture for review")).fetchSemanticsNodes().size)
        assertTrue(vm.session.value is SessionState.Idle)
    }

    @Test
    fun selectedSnapshotCannotChangeWhileConnectingOrBeReplacedWhilePending() {
        rule.onNodeWithText("Read codes from the car (Bluetooth)", substring = true).performScrollTo().performClick()
        waitForText("Paired devices.")
        rule.onNodeWithText("Codes + sensor snapshot").performScrollTo().performClick()
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        waitForText("Connecting to VEEPEAK")
        rule.onNodeWithText("Codes only").assertIsNotEnabled()
        val model = vm
        rule.runOnUiThread { model.startLive(BondedDevice("VEEPEAK", "00:11:22:33:44:55"), ReadScope.CODES_ONLY) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        socket.release.countDown()
        idleUntil(20) { model.session.value is SessionState.Finished }
        val record = (model.session.value as SessionState.Finished).record
        assertEquals("FULL", record.events.first { it.kind == SessionController.READ_SCOPE }.detail)
        assertEquals("ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r0104\r0105\r010C\r010D\r", socket.writtenText())
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
        rule.runOnUiThread { model.startLive(BondedDevice("VEEPEAK", "00:11:22:33:44:55")) }
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
        rule.onNodeWithText("Start an offline session").assertExists()
        assertEquals(liveId, (model.session.value as SessionState.Finished).record.id)

        // The original result opens, and can be saved and its capture copied.
        rule.onNodeWithText("Open live result").performScrollTo().performClick()
        waitForText("Copy raw capture for review")
        assertEquals(liveId, (model.session.value as SessionState.Finished).record.id)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        waitForText("Saved")
        assertTrue(model.live.value is LiveState.Available)
    }

    @Test
    fun discardingAPendingLiveResultClearsItAndAllowsOtherSessions() {
        val (model, _) = pendingLiveResultOnHome()
        rule.onNodeWithText("Discard live result").performScrollTo().performClick()
        idleUntil(5) { model.session.value is SessionState.Idle }
        assertTrue(model.live.value is LiveState.Available)
        assertEquals(0, rule.onAllNodes(hasText("A live read hasn't been opened yet")).fetchSemanticsNodes().size)
        rule.onNodeWithText("Start").performScrollTo().assertIsEnabled()
    }
}
