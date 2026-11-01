package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The diagnostic log through the real connection owner, transport and screens, with
 * constructed sockets. JVM only: not a phone, adapter or car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h700dp", fontScale = 1.5f)
// Real font measurement; the default legacy mode measures text far too narrow to wrap.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticExportFlowTest {
    private var socket = ScriptedElmSocket(TestCars.cayenne)
    @get:Rule(order = 0) val fakeBluetooth = object : ExternalResource() {
        private val original = BluetoothPlatforms.create
        override fun before() { BluetoothPlatforms.create = { ScannerPlatform { socket } } }
        override fun after() { BluetoothPlatforms.create = original }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun forgetSavedScanner() = forgetActivityScanner(rule)
    private val vm get() = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    /** The activity ViewModel's log directory is shared by the whole run (stale Application); start clean. */
    private fun clearLog() = File(vm.getApplication<Application>().filesDir, "diagnostics").deleteRecursively()

    private fun waitForText(text: String, seconds: Long = 20) = rule.waitUntil(seconds * 1_000) {
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun exportJson(model: AppViewModel = vm): JSONObject = JSONObject(model.diagnostics.export().json!!)

    private fun events(doc: JSONObject, session: Int): List<JSONObject> {
        val array = doc.getJSONArray("sessions").getJSONObject(session).getJSONArray("events")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test fun oneConnectionLogsIdentificationAndAFailedScanAndSurvivesRestart() {
        clearLog()
        // The scan's stored-code request gets a negative reply the decoder rejects, as in the owner's report.
        socket = ScriptedElmSocket(TestCars.cayenne + mapOf("03" to "7E8 03 7F 03\r\r>", "07" to "NO DATA\r\r>"))
        vm.connectForTest()
        assertTrue(vm.startLive())
        idleUntil(20) { vm.session.value is SessionState.Finished }
        idleUntil(5) { !vm.vehicle.leased }
        vm.disconnectVehicle()
        val writes = socket.writes()

        val doc = exportJson()
        val log = events(doc, doc.getJSONArray("sessions").length() - 1)
        val kinds = log.map { it.getString("kind") }
        assertEquals("session_start", kinds.first())
        assertEquals("session_end", kinds.last())
        val operations = log.filter { it.getString("kind") == "operation_start" }.map { it.getString("operation") }
        assertEquals(listOf("identification", "scan"), operations)
        val requests = log.filter { it.getString("kind") == "exchange" }.map { it.getString("request") }
        // Every request the socket received is logged once, in order, and nothing else was sent.
        assertEquals(writes.split('\r').filter { it.isNotEmpty() }, requests)
        val replies = log.filter { it.getString("kind") == "exchange" }.associate { it.getString("request") to it.getString("reply") }
        assertEquals("7E8 03 7F 03\r\r>", replies["03"])
        assertEquals("NO DATA\r\r>", replies["07"])
        assertTrue(log.any { it.getString("category") == "interpretation" && it.getString("text").startsWith("codes_not_read") })

        // Hidden: the VIN (plain and hex-encoded) and the Bluetooth address. Kept: responder IDs.
        val text = vm.diagnostics.export().json!!
        assertFalse(TestCars.CAYENNE_VIN in text)
        assertFalse("57 50 31 41 41" in text)
        assertFalse(TEST_SCANNER.address in text)
        assertTrue("[REDACTED:VIN data" in text)
        assertTrue("manufacturer code WP1 (rest hidden)" in text)

        // A new process reads the same stored log.
        val restarted = AppViewModel(vm.getApplication(), FileSessionStore(Files.createTempDirectory("r").toFile()), Dispatchers.IO, ScannerPlatform { socket })
        assertEquals(doc.getJSONArray("sessions").length(), exportJson(restarted).getJSONArray("sessions").length())
    }

    @Test fun exportExplainsAnEmptyLogThenSharesAndSavesTheFile() {
        clearLog()
        rule.onNodeWithText("Export diagnostic log").performScrollTo().performClick()
        waitForText("No diagnostic log yet")
        rule.onNodeWithText("Done").performClick()

        vm.connectForTest()
        rule.onNodeWithText("Export diagnostic log").performScrollTo().performClick()
        waitForText("VINs and Bluetooth addresses are hidden")
        rule.onNodeWithText("Share…").performClick()
        val chooser = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${rule.activity.packageName}.diagnostics", uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val shared = rule.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val doc = JSONObject(shared)
        assertEquals("bamboobd2-diagnostic-log", doc.getString("format"))
        assertTrue(doc.getJSONObject("app").getString("appVersion").isNotEmpty())
        assertFalse(TestCars.CAYENNE_VIN in shared)

        // Save to file goes through the system document picker.
        rule.onNodeWithText("Export diagnostic log").performScrollTo().performClick()
        waitForText("Save to file…")
        rule.onNodeWithText("Save to file…").performClick()
        val create = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertTrue(create.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("bamboobd2-diagnostic-log-"))
    }

    @Test fun scannerActionsStayOnOneLineOnANarrowScreenWithLargeText() {
        vm.connectForTest()
        waitForText("Forget scanner")
        for (label in listOf("Disconnect", "Change scanner", "Forget scanner")) {
            val node = rule.onNodeWithText(label, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("$label wraps", 1, layouts.single().lineCount)
        }
        assertTrue(vm.connection.value is ConnectionState.Ready)
        System.getenv("BAMBOOBD2_SCREENSHOT")?.let { path ->
            rule.onNodeWithText("Forget scanner").performScrollTo()
            rule.waitForIdle()
            val view = rule.activity.window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            java.io.FileOutputStream(path).use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
