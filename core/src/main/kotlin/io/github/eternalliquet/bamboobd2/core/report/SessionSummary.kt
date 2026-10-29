package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
import kotlin.math.round

/** A deliberately narrow, deterministic export of a saved or finished session. */
object SessionSummary {
    private val codePattern = Regex("[PCBU][0-3][0-9A-F]{3}")
    private val responderPattern = Regex("(?:[0-9A-F]{2}|[0-7][0-9A-F]{2})")

    fun render(record: SessionRecord, appVersion: String, formatTime: (Long) -> String): String {
        val scope = record.events.firstOrNull { it.kind == SessionController.READ_SCOPE }?.detail
        val scopeLabel = when (scope) {
            "CODES_ONLY" -> "Codes only"
            "FULL" -> "Codes and one sensor snapshot"
            else -> "Unknown (older record or missing scope)"
        }
        val provenance = when (record.provenance) {
            Provenance.SIMULATED -> "SIMULATED — synthetic data, not a vehicle scan"
            Provenance.REPLAY -> "REPLAY — scripted data, not a live vehicle scan"
            Provenance.LIVE -> "LIVE — adapter session"
        }
        val outcome = when (record.outcome) {
            SessionOutcome.COMPLETE -> "Complete"
            SessionOutcome.CANCELLED -> "Cancelled"
            SessionOutcome.FAILED -> "Stopped by an error"
            SessionOutcome.DISCONNECTED -> "Disconnected"
            null -> "Unknown"
        }
        val usable = record.supportedPids is SupportedPids.Known || record.dtcKindsRead.isNotEmpty() ||
            record.dtcs.any { codePattern.matches(it.code) } ||
            record.readings.any { reading ->
                val pid = Mode01Catalog.lookup(reading.pid)
                val result = reading.result as? ReadingResult.Value
                pid != null && result != null && result.value.isFinite() && result.unit == pid.unit
            }
        return buildString {
            appendLine("BambooBD2 $appVersion")
            appendLine(provenance)
            appendLine("Started: ${formatTime(record.startedAt)}")
            appendLine("Ended: ${record.endedAt?.let(formatTime) ?: "Unknown"}")
            appendLine("Outcome: $outcome")
            appendLine("Read scope: $scopeLabel")
            appendLine(if (usable) "Usable data: Some results recorded" else "Usable data: Nothing read")
            appendLine()
            appendCodes(record, DtcKind.STORED, "Stored codes")
            appendCodes(record, DtcKind.PENDING, "Pending codes")
            appendLine()
            appendLine("Sensor readings (generic Mode 01):")
            ObdPid.entries.mapNotNull { Mode01Catalog.lookup(it.code) }.forEach { pid ->
                val label = pid.labelKey.replace('_', ' ').replaceFirstChar { it.uppercase() }
                val code = pid.pid
                val rows = record.readings.filter { it.pid == code }
                if (rows.isEmpty()) {
                    appendLine("$label (PID ${code}): ${if (scope == "CODES_ONLY") "Not requested" else "Not read"}")
                } else rows.forEach { reading ->
                    val result = when (val value = reading.result) {
                        is ReadingResult.Value -> if (value.value.isFinite() && value.unit == pid.unit) {
                            "${formatValue(value.value)} ${pid.unit} (${responder(value.ecu)})"
                        } else "No usable value"
                        ReadingResult.NoData -> "No data"
                        is ReadingResult.Failed -> "Failed (no value)"
                        ReadingResult.Unsupported -> "Not reported by vehicle"
                        ReadingResult.UnknownCapability -> "Support unknown; not requested"
                    }
                    appendLine("$label (PID ${code}): $result; captured ${formatTime(reading.atMillis)}")
                }
            }
            appendLine()
            append("Generic engine/emissions observations only. This is not a diagnosis or proof of vehicle health.")
        }
    }

    private fun StringBuilder.appendCodes(record: SessionRecord, kind: DtcKind, label: String) {
        val entries = record.dtcs.filter { it.kind == kind }
        val validated = entries.filter { codePattern.matches(it.code) }
        val incomplete = record.events.any {
            it.kind == SessionController.CODES_INCOMPLETE && it.detail.substringBefore(':') == kind.name.lowercase()
        }
        val status = when {
            kind in record.dtcKindsRead -> "Read"
            incomplete || entries.isNotEmpty() -> "Incomplete"
            else -> "Not read"
        }
        appendLine("$label: $status")
        validated.forEach { appendLine("  ${it.code} (${responder(it.ecu)})") }
        if (entries.isEmpty() && kind in record.dtcKindsRead) appendLine("  No codes reported")
        if (entries.isNotEmpty() && validated.isEmpty()) appendLine("  No validated code included")
    }

    private fun responder(value: String?): String = if (value != null && responderPattern.matches(value)) {
        "responder $value"
    } else "unknown responder"

    // toString and Kotlin rounding avoid locale dependence and java.* in pure core.
    private fun formatValue(value: Double): String {
        val scaled = value * 10.0
        return if (scaled.isFinite()) (round(scaled) / 10.0).toString() else value.toString()
    }
}
