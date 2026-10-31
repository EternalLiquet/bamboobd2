package io.github.eternalliquet.bamboobd2.core.offline

import io.github.eternalliquet.bamboobd2.core.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ServiceEvidenceTest {
    private fun report(value: String, id: String = "before") = OfflineDiagnostics.analyze(ManufacturerRecording(
        ReplayScript(id, "Synthetic", "SYNTHETIC", "constructed", "Constructed fixture", steps = listOf(
            ReplayStep("221234", listOf(ReplayChunk(0, "700 05 62 12 34 $value\r>"))))),
        DiagnosticProtocol.UDS, RecordedFraming.CAN_11BIT_HEADERS, expectedResponder = "700", vehicleAlias = "fixture-car", vehicleApplicability = "synthetic-family", moduleIdentity = "fixture-module"))
    private fun definition(category: ServiceCategory = ServiceCategory.OIL) = ServiceFieldDefinition(
        "synthetic-days", "Constructed fixture; not a Porsche definition", "synthetic-family", "fixture-module", true,
        DiagnosticProtocol.UDS, "221234", "700", category, ServiceMeaning.REMAINING, "days", 3, 2,
        FieldByteOrder.BIG_ENDIAN, false, 1.0, 0.0, AllOnesRule.UNAVAILABLE)

    @Test fun explicitDefinitionsDecodeAndUnavailableDoesNotBecomeANumber() {
        assertEquals(100.0, ServiceEvidence.decode(report("00 64"), definition()).value!!, 0.0)
        assertEquals(FieldStatus.UNAVAILABLE, ServiceEvidence.decode(report("FF FF"), definition()).status)
        assertEquals(FieldStatus.UNKNOWN, ServiceEvidence.decode(report("00 64"), null).status)
        val signed = definition().copy(signed = true, scale = 0.5, allOnes = AllOnesRule.NUMERIC)
        assertEquals(-0.5, ServiceEvidence.decode(report("FF FF"), signed).value!!, 0.0)
        assertEquals(25600.0, ServiceEvidence.decode(report("00 64"), definition().copy(order = FieldByteOrder.LITTLE_ENDIAN)).value!!, 0.0)
    }

    @Test fun incorrectIdentityTruncationAndSyntheticApplicabilityStayUnknown() {
        assertEquals(FieldStatus.UNKNOWN, ServiceEvidence.decode(report("00 64"), definition().copy(moduleApplicability = "other")).status)
        assertEquals(FieldStatus.UNKNOWN, ServiceEvidence.decode(report("00 64"), definition().copy(offset = 4)).status)
        val raw = report("00 64").recording.let { it.copy(script = it.script.copy(provenance = "RAW_CAPTURE")) }
        assertEquals(FieldStatus.UNKNOWN, ServiceEvidence.decode(OfflineDiagnostics.analyze(raw), definition()).status)
    }

    @Test fun comparisonKeepsCategoriesAndAcknowledgementSeparateFromCausality() {
        val a = ServiceSnapshot(report("00 64"), listOf(definition()), listOf(DashboardObservation(ServiceCategory.OIL, ServiceMeaning.REMAINING, 100.0, "days", "manual fixture", "synthetic time")))
        val b = ServiceSnapshot(report("00 65", "after"), listOf(definition()))
        val result = ServiceEvidence.compare(a, b, "Synthetic operation acknowledgement only")
        assertEquals(1.0, result.fields.single().delta!!, 0.0)
        assertEquals(ChangeStatus.CHANGED, result.fields.single().status)
        assertEquals(listOf(ServiceCategory.OIL, ServiceCategory.INTERMEDIATE, ServiceCategory.MAIN), result.categories)
        assertTrue(result.render().contains("causation unknown"))
        assertFalse(result.render().contains("reset verified", ignoreCase = true))
        assertEquals(ChangeStatus.NOT_COMPARABLE, ServiceEvidence.compare(a, b.copy(definitions = listOf(definition().copy(scale = 2.0)))).fields.single().status)
    }

    @Test fun documentedExampleHasThreeDistinctOutcomes() {
        val comparison = OfflineExample.comparison()
        assertEquals(listOf(ChangeStatus.CHANGED, ChangeStatus.UNCHANGED, ChangeStatus.NOT_COMPARABLE), comparison.fields.map { it.status })
        assertEquals(FieldStatus.UNAVAILABLE, comparison.fields.last().after!!.status)
        assertTrue(OfflineExample.render().startsWith("SYNTHETIC EXAMPLE"))
    }

    @Test fun unknownVehicleIdentityAndDuplicateFieldsBlockComparison() {
        val a = ServiceSnapshot(report("00 64"), listOf(definition()))
        val noIdentity = a.report.recording.copy(vehicleAlias = null)
        val b = a.copy(report = OfflineDiagnostics.analyze(noIdentity))
        assertTrue(ServiceEvidence.compare(a,b).fields.all { it.status == ChangeStatus.NOT_COMPARABLE })
        assertEquals(FieldStatus.UNKNOWN, ServiceEvidence.decode(a.report.copy(exchanges = a.report.exchanges + a.report.exchanges), definition()).status)
    }
}
