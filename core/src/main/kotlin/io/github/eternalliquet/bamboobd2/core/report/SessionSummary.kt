package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.DtcKind
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
        if (record.recording != null) return renderRecording(record, appVersion, formatTime)
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
            Mode01Catalog.readings.forEach { pid ->
                val label = pid.labelKey.replace('_', ' ').replace(Regex(" ([a-f])$")) { " ${it.groupValues[1].uppercase()}" }
                    .replaceFirstChar { it.uppercase() }
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
                    val captured = if (reading.result == ReadingResult.Unsupported || reading.result == ReadingResult.UnknownCapability) ""
                        else "; captured ${formatTime(reading.atMillis)}"
                    appendLine("$label (PID ${code}): $result$captured")
                }
            }
            appendLine()
            append("Generic engine/emissions observations only. This is not a diagnosis or proof of vehicle health.")
        }
    }

    private fun renderRecording(record: SessionRecord, appVersion: String, formatTime: (Long) -> String): String = buildString {
        val recording = requireNotNull(record.recording)
        appendLine("BambooBD2 $appVersion")
        appendLine("Live recording · ${record.provenance}")
        appendLine("Started: ${formatTime(record.startedAt)}")
        appendLine("Ended: ${record.endedAt?.let(formatTime) ?: "Unknown"}")
        val duration = recording.events.lastOrNull()?.elapsedMillis ?: recording.samples.maxOfOrNull { it.elapsedMillis }
        appendLine("Elapsed: ${duration?.let { "${it} ms" } ?: "Unknown"}")
        appendLine("Outcome: ${record.outcome ?: "Unknown"} (${if (record.complete) "complete" else "partial"})")
        appendLine("Stop reason: ${recording.stopReason ?: "Unknown"}")
        appendLine("Selected readings: ${recording.selectedPids.mapNotNull { Mode01Catalog.lookup(it)?.pid }.joinToString().ifEmpty { "None recorded" }}")
        appendLine("Codes were collected during preparation; sensor samples continued afterward.")
        appendCodes(record, DtcKind.STORED, "Stored codes")
        appendCodes(record, DtcKind.PENDING, "Pending codes")
        appendLine("Recorded sensor series (observed outcomes only):")
        val series = RecordingReport.series(record)
        series.take(24).forEach { row ->
            val pid = Mode01Catalog.lookup(row.key.pid)
            val unit = row.key.unit.takeIf { it == pid?.unit } ?: "unrecognized unit"
            appendLine("PID ${pid?.pid ?: "unrecognized"} · ${responder(row.key.ecu)} · $unit: " +
                "${row.observedCount} values, ${row.missingCount} missing, ${row.failureCount} failed; " +
                "first ${row.firstAtMillis?.let(formatTime) ?: "unknown"}, last ${row.lastAtMillis?.let(formatTime) ?: "unknown"}; " +
                "min ${row.minimum ?: "none"}, max ${row.maximum ?: "none"}; " +
                "last observed value ${row.lastValue?.toString() ?: "none"} at ${row.lastValueAtMillis?.let(formatTime) ?: "unknown"}")
        }
        if (series.size > 24) appendLine("Omitted ${series.size - 24} series from this summary; inspect the saved history for all outcomes.")
        append("Generic emissions observations only. Counts do not estimate a sample rate or establish vehicle health.")
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
