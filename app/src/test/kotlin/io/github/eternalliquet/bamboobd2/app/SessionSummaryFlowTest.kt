package io.github.eternalliquet.bamboobd2.app

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SessionSummaryFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun clearSaved() {
        rule.activity.filesDir.resolve("sessions").deleteRecursively()
    }

    @Test
    fun finishedAndReopenedRecordPreviewsCopyExactlyAndSharesOnlyOnExplicitPress() {
        rule.onNodeWithText("Two codes").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        rule.onAllNodes(hasText("Preview summary")).assertCountEquals(0)
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
        rule.waitForIdle()
        rule.onNodeWithText("Preview summary").performScrollTo().performClick()
        rule.onNodeWithText("Review before copying or sharing. This text includes the session time and diagnostic results.")
            .assertIsDisplayed()
        rule.onNodeWithText("SIMULATED — synthetic data", substring = true).assertIsDisplayed()
        assertEquals(null, shadowOf(rule.activity).nextStartedActivity)
        rule.onNodeWithText("Copy summary").performClick()
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains("Stored codes: Read"))
        assertTrue(copied.contains("P0133"))
        assertTrue(copied.contains("Pending codes: Read"))
        assertTrue(copied.contains("not a diagnosis"))
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        val entry = "SIMULATED · fake:two_codes · Complete"
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(entry)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(entry).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Preview summary")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Preview summary").performScrollTo().performClick()
        rule.onNodeWithText("Share").performClick()
        val chooser = shadowOf(rule.activity).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT) as Intent
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(copied, send.getStringExtra(Intent.EXTRA_TEXT))
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText(entry).assertIsDisplayed()
    }
}
