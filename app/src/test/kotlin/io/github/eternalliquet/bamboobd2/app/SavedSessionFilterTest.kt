package io.github.eternalliquet.bamboobd2.app

import io.github.eternalliquet.bamboobd2.app.saved.filterSavedSessions
import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSessionFilterTest {
    private fun row(id: String, source: Provenance, vararg codes: Dtc) = StoredSession.Readable("$id.1.json", SessionRecord(
        schemaVersion = 1, id = id, startedAt = 1, provenance = source, sourceLabel = "P0133 in a label",
        complete = false, dtcs = codes.toList(),
    ))

    @Test fun literalPrefixAndSourceOnlySearchRecordedCanonicalCodes() {
        val stored = row("stored", Provenance.REPLAY, Dtc("P0133", DtcKind.STORED))
        val pending = row("pending", Provenance.LIVE, Dtc("P0171", DtcKind.PENDING))
        val malformed = row("malformed", Provenance.REPLAY, Dtc("P.*99", DtcKind.STORED))
        val labelOnly = row("label", Provenance.SIMULATED)
        val entries = listOf(stored, pending, malformed, labelOnly)
        val cases = listOf(
            Triple("", null, entries),
            Triple(" p01 ", null, listOf(stored, pending)),
            Triple("P01", Provenance.LIVE, listOf(pending)),
            Triple("p0133", Provenance.REPLAY, listOf(stored)),
            Triple("0133", null, emptyList()),
            Triple("P.*", null, emptyList()),
            Triple("P013", Provenance.SIMULATED, emptyList()),
            Triple("   ", Provenance.REPLAY, listOf(stored, malformed)),
        )
        cases.forEach { (query, source, expected) ->
            assertEquals("query=$query source=$source", expected, filterSavedSessions(entries, query, source))
        }
    }

    @Test fun unreadableEntriesRemainInOriginalOrderButNeverBecomeMatches() {
        val unreadable = StoredSession.Unreadable("broken.1.json", "invalid JSON P0133")
        val match = row("match", Provenance.REPLAY, Dtc("P0133", DtcKind.PENDING))
        val noMatch = row("other", Provenance.LIVE)
        val entries = listOf(noMatch, unreadable, match)
        assertEquals(listOf(unreadable, match), filterSavedSessions(entries, "p01", Provenance.REPLAY))
        assertEquals(listOf(unreadable), filterSavedSessions(entries, "P.*", Provenance.SIMULATED))
        assertEquals(entries, filterSavedSessions(entries, " ", null))
    }
}
