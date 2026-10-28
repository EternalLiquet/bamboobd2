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
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord

/** Read-only rendering of a session record, used live and when reopening. */
@Composable
fun SessionResults(record: SessionRecord, showStatus: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showStatus) {
            Text(statusText(record.outcome), style = MaterialTheme.typography.titleMedium)
            // Why a session stopped, as the reader reported it.
            if (record.outcome == SessionOutcome.FAILED || record.outcome == SessionOutcome.DISCONNECTED) {
                record.events.lastOrNull()?.detail?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.stop_reason, it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text(stringResource(R.string.codes_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.codes_scope), style = MaterialTheme.typography.bodySmall)
        when {
            record.dtcs.isNotEmpty() -> record.dtcs.forEach { dtc ->
                val kind = if (dtc.kind == DtcKind.STORED) R.string.code_stored else R.string.code_pending
                Text("${dtc.code} (${stringResource(kind)})", style = MaterialTheme.typography.bodyLarge)
            }
            record.allCodesRead -> Text(stringResource(R.string.no_codes), style = MaterialTheme.typography.bodyLarge)
            else -> Text(stringResource(R.string.not_read), style = MaterialTheme.typography.bodyLarge)
        }

        HorizontalDivider()
        Text(stringResource(R.string.readings_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        ObdPid.entries.forEach { pid ->
            // One row per responder; a PID never read shows once as "Not read".
            val results = record.readings.filter { it.pid == pid.code }.map { it.result }.ifEmpty { listOf(null) }
            results.forEach { result ->
                val value = when (result) {
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
            }
        }
    }
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
