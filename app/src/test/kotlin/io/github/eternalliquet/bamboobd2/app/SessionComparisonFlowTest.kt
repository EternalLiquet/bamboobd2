package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import java.text.DateFormat
import java.util.Date
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SessionComparisonFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun sessionsDir() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        .getApplication<Application>().filesDir.resolve("sessions")

    private fun waitFor(text: String) = rule.waitUntil(5_000) {
        ShadowLooper.idleMainLooper()
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForSavedRow(text: String) {
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            try {
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
                true
            } catch (_: AssertionError) { false }
        }
        rule.onNodeWithText(text).assertExists()
    }
    private fun savedTop() { rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0) }

    private fun record(id: String, start: Long, load: Double) = SessionRecord(
        schemaVersion = 1, id = id, startedAt = start, endedAt = start + 10,
        provenance = Provenance.REPLAY, sourceLabel = "replay:$id", complete = true, outcome = SessionOutcome.COMPLETE,
        dtcKindsRead = DtcKind.entries,
        dtcs = if (id == "first") listOf(Dtc("P0133", DtcKind.STORED, "7E8")) else emptyList(),
        readings = listOf(Reading("04", ReadingResult.Value(load, "%", "7E8"), start + 5)),
    )

    @Test
    fun fewerThanTwoReadableEntriesExplainsWhyComparisonIsUnavailable() {
        sessionsDir().deleteRecursively()
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        rule.onNodeWithText("Choose two sessions").assertIsNotEnabled()
        rule.onNodeWithText("Save at least two readable sessions to compare them.").assertIsDisplayed()
    }

    @Test
    fun explicitSelectionBackReentryAndDisappearingRecordNeverScan() {
        val directory = sessionsDir().also { it.deleteRecursively() }
        val first = record("first", 1_700_000_000_000, 50.0)
        val second = record("second", 1_700_086_400_000, 52.0)
        FileSessionStore(directory).apply { save(first); save(second) }
        val firstDate = DateFormat.getDateTimeInstance().format(Date(first.startedAt))
        val secondDate = DateFormat.getDateTimeInstance().format(Date(second.startedAt))

        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        waitForSavedRow(firstDate)
        savedTop()
        rule.onNodeWithText("Choose two sessions").performClick()
        rule.onNodeWithText("Choose sessions from the same vehicle. The app cannot verify that they are.").assertIsDisplayed()
        waitForSavedRow(firstDate)
        rule.onNodeWithText(firstDate).performClick()
        waitForSavedRow(secondDate)
        rule.onNodeWithText(secondDate).performClick()
        savedTop()
        rule.onNodeWithText("Compare").performClick()
        waitFor("Reported only in first")
        rule.onNodeWithText("First session").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Second session").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reported only in first: P0133", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Second minus first: +2.0 %").performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasText("Read codes")).assertCountEquals(0)
        rule.onNodeWithContentDescription("Back").performClick()

        savedTop()
        rule.onNodeWithText("Choose two sessions").performClick()
        rule.onNodeWithText("First: —", substring = true).assertIsDisplayed()
        waitForSavedRow(firstDate)
        rule.onNodeWithText(firstDate).performClick()
        savedTop()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Choose two sessions").assertIsDisplayed()

        rule.onNodeWithText("Choose two sessions").performClick()
        waitForSavedRow(firstDate)
        rule.onNodeWithText(firstDate).performClick()
        waitForSavedRow(secondDate)
        rule.onNodeWithText(secondDate).performClick()
        assertTrue(directory.resolve("second.1.json").delete())
        savedTop()
        rule.onNodeWithText("Compare").performClick()
        waitFor("no longer readable")
        rule.onNodeWithText("One or both selected sessions are no longer readable. Go back and choose again.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        savedTop()
        rule.onNodeWithText("Choose two sessions").assertIsDisplayed()
    }
}
