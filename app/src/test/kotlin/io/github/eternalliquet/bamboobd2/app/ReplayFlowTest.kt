package io.github.eternalliquet.bamboobd2.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.lifecycle.ViewModelProvider
import io.github.eternalliquet.bamboobd2.app.ui.PROVENANCE_BANNER_TAG
import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionEvent
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
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

    // The saved list and the record load on the IO dispatcher and are posted to the
    // paused main looper, so idle it while waiting.
    private fun waitForText(text: String) = rule.waitUntil(5_000) {
        ShadowLooper.idleMainLooper()
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun reopenedSessionWithAnUnresolvedPendingReplyNeverClaimsNoCodes() {
        // As ElmObdReader records 03 answered "7E8 7F 03 78" then "7E9 43 00", and 07 read completely.
        fun record(id: String, dtcs: List<Dtc>) = SessionRecord(
            schemaVersion = SessionRecord.SCHEMA_VERSION, id = id, startedAt = 1, endedAt = 2,
            provenance = Provenance.REPLAY, sourceLabel = "replay:$id", complete = true, outcome = SessionOutcome.COMPLETE,
            dtcs = dtcs, dtcKindsRead = listOf(DtcKind.PENDING),
            events = listOf(SessionEvent(2, "codes_incomplete", "stored: response pending, no final reply from 7E8")),
        )
        // Save where the app's ViewModel reads. Under Robolectric that can differ from this
        // test's filesDir: the ViewModel factory keeps the first test's Application.
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        val store = FileSessionStore(vm.getApplication<Application>().filesDir.resolve("sessions"))
        store.save(record("mixed", emptyList()))
        store.save(record("earlier", listOf(Dtc("P0133", DtcKind.STORED, "7E8"))))

        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        for ((id, code) in listOf("mixed" to null, "earlier" to "P0133 (stored)")) {
            val entry = "REPLAY · replay:$id · Complete"
            waitForText(entry)
            rule.onNodeWithText(entry).performClick()
            waitForText("REPLAY · replay:$id")
            rule.onNodeWithText("Not every code list was read completely, so codes may be missing.").performScrollTo().assertIsDisplayed()
            rule.onAllNodes(hasText("No generic emissions codes reported", substring = true)).assertCountEquals(0)
            code?.let { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            rule.onNodeWithContentDescription("Back").performClick()
        }
    }

    @Test
    fun disconnectIsShownAsDisconnectedWithoutReconnecting() {
        startReplay("Disconnect during stored codes")
        rule.onNodeWithText("Disconnected: partial results. Not reconnected.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reason: disconnected: during stored codes").performScrollTo().assertIsDisplayed()
    }
}
