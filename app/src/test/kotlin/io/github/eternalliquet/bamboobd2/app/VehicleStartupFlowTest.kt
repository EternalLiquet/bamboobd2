package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.RfcommSocket
import io.github.eternalliquet.bamboobd2.app.connection.Blocker
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.app.connection.VehicleStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.MakeEvidence
import io.github.eternalliquet.bamboobd2.core.manufacturer.porsche.Porsche
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * The integrated startup flow through the real screens and Bluetooth wiring, with
 * constructed BRZ/Cayenne sockets. JVM only: not a phone, adapter or car.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleStartupFlowTest {
    private val sockets = ArrayDeque<RfcommSocket>()
    private val opened = mutableListOf<RfcommSocket>()
    private var availability: BluetoothAvailability = BluetoothAvailability.Ready(listOf(TEST_SCANNER))

    @get:Rule(order = 0) val fakeBluetooth = object : ExternalResource() {
        private val original = BluetoothPlatforms.create
        override fun before() {
            BluetoothPlatforms.create = {
                object : io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform {
                    override val connectPermission: String? = null
                    override fun availability() = availability
                    override fun openSocket(address: String): RfcommSocket = sockets.removeFirst().also { opened += it }
                }
            }
        }
        override fun after() { BluetoothPlatforms.create = original }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun forgetSavedScanner() = forgetActivityScanner(rule)
    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    private fun waitForText(text: String, seconds: Long = 20) = rule.waitUntil(seconds * 1_000) {
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun context() = ((vm.connection.value as ConnectionState.Ready).vehicle as VehicleStatus.Known).context

    @Test fun firstRunSetupIdentifiesAndConfirmationTouchesNoTransport() {
        val car = ScriptedElmSocket(TestCars.cayenne).also { sockets += it }
        rule.onNodeWithText("Set up your scanner").assertExists()
        rule.onNodeWithText("Choose scanner").performClick()
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        waitForText("Porsche · model not confirmed")
        rule.onNodeWithText("Porsche · from the VIN").assertExists()
        rule.onNodeWithText("2014 · from the VIN").assertDoesNotExist()
        rule.onNodeWithText("WP1" + "•".repeat(14) + " · hidden, not saved").assertExists()
        rule.onNodeWithText("Porsche features").performScrollTo()
        rule.onNodeWithText("Needs model confirmation").performScrollTo()
        assertEquals(TestCars.IDENTIFY_WITH_VIN, car.writes())

        rule.onNodeWithText("Confirm model").performScrollTo().performClick()
        rule.onNodeWithText("Porsche Cayenne (92A, second generation)").performClick()
        rule.onNodeWithText("Done").performClick()
        waitForText("Cayenne (92A, second generation) · you confirmed")
        rule.onNodeWithText("Research only").performScrollTo()
        rule.onNodeWithText("Open experiment").performScrollTo()
        // Oil, intermediate and main inspection are listed separately, all unavailable.
        rule.onNodeWithText("Oil service counter").performScrollTo()
        rule.onNodeWithText("Intermediate inspection counter").performScrollTo()
        rule.onNodeWithText("Main inspection counter").performScrollTo()
        // Selecting the service and its capabilities sent nothing.
        repeat(10) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) }
        assertEquals(TestCars.IDENTIFY_WITH_VIN, car.writes())

        // Activity recreation keeps the connection and starts no second one.
        rule.activityRule.scenario.recreate()
        waitForText("Porsche Cayenne (92A, second generation)")
        assertEquals(1, opened.size)

        // A new process start (new ViewModel) tries the saved scanner exactly once.
        val relaunch = ScriptedElmSocket(TestCars.cayenne).also { sockets += it }
        // Same Application (and so the same saved scanner) as the activity's ViewModel.
        val app = vm.getApplication<Application>()
        val fresh = AppViewModel(app, FileSessionStore(Files.createTempDirectory("relaunch").toFile()), Dispatchers.IO, BluetoothPlatforms.create(app))
        fresh.onAppForegrounded()
        fresh.onAppForegrounded()
        idleUntil(20) { fresh.connection.value is ConnectionState.Ready }
        assertEquals(2, opened.size)
        assertEquals(TestCars.IDENTIFY_WITH_VIN, relaunch.writes())
        // It starts unidentified: the earlier model confirmation is not remembered.
        assertNull(((fresh.connection.value as ConnectionState.Ready).vehicle as VehicleStatus.Known).context.profile)
        fresh.disconnectVehicle()
    }

    @Test fun movingTheScannerBetweenCarsDiscardsTheOldVehicleBothWays() {
        val cayenne = ScriptedElmSocket(TestCars.cayenne).also { sockets += it }
        vm.connectForTest()
        vm.confirmProfile(Porsche.CAYENNE_92A.id)
        waitForText("Porsche Cayenne (92A, second generation)")

        // Unplug from the Cayenne, plug into the BRZ: the link drops, then the user connects again.
        val brz = ScriptedElmSocket(TestCars.brz).also { sockets += it }
        cayenne.disconnect()
        waitForText("The scanner connection ended.")
        assertTrue(cayenne.isClosed)
        rule.onNodeWithText("Porsche features").assertDoesNotExist()
        rule.onNodeWithText("Connect").performScrollTo().performClick()
        waitForText("Subaru · model not confirmed")
        assertEquals("Subaru", context().service!!.make)
        assertTrue(context().capabilities.isEmpty())
        rule.onNodeWithText("Subaru features").performScrollTo()
        rule.onNodeWithText("No Subaru-specific features yet. Generic OBD scans work for this car.").assertExists()
        rule.onNodeWithText("Porsche features").assertDoesNotExist()
        assertNull(vm.experimentAdapter())
        assertEquals(TestCars.IDENTIFY_WITH_VIN, brz.writes())

        // And back to the Cayenne: unidentified model again, no BRZ state.
        val again = ScriptedElmSocket(TestCars.cayenne).also { sockets += it }
        rule.onNodeWithText("Disconnect").performScrollTo().performClick()
        assertTrue(brz.isClosed)
        rule.onNodeWithText("Connect").performScrollTo().performClick()
        waitForText("Porsche · model not confirmed")
        assertTrue(context().make is MakeEvidence.FromVin)
        assertNull(context().profile)
        assertEquals(TestCars.IDENTIFY_WITH_VIN, again.writes())
    }

    @Test fun unknownCarKeepsGenericScanning() {
        val car = ScriptedElmSocket(TestCars.unidentified).also { sockets += it }
        vm.connectForTest()
        waitForText("Car not identified")
        rule.onNodeWithText("Generic scans still work.").assertExists()
        rule.onNodeWithText("Choose vehicle").performScrollTo()
        rule.onAllNodes(hasText("features", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onNodeWithText("Scan this car").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Connected to VEEPEAK · Car not identified").assertExists()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        waitForText("Copy raw capture for review")
        assertTrue(vm.session.value is SessionState.Finished)
        assertEquals(TestCars.IDENTIFY_NO_VIN + "ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r03\r07\r", car.writes())
    }

    @Test fun aLateIdentificationReplyCannotReachTheNextConnection() {
        // The Cayenne's VIN reply is held back; the user cancels and connects to the BRZ.
        val slow = ScriptedElmSocket(TestCars.cayenne, hold = setOf("0902")).also { sockets += it }
        val brz = ScriptedElmSocket(TestCars.brz).also { sockets += it }
        assertTrue(vm.chooseScanner(TEST_SCANNER))
        idleUntil(10) { slow.writes().endsWith("0902\r") }
        assertTrue(vm.connection.value is ConnectionState.Identifying)
        rule.onNodeWithText("Identifying the car…").assertExists()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue(slow.isClosed)
        assertTrue(vm.connection.value is ConnectionState.Disconnected)
        assertTrue(vm.connectVehicle())
        idleUntil(20) { vm.connection.value is ConnectionState.Ready }
        slow.release.countDown()
        repeat(20) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS); Thread.sleep(5) }
        assertEquals("Subaru", context().make.entryOrNull!!.make)
        assertEquals(TestCars.IDENTIFY_WITH_VIN, brz.writes())
    }

    @Test fun vinTimeoutClosesConnectionBeforeNextScanAndCapture() {
        val slow = ScriptedElmSocket(TestCars.cayenne, hold = setOf("0902")).also { sockets += it }
        assertTrue(vm.chooseScanner(TEST_SCANNER))
        idleUntil(10) { slow.writes().endsWith("0902\r") }
        idleUntil(45, "identification timeout") { vm.connection.value !is ConnectionState.Identifying }
        assertTrue("Timed-out identification must close its socket", slow.isClosed)
        assertTrue(vm.connection.value is ConnectionState.Disconnected)
        assertFalse(vm.startLive())
        val clean = ScriptedElmSocket(TestCars.unidentified).also { sockets += it }
        assertTrue(vm.connectVehicle())
        idleUntil(20) { vm.connection.value is ConnectionState.Ready }
        slow.release.countDown()
        assertTrue(vm.startLive())
        idleUntil(20) { vm.session.value is SessionState.Finished }
        idleUntil(5) { vm.captures.value.isNotEmpty() }
        val capture = vm.captures.value.values.last()
        assertFalse(capture.contains("0902"))
        assertFalse(capture.contains("57 50 31")) // synthetic WP1 VIN bytes
        assertEquals(TestCars.IDENTIFY_WITH_VIN, slow.writes())
        assertTrue(clean.writes().endsWith("03\r07\r"))
    }

    @Test fun cancelledScanClosesItsLeaseBeforeAnotherOperation() {
        val car = ScriptedElmSocket(TestCars.unidentified, hold = setOf("03")).also { sockets += it }
        vm.connectForTest()
        assertTrue(vm.startLive())
        idleUntil(10) { car.writes().endsWith("03\r") }
        vm.cancelLiveConnect()
        idleUntil(10) { vm.session.value is SessionState.Finished }
        idleUntil(5) { vm.captures.value.isNotEmpty() }
        assertTrue("Cancellation leaves an unfinished exchange; close the socket", car.isClosed)
        assertTrue(vm.connection.value is ConnectionState.Disconnected)
    }

    @Test fun unavailableSavedScannerAndForget() {
        sockets += ScriptedElmSocket(TestCars.brz)
        vm.connectForTest()
        vm.disconnectVehicle()
        // The scanner is unpaired in Android settings.
        availability = BluetoothAvailability.Ready(emptyList())
        rule.onNodeWithText("Connect").performScrollTo().performClick()
        waitForText("VEEPEAK isn't paired")
        assertEquals(Blocker.NOT_PAIRED, (vm.connection.value as ConnectionState.Blocked).blocker)
        availability = BluetoothAvailability.Off
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        waitForText("Bluetooth is off")
        rule.onNodeWithText("Forget scanner").performScrollTo().performClick()
        waitForText("Set up your scanner")
        assertEquals(ConnectionState.NotSetUp, vm.connection.value)
        assertEquals(1, opened.size)
        assertFalse(vm.connectVehicle())
    }
}
