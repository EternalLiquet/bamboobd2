package io.github.eternalliquet.bamboobd2.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.eternalliquet.bamboobd2.app.ui.SIMULATED_BANNER_TAG
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Robolectric UI flow tests. They run Compose on the JVM, which approximates
 * Android; they are not a device or emulator run.
 */
@RunWith(RobolectricTestRunner::class)
class AppFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearSaved() {
        rule.activity.filesDir.resolve("sessions").deleteRecursively()
    }

    private fun bannerShown() = rule.onNodeWithTag(SIMULATED_BANNER_TAG).assertIsDisplayed()

    private fun runSessionToEnd() {
        // FakeReader steps use main-looper delays; advance Robolectric's clock past all of them.
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
        rule.waitForIdle()
    }

    @Test
    fun completeTwoCodesSessionSavesAndReopensWithBannerOnEveryScreen() {
        bannerShown()
        rule.onNodeWithText("Two codes").performClick()
        rule.onNodeWithText("Start").performClick()
        bannerShown()
        runSessionToEnd()
        rule.onNodeWithText("Complete").assertIsDisplayed()
        rule.onNodeWithText("P0133 (stored)").assertIsDisplayed()
        rule.onNodeWithText("P0171 (pending)").assertIsDisplayed()

        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performScrollTo().performClick()

        rule.onNodeWithText("Saved sessions").performClick()
        bannerShown()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("SIMULATED · fake:two_codes · Complete")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SIMULATED · fake:two_codes · Complete").performClick()
        bannerShown()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("P0133 (stored)")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SIMULATED · fake:two_codes").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        bannerShown()
    }

    @Test
    fun noCodesSessionDoesNotClaimTheCarIsFaultFree() {
        rule.onNodeWithText("No codes").performClick()
        rule.onNodeWithText("Start").performClick()
        runSessionToEnd()
        rule.onNodeWithText("No generic emissions codes reported. This doesn't mean the car has no faults.")
            .assertIsDisplayed()
    }

    @Test
    fun unsupportedPidIsShownAsNotReported() {
        rule.onNodeWithText("Unsupported reading").performClick()
        rule.onNodeWithText("Start").performClick()
        runSessionToEnd()
        rule.onNodeWithText("Not reported by this vehicle").assertIsDisplayed()
    }

    @Test
    fun cancelSavesPartialSessionThatReopensAsCancelled() {
        rule.onNodeWithText("Two codes").performClick()
        rule.onNodeWithText("Start").performClick()
        // Two steps (supported map, stored codes) at 400 ms each, then cancel.
        ShadowLooper.idleMainLooper(900, TimeUnit.MILLISECONDS)
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancelled: partial results").assertIsDisplayed()
        rule.onNodeWithText("P0133 (stored)").assertIsDisplayed()

        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithText("Saved sessions").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("SIMULATED · fake:two_codes · Cancelled: partial results")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
