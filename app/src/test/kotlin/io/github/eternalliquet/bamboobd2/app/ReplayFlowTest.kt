package io.github.eternalliquet.bamboobd2.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.eternalliquet.bamboobd2.app.ui.PROVENANCE_BANNER_TAG
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Replay sessions through the real protocol path, under Robolectric (JVM, not a
 * device or emulator). The scripts are the synthetic ones bundled with `:core`.
 */
@RunWith(RobolectricTestRunner::class)
class ReplayFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearSaved() {
        rule.activity.filesDir.resolve("sessions").deleteRecursively()
    }

    private fun banner(text: String) = rule.onNodeWithTag(PROVENANCE_BANNER_TAG).assertTextEquals(text)

    private fun startReplay(label: String) {
        rule.onNodeWithText(label).performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        // Replay timing runs on uptime, which Robolectric advances with the main looper.
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS)
        rule.waitForIdle()
    }

    @Test
    fun replaySessionCompletesSavesAndReopensAsReplay() {
        banner("OFFLINE — no adapter or vehicle connection")
        startReplay("CAN, one stored code")
        banner("REPLAY — scripted adapter data, not a live scan")
        rule.onNodeWithText("Complete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("P0133 (stored)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("83 °C (7E8)").performScrollTo().assertIsDisplayed()

        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("REPLAY · replay:can-complete · Complete")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("REPLAY · replay:can-complete · Complete").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("REPLAY · replay:can-complete")).fetchSemanticsNodes().isNotEmpty() }
        banner("REPLAY — scripted adapter data, not a live scan")
    }

    @Test
    fun scriptedFailureShowsItsStatusAndReasonAndReopensUnchanged() {
        startReplay("No reply to coolant temperature")
        banner("REPLAY — scripted adapter data, not a live scan")
        rule.onNodeWithText("Stopped by an error: partial results").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reason: no_response: no prompt after '0105' (PID 05)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("50.2 % (7E8)").performScrollTo().assertIsDisplayed()

        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        val entry = "REPLAY · replay:no-response · Stopped by an error: partial results"
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(entry)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(entry).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Reason: no_response: no prompt after '0105' (PID 05)")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun disconnectIsShownAsDisconnectedWithoutReconnecting() {
        startReplay("Disconnect during stored codes")
        rule.onNodeWithText("Disconnected: partial results. Not reconnected.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reason: disconnected: during stored codes").performScrollTo().assertIsDisplayed()
    }
}
