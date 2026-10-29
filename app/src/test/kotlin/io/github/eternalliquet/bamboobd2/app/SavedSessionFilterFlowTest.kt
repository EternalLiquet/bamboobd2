package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import java.text.DateFormat
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SavedSessionFilterFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun sessionsDir() = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        .getApplication<Application>().filesDir.resolve("sessions")

    private fun waitFor(text: String) = rule.waitUntil(5_000) {
        ShadowLooper.idleMainLooper()
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun record(id: String, at: Long, source: Provenance, code: String?) = SessionRecord(
        schemaVersion = 1, id = id, startedAt = at, endedAt = at + 10,
        provenance = source, sourceLabel = "label P0133", complete = true, outcome = SessionOutcome.COMPLETE,
        dtcKindsRead = DtcKind.entries,
        dtcs = code?.let { listOf(Dtc(it, DtcKind.STORED, "7E8")) } ?: emptyList(),
    )

    private fun bytes() = sessionsDir().listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
    private fun scrollTo(index: Int) = rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)

    @Test fun filtersInvalidatePickerAndPreserveSavedBytesThroughCompareOpenAndRecreation() {
        val directory = sessionsDir().also { it.deleteRecursively() }
        val first = record("first", 1_700_000_000_000, Provenance.REPLAY, "P0133")
        val second = record("second", 1_700_086_400_000, Provenance.REPLAY, "P0171")
        val live = record("live", 1_700_172_800_000, Provenance.LIVE, "P0133")
        FileSessionStore(directory).apply { save(first); save(second); save(live) }
        directory.resolve("broken.1.json").writeText("not JSON")
        val before = bytes()
        val firstDate = DateFormat.getDateTimeInstance().format(Date(first.startedAt))
        val secondDate = DateFormat.getDateTimeInstance().format(Date(second.startedAt))

        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodes(hasText("3 readable matches")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction()).performTextInput("p01")
        rule.onNodeWithText("Source: All").performClick()
        rule.onNodeWithText("REPLAY").performClick()
        rule.onNodeWithText("2 readable matches").assertIsDisplayed()
        scrollTo(3)
        rule.onNodeWithText("Unreadable file broken.1.json", substring = true).assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        scrollTo(0)
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodes(hasText("Source: REPLAY")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("2 readable matches").assertIsDisplayed()

        rule.onNodeWithText("Choose two sessions").performScrollTo().performClick()
        scrollTo(2)
        rule.onNodeWithText(firstDate).performClick()
        scrollTo(0)
        rule.onNode(hasSetTextAction()).performTextInput("3")
        rule.onNodeWithText("Choose two sessions").performScrollTo().assertIsNotEnabled()
        rule.onAllNodes(hasText("Compare")).assertCountEquals(0)
        rule.onNodeWithText("Clear filters").performScrollTo().performClick()
        rule.onNodeWithText("3 readable matches").assertIsDisplayed()
        rule.onNodeWithText("Choose two sessions").performScrollTo().assertIsDisplayed().performClick()
        scrollTo(1)
        rule.onNodeWithText(DateFormat.getDateTimeInstance().format(Date(live.startedAt))).performClick()
        scrollTo(0)
        rule.onNodeWithText("Source: All").performClick()
        rule.onNodeWithText("LIVE").performClick()
        rule.onNodeWithText("Choose two sessions").assertIsNotEnabled()
        rule.onAllNodes(hasText("Compare")).assertCountEquals(0)
        rule.onNodeWithText("Clear filters").performClick()

        rule.onNode(hasSetTextAction()).performTextInput("p01")
        rule.onNodeWithText("Source: All").performClick()
        rule.onNodeWithText("REPLAY").performClick()
        rule.onNodeWithText("Choose two sessions").performScrollTo().performClick()
        scrollTo(2)
        rule.onNodeWithText(firstDate).performClick()
        scrollTo(1)
        rule.onNodeWithText(secondDate).performClick()
        scrollTo(0)
        rule.activityRule.scenario.recreate()
        scrollTo(0)
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodes(hasText("Source: REPLAY")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("2 readable matches").assertIsDisplayed()
        rule.onAllNodes(hasText("Compare")).assertCountEquals(0)
        rule.onNodeWithText("Choose two sessions").performScrollTo().assertIsDisplayed().performClick()
        scrollTo(2)
        rule.onNodeWithText(firstDate).performClick()
        scrollTo(1)
        rule.onNodeWithText(secondDate).performClick()
        scrollTo(0)
        rule.onNodeWithText("Compare").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodes(hasText("Reported only in first", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Back").performClick()
        scrollTo(0)
        rule.onNodeWithText("Source: REPLAY").assertIsDisplayed()
        rule.onNodeWithText("Choose two sessions").performScrollTo().assertIsDisplayed().performClick()
        scrollTo(2)
        rule.onNodeWithText(firstDate).performClick()
        scrollTo(0)
        rule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Source: REPLAY").assertIsDisplayed()
        rule.onNodeWithText("Choose two sessions").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("First: —", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        scrollTo(2)
        rule.onNodeWithText(firstDate).performClick()
        rule.onNodeWithText("P0133 (stored)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(before, bytes())
        rule.onAllNodes(hasText("Read codes")).assertCountEquals(0)
    }

    @Test @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun noMatchRetainsUnreadableNoticeWithoutClaimingCodeAbsence() {
        val directory = sessionsDir().also { it.deleteRecursively() }
        FileSessionStore(directory).save(record("label-only", 1_700_000_000_000, Provenance.SIMULATED, null))
        directory.resolve("broken.1.json").writeText("invalid JSON P0133")
        val before = bytes()
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        waitFor("1 readable match")
        rule.onNode(hasSetTextAction()).performTextInput("P0133")
        rule.onNodeWithText("Source: All").performScrollTo().performClick()
        rule.onNodeWithText("SIMULATED").performClick()
        rule.onNodeWithText("Source: SIMULATED").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("0 readable matches").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No readable saved sessions match these filters.").performScrollTo().assertIsDisplayed()
        scrollTo(1)
        rule.onNodeWithText("Unreadable file broken.1.json", substring = true).assertIsDisplayed()
        scrollTo(0)
        rule.onNodeWithText("Clear filters").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("1 readable match").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Choose two sessions").performScrollTo().assertIsNotEnabled()
        rule.onAllNodes(hasText("No saved sessions yet.")).assertCountEquals(0)
        assertEquals(before, bytes())
    }
}
