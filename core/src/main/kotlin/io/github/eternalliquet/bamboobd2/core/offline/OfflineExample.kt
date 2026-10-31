package io.github.eternalliquet.bamboobd2.core.offline

import io.github.eternalliquet.bamboobd2.core.protocol.ReplayChunk
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayStep

/** Constructed bytes and definitions only. None of these identifiers belongs to a verified vehicle. */
object OfflineExample {
    fun recording(after: Boolean = false): ManufacturerRecording {
        val values = if (after) listOf("00 65", "00 C8", "FF FF") else listOf("00 64", "00 C8", "01 90")
        return ManufacturerRecording(
            ReplayScript(if (after) "synthetic-after" else "synthetic-before", "Synthetic service example", "SYNTHETIC", "constructed",
                "Constructed offline example; no vehicle or reset occurred", steps = values.mapIndexed { index, value ->
                    val identifier = "12${(0x34 + index).toString(16).uppercase()}"
                    ReplayStep("22$identifier", listOf(ReplayChunk(15, "700 05 62 ${identifier.take(2)} ${identifier.takeLast(2)} $value\r>")))
                }),
            DiagnosticProtocol.UDS, RecordedFraming.CAN_11BIT_HEADERS, expectedResponder = "700", vehicleAlias = "synthetic-car", vehicleApplicability = "synthetic-family",
            moduleIdentity = "synthetic-module",
        )
    }

    fun definitions(): List<ServiceFieldDefinition> = ServiceCategory.entries.mapIndexed { index, category ->
        ServiceFieldDefinition("synthetic-${category.name.lowercase()}-days", "Constructed example in OfflineExample.kt, not a Porsche definition",
            "synthetic-family", "synthetic-module", true, DiagnosticProtocol.UDS,
            "2212${(0x34 + index).toString(16).uppercase()}", "700", category, ServiceMeaning.REMAINING, "days", 3, 2,
            FieldByteOrder.BIG_ENDIAN, false, 1.0, 0.0, AllOnesRule.UNAVAILABLE)
    }

    fun comparison(): ServiceComparison = ServiceEvidence.compare(
        ServiceSnapshot(OfflineDiagnostics.analyze(recording()), definitions(), listOf(
            DashboardObservation(ServiceCategory.OIL, ServiceMeaning.REMAINING, 100.0, "days", "Constructed manual observation", "synthetic t0"))),
        ServiceSnapshot(OfflineDiagnostics.analyze(recording(true)), definitions(), listOf(
            DashboardObservation(ServiceCategory.OIL, ServiceMeaning.REMAINING, 101.0, "days", "Constructed manual observation", "synthetic t1"))),
        "Constructed acknowledgement; no operation occurred",
    )

    fun render(): String = "SYNTHETIC EXAMPLE — not vehicle validation\n\n" +
        OfflineDiagnostics.analyze(recording()).render() + "\n" + comparison().render()
}
