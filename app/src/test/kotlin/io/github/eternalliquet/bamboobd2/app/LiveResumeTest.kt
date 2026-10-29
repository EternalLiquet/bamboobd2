package io.github.eternalliquet.bamboobd2.app

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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
        rule.onNodeWithText("Read codes from the car (Bluetooth)").performScrollTo().performClick()
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
}
