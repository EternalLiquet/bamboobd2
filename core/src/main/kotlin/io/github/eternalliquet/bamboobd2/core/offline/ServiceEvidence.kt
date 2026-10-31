package io.github.eternalliquet.bamboobd2.core.offline

import kotlinx.serialization.Serializable

@Serializable
enum class ServiceCategory { OIL, INTERMEDIATE, MAIN }
@Serializable
enum class ServiceMeaning { REMAINING, ELAPSED, CONFIGURED_INTERVAL, PREVIOUS_SERVICE, DUE_AT }
@Serializable
enum class FieldByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }
@Serializable
enum class AllOnesRule { NUMERIC, UNAVAILABLE }

/** Offset is zero-based in the complete diagnostic payload, including service and identifier. */
@Serializable
data class ServiceFieldDefinition(
    val id: String,
    val source: String,
    val vehicleApplicability: String,
    val moduleApplicability: String,
    val synthetic: Boolean,
    val protocol: DiagnosticProtocol,
    val request: String,
    val responder: String,
    val category: ServiceCategory,
    val meaning: ServiceMeaning,
    val unit: String,
    val offset: Int,
    val width: Int,
    val order: FieldByteOrder,
    val signed: Boolean,
    val scale: Double,
    val additiveOffset: Double,
    val allOnes: AllOnesRule,
    /** Unsigned raw values, checked before signedness or scaling. Empty explicitly means none. */
    val unavailableRaw: List<Long> = emptyList(),
)
enum class FieldStatus { VALUE, UNAVAILABLE, UNKNOWN }
data class DecodedField(
    val definition: ServiceFieldDefinition?,
    val status: FieldStatus,
    val value: Double? = null,
    val reason: String,
    val stepIndex: Int? = null,
    val rawBytes: List<Int> = emptyList(),
)
@Serializable
data class DashboardObservation(
    val category: ServiceCategory,
    val meaning: ServiceMeaning,
    val value: Double,
    val unit: String,
    val source: String,
    val observedAt: String,
)
data class ServiceSnapshot(
    val report: AnalysisReport,
    val definitions: List<ServiceFieldDefinition>,
    val dashboard: List<DashboardObservation> = emptyList(),
)
enum class ChangeStatus { CHANGED, UNCHANGED, NOT_COMPARABLE }
data class FieldChange(val category: ServiceCategory, val id: String, val before: DecodedField?, val after: DecodedField?,
    val status: ChangeStatus, val delta: Double?, val reason: String)
data class DashboardChange(val category: ServiceCategory, val meaning: ServiceMeaning, val unit: String,
    val before: List<DashboardObservation>, val after: List<DashboardObservation>, val status: ChangeStatus,
    val delta: Double?, val reason: String)
data class ServiceComparison(
    val before: ServiceSnapshot,
    val after: ServiceSnapshot,
    /** Operator's description, kept as unverified evidence; never used as a success signal. */
    val operationObservation: String?,
    val fields: List<FieldChange>,
    val dashboard: List<DashboardChange>,
) {
    val categories: List<ServiceCategory> get() = ServiceCategory.entries.toList()
    fun render(): String = buildString {
        appendLine("Offline service evidence comparison")
        appendLine("Before provenance: ${before.report.recording.script.provenance}; after provenance: ${after.report.recording.script.provenance}")
        appendLine("Identity equality relies on supplied pseudonym/module metadata, not authenticated vehicle identity.")
        appendLine("Operation observation: ${if (operationObservation == null) "not supplied" else "supplied, unverified"}; causation unknown.")
        categories.forEach { category ->
            appendLine(category.name)
            val rows = fields.filter { it.category == category }
            if (rows.isEmpty()) appendLine("  No explicit comparable field definitions; values unknown.")
            rows.forEach { row ->
                appendLine("  ${row.before?.definition?.meaning ?: row.after?.definition?.meaning}: ${row.status}; " +
                    "before=${row.before?.value ?: "unknown"}, after=${row.after?.value ?: "unknown"}, delta=${row.delta ?: "unknown"}; ${row.reason}")
            }
            dashboard.filter { it.category == category }.forEach { row ->
                appendLine("  Manual dashboard ${row.meaning}: ${row.status}; delta=${row.delta ?: "unknown"}; ${row.reason}")
            }
        }
        appendLine("Counter changes are observations only. An acknowledgement cannot establish a service reset or its cause.")
        appendLine("Sources, raw bytes and manual-observation timestamps remain in the local evidence objects; review them before sharing.")
    }
}

