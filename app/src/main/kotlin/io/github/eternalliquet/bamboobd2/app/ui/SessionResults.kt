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
            val status = when (record.outcome) {
                SessionOutcome.COMPLETE -> stringResource(R.string.status_complete)
                SessionOutcome.CANCELLED -> stringResource(R.string.status_cancelled)
                SessionOutcome.FAILED -> stringResource(R.string.status_failed)
                null -> stringResource(R.string.reading_progress)
            }
            Text(status, style = MaterialTheme.typography.titleMedium)
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
            val reading = record.readings.firstOrNull { it.pid == pid.code }
            val value = when (val r = reading?.result) {
                is ReadingResult.Value -> "${formatValue(r.value)} ${r.unit}"
                ReadingResult.Unsupported -> stringResource(R.string.unsupported)
                ReadingResult.UnknownCapability -> stringResource(R.string.unknown_capability)
                null -> stringResource(R.string.not_read)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(pidLabel(pid)))
                Text(value)
            }
        }
    }
}

private fun pidLabel(pid: ObdPid) = when (pid) {
    ObdPid.ENGINE_LOAD -> R.string.pid_04
    ObdPid.COOLANT_TEMP -> R.string.pid_05
    ObdPid.ENGINE_RPM -> R.string.pid_0c
    ObdPid.VEHICLE_SPEED -> R.string.pid_0d
}

private fun formatValue(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)
