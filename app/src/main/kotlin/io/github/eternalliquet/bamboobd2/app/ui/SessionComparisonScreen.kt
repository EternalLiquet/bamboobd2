package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.report.CodeCoverage
import io.github.eternalliquet.bamboobd2.core.report.CodeKey
import io.github.eternalliquet.bamboobd2.core.report.ComparisonBlock
import io.github.eternalliquet.bamboobd2.core.report.ComparisonResult
import io.github.eternalliquet.bamboobd2.core.report.ReadingComparison
import io.github.eternalliquet.bamboobd2.core.report.ReadingObservation
import io.github.eternalliquet.bamboobd2.core.report.ReadingStatus
import io.github.eternalliquet.bamboobd2.core.report.SessionComparison
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

/** Reloads each selected id independently; neither the picker snapshot nor a running scan supplies results. */
@Composable
fun SessionComparisonScreen(vm: AppViewModel, firstId: String, secondId: String, onBack: () -> Unit) {
    var firstLoad by remember(firstId) { mutableStateOf<Result<SessionRecord?>?>(null) }
    var secondLoad by remember(secondId) { mutableStateOf<Result<SessionRecord?>?>(null) }
    LaunchedEffect(firstId) { firstLoad = loadSaved(vm, firstId) }
    LaunchedEffect(secondId) { secondLoad = loadSaved(vm, secondId) }
    DiagnosticScaffold(title = stringResource(R.string.compare_title), navigationIcon = { BackButton(onBack) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.compare_vehicle_notice), style = MaterialTheme.typography.bodyMedium)
            val first = firstLoad?.getOrNull()
            val second = secondLoad?.getOrNull()
            when {
                firstLoad == null || secondLoad == null -> Text(stringResource(R.string.compare_loading))
                first == null || second == null -> Text(stringResource(R.string.compare_unavailable))
                else -> {
                    Text(stringResource(R.string.compare_first), style = MaterialTheme.typography.titleMedium)
                    Metadata(first)
                    Text(stringResource(R.string.compare_second), style = MaterialTheme.typography.titleMedium)
                    Metadata(second)
                    when (val result = SessionComparison.compare(first, second)) {
                        is ComparisonResult.Blocked -> Text(stringResource(
                            if (result.reason == ComparisonBlock.SAME_SESSION) R.string.compare_distinct else R.string.compare_same_provenance,
                        ))
                        is ComparisonResult.Ready -> {
                            Text(stringResource(R.string.compare_codes), style = MaterialTheme.typography.titleMedium)
                            result.codeKinds.forEach { kind ->
                                Text(if (kind.kind == DtcKind.STORED) stringResource(R.string.compare_stored) else stringResource(R.string.compare_pending),
                                    style = MaterialTheme.typography.titleSmall)
                                val differences = kind.differences
                                if (differences == null) {
                                    Text("First: ${coverage(kind.first.coverage)} — ${codes(kind.first.codes)}")
                                    Text("Second: ${coverage(kind.second.coverage)} — ${codes(kind.second.codes)}")
                                    Text(stringResource(R.string.compare_no_code_conclusion))
                                } else {
                                    Text("Reported in both: ${codes(differences.reportedInBoth)}")
                                    Text("Reported only in first: ${codes(differences.onlyFirst)}")
                                    Text("Reported only in second: ${codes(differences.onlySecond)}")
                                }
                            }
                            Text(stringResource(R.string.compare_readings), style = MaterialTheme.typography.titleMedium)
                            result.readings.forEach { row -> ReadingRow(row) }
                            Text(stringResource(R.string.compare_limitations))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metadata(record: SessionRecord) {
    Text("${record.provenance} · ${record.outcome ?: "Unknown outcome"}")
    Text("Started: ${time(record.startedAt)}")
    Text("Ended: ${record.endedAt?.let(::time) ?: "Unknown"}")
}

@Composable
private fun ReadingRow(row: ReadingComparison) {
    val label = when (row.key.pid) {
        ObdPid.ENGINE_LOAD -> stringResource(R.string.pid_04)
        ObdPid.COOLANT_TEMP -> stringResource(R.string.pid_05)
        ObdPid.ENGINE_RPM -> stringResource(R.string.pid_0c)
        ObdPid.VEHICLE_SPEED -> stringResource(R.string.pid_0d)
    }
    Text("$label (PID ${row.key.pid.code}, ${responder(row.key.ecu)})", style = MaterialTheme.typography.titleSmall)
    Text("First: ${row.first.joinToString(" | ") { observation(it, row.key.pid) }}")
    Text("Second: ${row.second.joinToString(" | ") { observation(it, row.key.pid) }}")
    val delta = row.delta
    if (delta != null) {
        val signed = if (delta == 0.0) "+0.0" else if (delta > 0) "+$delta" else delta.toString()
        Text("Second minus first: $signed ${row.key.pid.unit}")
    }
    else Text("Not comparable: ${row.reason?.name?.lowercase()?.replace('_', ' ') ?: "unknown"}")
}

private fun observation(item: ReadingObservation, pid: ObdPid): String {
    val state = when (item.status) {
        ReadingStatus.VALUE -> "${item.value} ${pid.unit}"
        ReadingStatus.INVALID_VALUE -> "${item.value ?: "No finite value"} (unit or value unverified)"
        ReadingStatus.UNSUPPORTED -> "Not reported by vehicle"
        ReadingStatus.UNKNOWN_CAPABILITY -> "Support unknown; not requested"
        ReadingStatus.NO_DATA -> "No data"
        ReadingStatus.FAILED -> "Failed (no value)"
        ReadingStatus.NOT_REQUESTED -> "Not requested"
        ReadingStatus.NOT_READ -> "Not read"
    }
    return state + (item.atMillis?.let { "; captured ${time(it)}" } ?: "")
}

private fun coverage(value: CodeCoverage): String = when (value) {
    CodeCoverage.READ -> "Read"
    CodeCoverage.INCOMPLETE -> "Incomplete"
    CodeCoverage.NOT_READ -> "Not read"
}

private val codePattern = Regex("[PCBU][0-3][0-9A-F]{3}")
private val responderPattern = Regex("(?:[0-9A-F]{2}|[0-7][0-9A-F]{2})")
private fun responder(ecu: String?): String = if (ecu != null && responderPattern.matches(ecu)) "responder $ecu" else "unknown responder"
private fun codes(items: List<CodeKey>): String = if (items.isEmpty()) "No recorded entries" else items.joinToString { key ->
    "${key.code.takeIf { codePattern.matches(it) } ?: "Unrecognized code"} (${responder(key.ecu)})"
}
private fun time(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private suspend fun loadSaved(vm: AppViewModel, id: String): Result<SessionRecord?> = try {
    Result.success(vm.load(id))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
