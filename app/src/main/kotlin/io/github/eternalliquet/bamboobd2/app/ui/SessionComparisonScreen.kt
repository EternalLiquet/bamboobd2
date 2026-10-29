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
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.report.CodeCoverage
import io.github.eternalliquet.bamboobd2.core.report.CodeKey
import io.github.eternalliquet.bamboobd2.core.report.ComparisonBlock
import io.github.eternalliquet.bamboobd2.core.report.ComparisonResult
import io.github.eternalliquet.bamboobd2.core.report.NotComparable
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
                                    Text(stringResource(R.string.compare_side_codes, stringResource(R.string.compare_first_short),
                                        coverage(kind.first.coverage), codes(kind.first.codes)))
                                    Text(stringResource(R.string.compare_side_codes, stringResource(R.string.compare_second_short),
                                        coverage(kind.second.coverage), codes(kind.second.codes)))
                                    Text(stringResource(R.string.compare_no_code_conclusion))
                                } else {
                                    Text(stringResource(R.string.compare_reported_both, codes(differences.reportedInBoth)))
                                    Text(stringResource(R.string.compare_reported_first, codes(differences.onlyFirst)))
                                    Text(stringResource(R.string.compare_reported_second, codes(differences.onlySecond)))
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
    Text(stringResource(R.string.compare_source_outcome, record.provenance.toString(),
        record.outcome?.toString() ?: stringResource(R.string.compare_unknown_outcome)))
    Text(stringResource(R.string.compare_started, time(record.startedAt)))
    Text(stringResource(R.string.compare_ended, record.endedAt?.let(::time) ?: stringResource(R.string.compare_unknown_time)))
}

@Composable
private fun ReadingRow(row: ReadingComparison) {
    val reading = checkNotNull(Mode01Catalog.lookup(row.key.pid))
    val label = stringResource(mode01LabelId(row.key.pid))
    Text(stringResource(R.string.compare_reading_label, label, row.key.pid, responder(row.key.ecu)), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.compare_side_readings, stringResource(R.string.compare_first_short), observations(row.first, reading.unit)))
    Text(stringResource(R.string.compare_side_readings, stringResource(R.string.compare_second_short), observations(row.second, reading.unit)))
    val delta = row.delta
    if (delta != null) {
        val signed = if (delta == 0.0) "+0.0" else if (delta > 0) "+$delta" else delta.toString()
        Text(stringResource(R.string.compare_delta, signed, reading.unit))
    }
    else Text(stringResource(R.string.compare_not_comparable, stringResource(reasonLabel(row.reason))))
}

@Composable
private fun observations(items: List<ReadingObservation>, unit: String): String {
    val parts = mutableListOf<String>()
    for (item in items) parts += observation(item, unit)
    return parts.joinToString(" | ")
}

@Composable
private fun observation(item: ReadingObservation, unit: String): String {
    val state = when (item.status) {
        ReadingStatus.VALUE -> stringResource(R.string.compare_value, item.value.toString(), unit)
        ReadingStatus.INVALID_VALUE -> stringResource(R.string.compare_invalid_value,
            item.value?.toString() ?: stringResource(R.string.compare_no_finite_value))
        ReadingStatus.UNSUPPORTED -> stringResource(R.string.compare_unsupported)
        ReadingStatus.UNKNOWN_CAPABILITY -> stringResource(R.string.compare_unknown_capability)
        ReadingStatus.NO_DATA -> stringResource(R.string.compare_no_data)
        ReadingStatus.FAILED -> stringResource(R.string.compare_failed)
        ReadingStatus.NOT_REQUESTED -> stringResource(R.string.compare_not_requested)
        ReadingStatus.NOT_READ -> stringResource(R.string.compare_not_read)
    }
    return item.atMillis?.let { stringResource(R.string.compare_captured, state, time(it)) } ?: state
}

@Composable
private fun coverage(value: CodeCoverage): String = stringResource(when (value) {
    CodeCoverage.READ -> R.string.compare_read
    CodeCoverage.INCOMPLETE -> R.string.compare_incomplete
    CodeCoverage.NOT_READ -> R.string.compare_not_read
})

private fun reasonLabel(reason: NotComparable?): Int = when (reason) {
    NotComparable.MISSING_SIDE -> R.string.compare_reason_missing_side
    NotComparable.UNKNOWN_RESPONDER -> R.string.compare_reason_unknown_responder
    NotComparable.DUPLICATE -> R.string.compare_reason_duplicate
    // Defensive label for future status-keyed rows; current non-null ECU groups contain Value results.
    NotComparable.NON_VALUE -> R.string.compare_reason_non_value
    NotComparable.INVALID_VALUE -> R.string.compare_reason_invalid_value
    NotComparable.NON_FINITE_DELTA -> R.string.compare_reason_non_finite_delta
    null -> R.string.compare_reason_unknown
}

private val codePattern = Regex("[PCBU][0-3][0-9A-F]{3}")
private val responderPattern = Regex("(?:[0-9A-F]{2}|[0-7][0-9A-F]{2})")
@Composable
private fun responder(ecu: String?): String = if (ecu != null && responderPattern.matches(ecu)) {
    stringResource(R.string.compare_responder, ecu)
} else stringResource(R.string.compare_unknown_responder)

@Composable
private fun codes(items: List<CodeKey>): String {
    if (items.isEmpty()) return stringResource(R.string.compare_no_entries)
    val labels = mutableListOf<String>()
    for (key in items) {
        labels += stringResource(R.string.compare_code_entry,
            key.code.takeIf { codePattern.matches(it) } ?: stringResource(R.string.compare_unrecognized_code), responder(key.ecu))
    }
    return labels.joinToString()
}
private fun time(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private suspend fun loadSaved(vm: AppViewModel, id: String): Result<SessionRecord?> = try {
    Result.success(vm.load(id))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
