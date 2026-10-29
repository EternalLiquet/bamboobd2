package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionEvent
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSummaryTest {
    private fun render(record: SessionRecord) = SessionSummary.render(record, "0.1-test") { "T$it" }

    private fun record() = SessionRecord(
        schemaVersion = 1,
        id = "SECRET_SESSION_ID",
        startedAt = 10,
        endedAt = 20,
        provenance = Provenance.REPLAY,
        sourceLabel = "SECRET_SOURCE_LABEL",
        complete = false,
        outcome = SessionOutcome.CANCELLED,
    )

    @Test
    fun renderedScopeCodesAndSnapshotUseOnlyRecognizedValues() {
        val report = render(record().copy(
            dtcs = listOf(Dtc("P0133", DtcKind.STORED, "7E8")),
            dtcKindsRead = listOf(DtcKind.STORED, DtcKind.PENDING),
            readings = listOf(Reading("04", ReadingResult.Value(50.196, "%", "10"), 15)),
            events = listOf(SessionEvent(10, "read_scope", "FULL")),
        ))
        listOf("BambooBD2 0.1-test", "REPLAY", "T10", "T20", "Cancelled",
            "Codes and one sensor snapshot", "P0133 (responder 7E8)", "Pending codes: Read",
            "No codes reported", "Engine load (PID 04): 50.2 % (responder 10); captured T15",
            "Coolant temperature (PID 05): Not read", "not a diagnosis").forEach {
            assertTrue("Expected '$it' in report: $report", report.contains(it))
        }
        assertFalse(report.contains("SECRET_"))
    }

    @Test
    fun hostileRecordFieldsNeverEnterShareableTextAndIncompleteIsNotNoCodes() {
        val hostile = "SECRET_VIN_ADDRESS_RAW_CAPTURE"
        val report = render(record().copy(
            dtcs = listOf(Dtc("P0133", DtcKind.STORED, hostile), Dtc(hostile, DtcKind.PENDING, "7E8")),
            readings = listOf(
                Reading("05", ReadingResult.Failed(hostile), 12),
                Reading("04", ReadingResult.Value(Double.NaN, hostile, hostile), 13),
                Reading("0C", ReadingResult.Value(1726.0, "rpm", hostile), 14),
                Reading(hostile, ReadingResult.Value(1.0, hostile, hostile), 15),
            ),
            events = listOf(
                SessionEvent(11, "codes_incomplete", "stored: $hostile"),
                SessionEvent(12, "other", hostile),
                SessionEvent(13, "read_scope", hostile),
            ),
        ))
        listOf("Stored codes: Incomplete", "Pending codes: Incomplete", "No usable value",
            "Failed (no value)", "1726.0 rpm (unknown responder)",
            "Read scope: Unknown", "unknown responder", "No validated code included").forEach {
            assertTrue("Expected '$it' in report: $report", report.contains(it))
        }
        listOf(hostile, "SECRET_SESSION_ID", "SECRET_SOURCE_LABEL", "No codes reported", "NaN").forEach {
            assertFalse("Leaked '$it' in report: $report", report.contains(it))
        }
    }

    @Test
    fun eachCodeKindReportsReadThenIncompleteThenNotReadByEvidence() {
        for (kind in DtcKind.entries) {
            val label = if (kind == DtcKind.STORED) "Stored" else "Pending"
            val code = Dtc("P0133", kind)
            val incomplete = SessionEvent(11, "codes_incomplete", "${kind.name.lowercase()}: hidden reason")
            val cases = listOf(
                Triple(record().copy(dtcs = listOf(code), dtcKindsRead = listOf(kind), events = listOf(incomplete)), "Read", true),
                Triple(record().copy(dtcs = listOf(code)), "Incomplete", true),
                Triple(record().copy(events = listOf(incomplete)), "Incomplete", false),
                Triple(record(), "Not read", false),
            )
            cases.forEach { (input, status, hasEntry) ->
                val report = render(input)
                assertTrue(report.contains("$label codes: $status"))
                assertTrue(report.contains("P0133 (unknown responder)") == hasEntry)
                assertFalse(report.contains("hidden reason"))
                if (status != "Read") assertFalse(report.contains("No codes reported"))
            }
        }
    }

    @Test
    fun olderAndCodesOnlyRecordsDistinguishUnknownScopeAndNotRequested() {
        val older = render(record())
        assertTrue(older.contains("Read scope: Unknown"))
        assertTrue(older.contains("Stored codes: Not read"))
        assertTrue(older.contains("Usable data: Nothing read"))
        assertTrue(older.contains("Engine load (PID 04): Not read"))
        val codesOnly = render(record().copy(events = listOf(SessionEvent(10, "read_scope", "CODES_ONLY"))))
        assertTrue(codesOnly.contains("Engine load (PID 04): Not requested"))
        assertTrue(codesOnly.contains("SIMULATED").not())
    }
}
