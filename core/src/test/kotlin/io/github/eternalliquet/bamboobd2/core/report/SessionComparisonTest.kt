package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionEvent
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComparisonTest {
    private fun record(id: String) = SessionRecord(
        schemaVersion = 1, id = id, startedAt = 7, endedAt = 10,
        provenance = Provenance.REPLAY, sourceLabel = "untrusted", complete = true, outcome = SessionOutcome.COMPLETE,
    )

    private fun ready(first: SessionRecord, second: SessionRecord) =
        SessionComparison.compare(first, second) as ComparisonResult.Ready

    @Test
    fun catalogVoltageAndThrottleCompareByMatchingResponderAndCanonicalUnit() {
        val a = record("a").copy(readings = listOf(
            Reading("42", ReadingResult.Value(12.5, "V", "7E8"), 8),
            Reading("11", ReadingResult.Value(40.0, "%", "7E9"), 8),
        ))
        val b = record("b").copy(readings = listOf(
            Reading("42", ReadingResult.Value(13.0, "V", "7E8"), 9),
            Reading("11", ReadingResult.Value(42.0, "%", "7E8"), 9),
        ))
        val rows = ready(a, b).readings
        assertEquals(0.5, rows.single { it.key == ReadingKey("42", "7E8") }.delta!!, 0.0)
        assertEquals(listOf("7E8", "7E9"), rows.filter { it.key.pid == "11" }.map { it.key.ecu })
        assertTrue(rows.filter { it.key.pid == "11" }.all { it.reason == NotComparable.MISSING_SIDE })
    }

    @Test
    fun blocksSameIdAndMixedProvenanceWithoutReorderingEqualTimestamps() {
        assertEquals(ComparisonResult.Blocked(ComparisonBlock.SAME_SESSION), SessionComparison.compare(record("a"), record("a")))
        assertEquals(ComparisonResult.Blocked(ComparisonBlock.DIFFERENT_PROVENANCE),
            SessionComparison.compare(record("a"), record("b").copy(provenance = Provenance.LIVE)))
        val a = record("a").copy(readings = listOf(Reading("05", ReadingResult.Value(90.0, "°C", "7E8"), 9)))
        val b = record("b").copy(readings = listOf(Reading("05", ReadingResult.Value(80.0, "°C", "7E8"), 9)))
        val row = ready(a, b).readings.single { it.key == ReadingKey(ObdPid.COOLANT_TEMP, "7E8") }
        assertEquals(90.0, row.first.single().value!!, 0.0)
        assertEquals(80.0, row.second.single().value!!, 0.0)
        assertEquals(-10.0, row.delta!!, 0.0)
    }

    @Test
    fun codeSetsKeepKindAndResponderOnlyWhenBothListsWereRead() {
        val first = record("a").copy(
            dtcKindsRead = DtcKind.entries,
            dtcs = listOf(Dtc("P0133", DtcKind.STORED, "7E8"), Dtc("P0133", DtcKind.STORED, "7E9"),
                Dtc("P0171", DtcKind.PENDING, "7E8")),
        )
        val second = record("b").copy(
            dtcKindsRead = DtcKind.entries,
            dtcs = listOf(Dtc("P0133", DtcKind.STORED, "7E8"), Dtc("P0133", DtcKind.PENDING, "7E8")),
        )
        val compared = ready(first, second)
        val stored = compared.codeKinds.single { it.kind == DtcKind.STORED }.differences!!
        assertEquals(listOf(CodeKey(DtcKind.STORED, "P0133", "7E8")), stored.reportedInBoth)
        assertEquals(listOf(CodeKey(DtcKind.STORED, "P0133", "7E9")), stored.onlyFirst)
        assertTrue(stored.onlySecond.isEmpty())
        val pending = compared.codeKinds.single { it.kind == DtcKind.PENDING }.differences!!
        assertTrue(pending.reportedInBoth.isEmpty())
        assertEquals(listOf(CodeKey(DtcKind.PENDING, "P0171", "7E8")), pending.onlyFirst)
        assertEquals(listOf(CodeKey(DtcKind.PENDING, "P0133", "7E8")), pending.onlySecond)
    }

    @Test
    fun incompleteAndUnreadKindsNeverProduceSetDifferences() {
        val cases = listOf(
            record("b") to CodeCoverage.NOT_READ,
            record("b").copy(dtcs = listOf(Dtc("P0133", DtcKind.STORED))) to CodeCoverage.INCOMPLETE,
            record("b").copy(events = listOf(SessionEvent(9, "codes_incomplete", "stored: untrusted"))) to CodeCoverage.INCOMPLETE,
        )
        for ((second, coverage) in cases) {
            val compared = ready(record("a").copy(dtcKindsRead = listOf(DtcKind.STORED)), second)
            val kind = compared.codeKinds.single { it.kind == DtcKind.STORED }
            assertEquals(CodeCoverage.READ, kind.first.coverage)
            assertEquals(coverage, kind.second.coverage)
            assertNull(kind.differences)
        }
    }

    @Test
    fun readCoverageWithMalformedCodeOrResponderShowsListsButNoDifference() {
        val first = record("a").copy(dtcKindsRead = listOf(DtcKind.STORED),
            dtcs = listOf(Dtc("P0133", DtcKind.STORED, "SECRET_A")))
        val second = record("b").copy(dtcKindsRead = listOf(DtcKind.STORED),
            dtcs = listOf(Dtc("P0133", DtcKind.STORED, "7E8")))
        for (left in listOf(first, first.copy(dtcs = listOf(Dtc("INVALID_SECRET", DtcKind.STORED, "7E8"))))) {
            val compared = ready(left, second).codeKinds.single { it.kind == DtcKind.STORED }
            assertEquals(CodeCoverage.READ, compared.first.coverage)
            assertEquals(CodeCoverage.READ, compared.second.coverage)
            assertTrue(compared.first.codes.isNotEmpty())
            assertNull(compared.differences)
        }
        val unknownAttribution = first.copy(dtcs = listOf(Dtc("P0133", DtcKind.STORED, null)))
        assertTrue(ready(unknownAttribution, second).codeKinds.single { it.kind == DtcKind.STORED }.differences != null)
    }

    @Test
    fun finiteMatchingValuesShowSignedPositiveAndZeroDeltas() {
        for ((firstValue, secondValue, expected) in listOf(Triple(50.0, 52.5, 2.5), Triple(1726.0, 1726.0, 0.0))) {
            val a = record("a").copy(readings = listOf(Reading("0C", ReadingResult.Value(firstValue, "rpm", "10"), 8)))
            val b = record("b").copy(readings = listOf(Reading("0C", ReadingResult.Value(secondValue, "rpm", "10"), 9)))
            val row = ready(a, b).readings.single { it.key == ReadingKey(ObdPid.ENGINE_RPM, "10") }
            assertNull(row.reason)
            assertEquals(expected, row.delta!!, 0.0)
            assertEquals(8L, row.first.single().atMillis)
            assertEquals(9L, row.second.single().atMillis)
        }
    }

    @Test
    fun ambiguityAndMissingStatusesNeverBecomeZero() {
        val valid = Reading("04", ReadingResult.Value(50.0, "%", "7E8"), 8)
        val cases = listOf(
            record("b") to NotComparable.MISSING_SIDE,
            record("b").copy(readings = listOf(valid, valid)) to NotComparable.DUPLICATE,
            record("b").copy(readings = listOf(valid.copy(result = ReadingResult.Value(2.0, "rpm", "7E8")))) to NotComparable.INVALID_VALUE,
            record("b").copy(readings = listOf(valid.copy(result = ReadingResult.Value(Double.NaN, "%", "7E8")))) to NotComparable.INVALID_VALUE,
        )
        for ((second, reason) in cases) {
            val row = ready(record("a").copy(readings = listOf(valid)), second).readings
                .single { it.key == ReadingKey(ObdPid.ENGINE_LOAD, "7E8") }
            assertEquals(reason, row.reason)
            assertNull(row.delta)
        }
        val unknown = Reading("04", ReadingResult.Value(50.0, "%", null), 8)
        assertEquals(NotComparable.UNKNOWN_RESPONDER, ready(record("a").copy(readings = listOf(unknown)),
            record("b").copy(readings = listOf(unknown))).readings.single { it.key == ReadingKey(ObdPid.ENGINE_LOAD, null) }.reason)
        val unsupported = ready(record("a").copy(readings = listOf(Reading("0D", ReadingResult.Unsupported, 8))),
            record("b").copy(events = listOf(SessionEvent(7, "read_scope", "CODES_ONLY")))).readings
            .single { it.key == ReadingKey(ObdPid.VEHICLE_SPEED, null) }
        assertEquals(ReadingStatus.UNSUPPORTED, unsupported.first.single().status)
        assertEquals(ReadingStatus.NOT_REQUESTED, unsupported.second.single().status)
        assertNull(unsupported.delta)
        val huge = ready(record("a").copy(readings = listOf(Reading("04", ReadingResult.Value(-Double.MAX_VALUE, "%", "7E8"), 8))),
            record("b").copy(readings = listOf(Reading("04", ReadingResult.Value(Double.MAX_VALUE, "%", "7E8"), 9)))).readings
            .single { it.key == ReadingKey(ObdPid.ENGINE_LOAD, "7E8") }
        assertEquals(NotComparable.NON_FINITE_DELTA, huge.reason)
        assertNull(huge.delta)
    }

    @Test
    fun differentRespondersAndNonValueStatesStaySeparate() {
        val statuses = listOf(
            ReadingResult.NoData to ReadingStatus.NO_DATA,
            ReadingResult.Failed("untrusted") to ReadingStatus.FAILED,
            ReadingResult.UnknownCapability to ReadingStatus.UNKNOWN_CAPABILITY,
        )
        statuses.forEach { (result, status) ->
            val row = ready(record("a").copy(readings = listOf(Reading("05", result, 8))), record("b")).readings
                .single { it.key == ReadingKey(ObdPid.COOLANT_TEMP, null) }
            assertEquals(status, row.first.single().status)
            assertEquals(ReadingStatus.NOT_READ, row.second.single().status)
            assertNull(row.delta)
        }
        val a = record("a").copy(readings = listOf(Reading("05", ReadingResult.Value(80.0, "°C", "7E8"), 8)))
        val b = record("b").copy(readings = listOf(Reading("05", ReadingResult.Value(81.0, "°C", "7E9"), 9)))
        val rows = ready(a, b).readings.filter { it.key.pid == ObdPid.COOLANT_TEMP.code }
        assertEquals(listOf("7E8", "7E9"), rows.map { it.key.ecu })
        assertTrue(rows.all { it.reason == NotComparable.MISSING_SIDE && it.delta == null })
    }
}
