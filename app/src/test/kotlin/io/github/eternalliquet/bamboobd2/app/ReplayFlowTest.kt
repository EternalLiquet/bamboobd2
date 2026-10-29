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
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
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

    // Where the app's ViewModel stores sessions, emptied first. Under Robolectric it can differ
    // from this test's filesDir: the ViewModel factory keeps the first test's Application.
    private fun viewModelSessionsDir() =
        ViewModelProvider(rule.activity)[AppViewModel::class.java].getApplication<Application>().filesDir.resolve("sessions")
            .also { it.deleteRecursively() }

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
        val store = FileSessionStore(viewModelSessionsDir())
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
    fun reopenedSessionWithIncompleteDiscoveryShowsUnconfirmedReadingsAsUnknown() {
        // As ElmObdReader records 0100 answered by 7E9 (PIDs 05, 0C) while 7E8 stayed pending.
        val record = SessionRecord(
            schemaVersion = SessionRecord.SCHEMA_VERSION, id = "discovery", startedAt = 1, endedAt = 2,
            provenance = Provenance.REPLAY, sourceLabel = "replay:discovery", complete = true, outcome = SessionOutcome.COMPLETE,
            supportedPids = SupportedPids.Known(listOf("05", "0C"), complete = false),
            dtcKindsRead = listOf(DtcKind.STORED, DtcKind.PENDING),
            readings = listOf(
                Reading("04", ReadingResult.UnknownCapability, 2),
                Reading("05", ReadingResult.Value(83.0, "°C", "7E9"), 2),
                Reading("0C", ReadingResult.Value(1726.0, "rpm", "7E9"), 2),
                Reading("0D", ReadingResult.UnknownCapability, 2),
            ),
        )
        FileSessionStore(viewModelSessionsDir()).save(record)

        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        waitForText("REPLAY · replay:discovery · Complete")
        rule.onNodeWithText("REPLAY · replay:discovery · Complete").performClick()
        waitForText("REPLAY · replay:discovery")
        rule.onNodeWithText("Not every responder reported which readings it supports. Only confirmed readings were requested.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("83 °C (7E9)").performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasText("Unknown: support could not be confirmed")).assertCountEquals(2)
        rule.onAllNodes(hasText("Not reported by this vehicle")).assertCountEquals(0)
    }

    @Test
    fun fourSensorSupportRowsKeepMapCoverageSeparateFromCodesOnlyMeasurements() {
        val cases = listOf(
            Triple("complete-map", SupportedPids.Known(listOf("04", "0C")), listOf("Supported by at least one responder", "Unsupported", "Supported by at least one responder", "Unsupported")),
            Triple("partial-map", SupportedPids.Known(listOf("04", "0C"), complete = false), listOf("Supported by at least one responder", "Unknown", "Supported by at least one responder", "Unknown")),
            Triple("unknown-map", SupportedPids.Unknown, listOf("Unknown", "Unknown", "Unknown", "Unknown")),
        )
        val store = FileSessionStore(viewModelSessionsDir())
        cases.forEach { (id, map, _) ->
            store.save(SessionRecord(
                schemaVersion = SessionRecord.SCHEMA_VERSION, id = id, startedAt = 1, endedAt = 2,
                provenance = Provenance.REPLAY, sourceLabel = "replay:$id", complete = true, outcome = SessionOutcome.COMPLETE,
                supportedPids = map, events = listOf(SessionEvent(1, "read_scope", "CODES_ONLY"),
                    SessionEvent(2, "readings_not_requested", "synthetic")),
            ))
        }
        rule.onNodeWithText("Saved sessions").performScrollTo().performClick()
        val labels = listOf("Engine load", "Coolant temperature", "Engine speed", "Vehicle speed")
        for ((id, _, statuses) in cases) {
            val entry = "REPLAY · replay:$id · Complete"
            waitForText(entry)
            rule.onNodeWithText(entry).performClick()
            waitForText("REPLAY · replay:$id")
            rule.onNodeWithText("Sensor support").performScrollTo().assertIsDisplayed()
            labels.zip(statuses).forEach { (label, status) ->
                rule.onNodeWithText("$label: $status").performScrollTo().assertIsDisplayed()
            }
            rule.onNodeWithText("Live readings were not requested: this was a codes-only session.")
                .performScrollTo().assertIsDisplayed()
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
