package io.github.eternalliquet.bamboobd2.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.ui.PROVENANCE_BANNER_TAG
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowLooper

/**
 * The bench screen under Robolectric (JVM, not a device). The connection test uses
 * Robolectric's shadow socket, which never answers: it shows the real Android
 * socket path ends in "no reply" rather than hanging. No hardware is involved.
 */
@RunWith(RobolectricTestRunner::class)
class BenchFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    // The ViewModel's Application can be an earlier test's under Robolectric, so set state there.
    private val vmApp get() = vm.getApplication<Application>()

    private fun waitForText(text: String, seconds: Long = 5) = rule.waitUntil(seconds * 1_000) {
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openBench() {
        rule.onNodeWithText("Adapter bench check (Bluetooth)").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun deniedPermissionIsExplainedAndOfflineSessionsStillWork() {
        shadowOf(vmApp).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        openBench()
        rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals("ADAPTER BENCH — adapter-only commands, never vehicle requests")
        rule.onNodeWithText("This phone: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})").assertIsDisplayed()
        rule.onNodeWithText("Bluetooth permission is needed to connect to a paired adapter.").performScrollTo().assertIsDisplayed()

        rule.runOnUiThread { vm.onPermissionResult(false) }
        waitForText("Bluetooth permission was denied.")

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("No codes").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
        waitForText("Complete")
    }

    @Test
    fun pairedAdapterThatNeverAnswersEndsAsNoReply() {
        shadowOf(vmApp).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = vmApp.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val paired = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55").also { shadowOf(it).setName("VEEPEAK") }
        shadowOf(adapter).setBondedDevices(setOf(paired))

        openBench()
        waitForText("Paired devices.")
        rule.onNodeWithText("VEEPEAK").performScrollTo().performClick()
        waitForText("Result for VEEPEAK: stopped, no reply", seconds = 15)
        rule.onNodeWithText("ATI: no reply").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("AT@1: not run").performScrollTo().assertIsDisplayed()
    }
}
