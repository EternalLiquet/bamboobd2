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
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.app.live.recordingSeries
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog

@Composable
fun SessionDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    var loaded by remember(id) { mutableStateOf<Result<SessionRecord?>?>(null) }
    var allHistory by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { loaded = Result.success(vm.load(id)) }
    DiagnosticScaffold(
        title = stringResource(R.string.session_title),
        provenance = loaded?.getOrNull()?.provenance,
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val record = loaded?.getOrNull()
            when {
                loaded == null -> Unit
                record == null -> Text(stringResource(R.string.session_missing))
                else -> {
                    Text("${record.provenance} · ${record.sourceLabel}", style = MaterialTheme.typography.labelLarge)
                    val recording = record.recording
                    SessionResults(record, showStatus = true, showReadings = recording == null)
                    if (recording != null) {
                        val elapsed = recording.events.lastOrNull()?.elapsedMillis
                            ?: recording.samples.maxOfOrNull { it.elapsedMillis } ?: 0L
                        Text(stringResource(R.string.saved_recording_details, elapsed / 1000,
                            stringResource(recordingStopReasonLabel(recording.stopReason)),
                            if (record.complete) stringResource(R.string.saved_recording_complete) else stringResource(R.string.saved_recording_partial)))
                        Text(stringResource(R.string.saved_recording_pauses,
                            recording.events.count { it.kind == io.github.eternalliquet.bamboobd2.core.model.LiveEventKind.PAUSE }))
                        Text(stringResource(R.string.saved_recording_selected,
                            recording.selectedPids.mapNotNull { Mode01Catalog.lookup(it)?.pid }.joinToString().ifEmpty { "—" }))
                        RecordingTrends(recordingSeries(recording.samples, recording.events, elapsed,
                            if (allHistory) null else 60_000), elapsed, allHistory) { allHistory = it }
                    }
                    SessionSummaryActions(record)
                }
            }
        }
    }
}