object ServiceEvidence {
    fun decode(report: AnalysisReport, definition: ServiceFieldDefinition?): DecodedField {
        fun unknown(reason: String) = DecodedField(definition, FieldStatus.UNKNOWN, reason = reason)
        if (definition == null) return unknown("No explicit definition")
        val d = definition
        if (listOf(d.id, d.source, d.vehicleApplicability, d.moduleApplicability, d.responder, d.unit).any { it.isBlank() } ||
            d.width !in 1..6 || d.offset !in 0..4095 || !d.scale.isFinite() || !d.additiveOffset.isFinite() || d.scale == 0.0 ||
            d.protocol == DiagnosticProtocol.UNKNOWN || hexBytes(d.request) == null)
            return unknown("Invalid or incomplete explicit definition")
        val maxRaw = (1L shl (8 * d.width)) - 1L
        if (d.unavailableRaw.any { it !in 0..maxRaw }) return unknown("Unavailable rule outside field width")
        val r = report.recording
        if (d.synthetic && r.script.provenance != "SYNTHETIC") return unknown("Synthetic definitions cannot decode real recordings")
        if (r.vehicleApplicability != d.vehicleApplicability || r.moduleIdentity != d.moduleApplicability || r.protocol != d.protocol)
            return unknown("Vehicle/module/protocol applicability does not match")
        val exchanges = report.exchanges.filter { hexBytes(it.request) == hexBytes(d.request) }
        val exchange = exchanges.singleOrNull() ?: return unknown("Missing or repeated matching request; select a single observation first")
        if (!exchange.usable) return unknown("Response is incomplete, conflicting, negative, pending or uncorrelated")
        val response = exchange.responses.single { it.status == ExchangeStatus.COMPLETE }
        if (response.responder != d.responder) return unknown("Responder identity mismatch or unknown")
        if (d.offset > response.bytes.size - d.width) return unknown("Defined field exceeds response bounds")
        val bytes = response.bytes.subList(d.offset, d.offset + d.width).toList()
        val ordered = if (d.order == FieldByteOrder.BIG_ENDIAN) bytes else bytes.reversed()
        var raw = 0L
        for (byte in ordered) raw = (raw shl 8) or byte.toLong()
        if (raw in d.unavailableRaw || (raw == maxRaw && d.allOnes == AllOnesRule.UNAVAILABLE))
            return DecodedField(d, FieldStatus.UNAVAILABLE, reason = "Explicit unavailable-value rule", stepIndex = exchange.stepIndex, rawBytes = bytes)
        val signed = if (d.signed && (raw and (1L shl (8 * d.width - 1))) != 0L) raw - (1L shl (8 * d.width)) else raw
        val value = signed * d.scale + d.additiveOffset
        if (!value.isFinite()) return unknown("Scaling exceeds finite numeric range")
        return DecodedField(d, FieldStatus.VALUE, value, "Decoded only under supplied definition; source applicability is caller asserted", exchange.stepIndex, bytes)
    }

    fun compare(before: ServiceSnapshot, after: ServiceSnapshot, operationObservation: String? = null): ServiceComparison {
        val a = before.report.recording
        val b = after.report.recording
        val identityProblem = when {
            a.vehicleAlias.isNullOrBlank() || b.vehicleAlias.isNullOrBlank() || a.moduleIdentity.isNullOrBlank() || b.moduleIdentity.isNullOrBlank() -> "Vehicle/module identity unknown"
            a.vehicleAlias != b.vehicleAlias || a.moduleIdentity != b.moduleIdentity || a.vehicleApplicability != b.vehicleApplicability -> "Vehicle/module identities differ"
            a.script.provenance != b.script.provenance -> "Recording provenances differ"
            a.script.id == b.script.id -> "Same recording is not independent before/after evidence"
            else -> null
        }
        val keys = (before.definitions + after.definitions).map { it.category to it.id }.distinct()
        val fields = keys.map { (category, id) ->
            val da = before.definitions.filter { it.category == category && it.id == id }.singleOrNull()
            val db = after.definitions.filter { it.category == category && it.id == id }.singleOrNull()
            val va = da?.let { decode(before.report, it) }
            val vb = db?.let { decode(after.report, it) }
            val reason = identityProblem ?: when {
                da == null || db == null -> "Missing or duplicate field definition"
                da != db -> "Field definitions differ; no automatic conversion"
                va?.status != FieldStatus.VALUE || vb?.status != FieldStatus.VALUE -> "One or both field values unknown or unavailable"
                !(vb.value!! - va.value!!).isFinite() -> "Non-finite change"
                else -> null
            }
            val delta = if (reason == null) vb!!.value!! - va!!.value!! else null
            FieldChange(category, id, va, vb, status(delta), delta, reason ?: "Same explicit definition; observed change, causation unknown")
        }
        val dashboardKeys = (before.dashboard + after.dashboard).map { Triple(it.category, it.meaning, it.unit) }.distinct()
        val dashboard = dashboardKeys.map { (category, meaning, unit) ->
            val av = before.dashboard.filter { it.category == category && it.meaning == meaning && it.unit == unit }
            val bv = after.dashboard.filter { it.category == category && it.meaning == meaning && it.unit == unit }
            val reason = identityProblem ?: when {
                av.size != 1 || bv.size != 1 -> "Missing or duplicate manual observation"
                (av + bv).any { !it.value.isFinite() || it.source.isBlank() || it.observedAt.isBlank() || it.unit.isBlank() } -> "Incomplete manual provenance or invalid value"
                !(bv.single().value - av.single().value).isFinite() -> "Non-finite manual change"
                else -> null
            }
            val delta = if (reason == null) bv.single().value - av.single().value else null
            DashboardChange(category, meaning, unit, av, bv, status(delta), delta, reason ?: "Manual observations; not independently linked to a raw field")
        }
        return ServiceComparison(before, after, operationObservation, fields, dashboard)
    }

    private fun status(delta: Double?) = when (delta) {
        null -> ChangeStatus.NOT_COMPARABLE
        0.0 -> ChangeStatus.UNCHANGED
        else -> ChangeStatus.CHANGED
    }
}
