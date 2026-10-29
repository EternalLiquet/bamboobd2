package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.model.Capability
import io.github.eternalliquet.bamboobd2.core.model.capability
import io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import java.text.DateFormat
import java.util.Date

/** Read-only rendering of a session record, used live and when reopening. */
@Composable
fun SessionResults(record: SessionRecord, showStatus: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showStatus) {
            Text(statusText(record), style = MaterialTheme.typography.titleMedium)
            // The protocol the adapter reported (ATDPN), as the reader recorded it.
            record.events.lastOrNull { it.kind == ElmObdReader.PROTOCOL_NOTE }?.let {
                Text(stringResource(R.string.protocol_line, it.detail), style = MaterialTheme.typography.bodyMedium)
            }
            // Why a session stopped, as the reader reported it.
            if (record.outcome == SessionOutcome.FAILED || record.outcome == SessionOutcome.DISCONNECTED) {
                record.events.lastOrNull()?.detail?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.stop_reason, it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text(stringResource(R.string.codes_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.codes_scope), style = MaterialTheme.typography.bodySmall)
        record.dtcs.forEach { dtc ->
            val kind = if (dtc.kind == DtcKind.STORED) R.string.code_stored else R.string.code_pending
            Text("${dtc.code} (${stringResource(kind)})", style = MaterialTheme.typography.bodyLarge)
        }
        // "No codes" only when every list was read completely; otherwise say what is missing.
        when {
            record.allCodesRead -> if (record.dtcs.isEmpty()) Text(stringResource(R.string.no_codes), style = MaterialTheme.typography.bodyLarge)
            record.dtcs.isEmpty() && record.dtcKindsRead.isEmpty() -> Text(stringResource(R.string.not_read), style = MaterialTheme.typography.bodyLarge)
            else -> Text(stringResource(R.string.codes_incomplete), style = MaterialTheme.typography.bodyLarge)
        }
        // Why a list or the supported-reading map wasn't read, as the reader recorded it.
        record.events.forEach { event ->
            val reason = when (event.kind) {
                SessionController.CODES_NOT_READ -> R.string.reason_codes_not_read
                SessionController.CODES_INCOMPLETE -> R.string.reason_codes_incomplete
                ElmObdReader.SUPPORTED_PIDS_UNKNOWN -> R.string.reason_map_not_read
                else -> null
            }
            reason?.let { Text(stringResource(it, event.detail), style = MaterialTheme.typography.bodyMedium) }
        }

        HorizontalDivider()
        Text(stringResource(R.string.sensor_support_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.sensor_support_explainer), style = MaterialTheme.typography.bodySmall)
        ObdPid.entries.forEach { pid ->
            val support = when (record.supportedPids.capability(pid.code).capability) {
                Capability.SUPPORTED_AND_DECODABLE, Capability.SUPPORTED_NOT_DECODED -> R.string.sensor_support_supported
                Capability.UNSUPPORTED -> R.string.sensor_support_unsupported
                Capability.UNKNOWN -> R.string.sensor_support_unknown
            }
            Text(stringResource(R.string.sensor_support_row, stringResource(pidLabel(pid)), stringResource(support)))
        }
        HorizontalDivider()
        val isSnapshot = record.readings.isNotEmpty() ||
            record.events.any { it.kind == SessionController.READ_SCOPE && it.detail == "FULL" }
        Text(stringResource(if (isSnapshot) R.string.snapshot_heading else R.string.readings_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if ((record.supportedPids as? SupportedPids.Known)?.complete == false) {
            Text(stringResource(R.string.discovery_incomplete), style = MaterialTheme.typography.bodySmall)
        }
        if (record.events.any { it.kind == SessionController.READINGS_NOT_REQUESTED }) {
            Text(stringResource(R.string.readings_not_requested), style = MaterialTheme.typography.bodyLarge)
        } else ObdPid.entries.forEach { pid ->
            // One row per responder; a PID never read shows once as "Not read".
            val forPid = record.readings.filter { it.pid == pid.code }
            val readings: List<Reading?> = if (forPid.isEmpty()) listOf(null) else forPid
            readings.forEach { reading ->
                val value = when (val result = reading?.result) {
                    is ReadingResult.Value -> "${formatValue(result.value)} ${result.unit}" + (result.ecu?.let { " ($it)" } ?: "")
                    ReadingResult.Unsupported -> stringResource(R.string.unsupported)
                    ReadingResult.UnknownCapability -> stringResource(R.string.unknown_capability)
                    ReadingResult.NoData -> stringResource(R.string.no_data)
                    is ReadingResult.Failed -> stringResource(R.string.reading_failed, result.reason)
                    null -> stringResource(R.string.not_read)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(pidLabel(pid)))
                    Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                reading?.let {
                    Text(
                        stringResource(R.string.reading_captured_at, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.atMillis))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun statusText(record: SessionRecord): String {
    if (!record.readNothing) return statusText(record.outcome)
    // Never "Complete" or "partial results" when the car gave nothing.
    return stringResource(
        when (record.outcome) {
            SessionOutcome.COMPLETE -> R.string.status_nothing_read
            SessionOutcome.CANCELLED -> R.string.status_cancelled_nothing
            SessionOutcome.FAILED -> R.string.status_failed_nothing
            SessionOutcome.DISCONNECTED -> R.string.status_disconnected_nothing
            null -> R.string.reading_progress
        },
    )
}

@Composable
fun statusText(outcome: SessionOutcome?): String = stringResource(
    when (outcome) {
        SessionOutcome.COMPLETE -> R.string.status_complete
        SessionOutcome.CANCELLED -> R.string.status_cancelled
        SessionOutcome.FAILED -> R.string.status_failed
        SessionOutcome.DISCONNECTED -> R.string.status_disconnected
        null -> R.string.reading_progress
    },
)

private fun pidLabel(pid: ObdPid) = when (pid) {
    ObdPid.ENGINE_LOAD -> R.string.pid_04
    ObdPid.COOLANT_TEMP -> R.string.pid_05
    ObdPid.ENGINE_RPM -> R.string.pid_0c
    ObdPid.VEHICLE_SPEED -> R.string.pid_0d
}

private fun formatValue(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)
