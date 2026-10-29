package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.LiveState
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.SaveStatus
import io.github.eternalliquet.bamboobd2.app.live.recordingSeries
import io.github.eternalliquet.bamboobd2.core.live.LiveReadState
import io.github.eternalliquet.bamboobd2.core.model.*
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import kotlinx.coroutines.delay

@Composable
fun LiveRecordingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val state by vm.recording.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val pending by vm.recordingPending.collectAsStateWithLifecycle()
    val save by vm.saveStatus.collectAsStateWithLifecycle()
    var elapsed by remember { mutableLongStateOf(0L) }
    var all by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        while (state !is LiveReadState.Finished && state !is LiveReadState.Idle) {
            elapsed = vm.recordingElapsedMillis()
            delay(1000)
        }
    }
    val record = if (!pending || live is LiveState.Connecting || live is LiveState.Failed) null else when (val s = state) {
        is LiveReadState.Preparing -> s.record
        is LiveReadState.Ready -> s.record
        is LiveReadState.Recording -> s.record
        is LiveReadState.Pausing -> s.record
        is LiveReadState.Paused -> s.record
        is LiveReadState.Finished -> s.record
        LiveReadState.Idle -> null
    }
    val selection: SnapshotStateList<String> = remember(record?.id) { mutableStateListOf() }
    val finished = record != null && state is LiveReadState.Finished
    val saving = record?.let { save[it.id] == SaveStatus.Saving } == true
    val saved = record?.let { (save[it.id] as? SaveStatus.Done)?.result is SaveResult.Saved } == true
    var discardPrompt by remember { mutableStateOf(false) }
    fun leave() {
        when {
            saving -> Unit
            live is LiveState.Connecting -> { vm.cancelLiveConnect(); onDone() }
            live is LiveState.Failed -> onDone()
            !pending -> onDone()
            !finished && state !is LiveReadState.Idle -> vm.stopRecording()
            finished && !saved -> discardPrompt = true
            else -> { vm.finishRecording(); onDone() }
        }
    }
    BackHandler { leave() }
    if (discardPrompt) AlertDialog(onDismissRequest = { discardPrompt = false },
        title = { Text(stringResource(R.string.recording_discard_title)) },
        text = { Text(stringResource(R.string.recording_discard_explainer)) },
        confirmButton = { TextButton(onClick = { discardPrompt = false; vm.discardPendingRecording(); onDone() }) { Text(stringResource(R.string.recording_discard)) } },
        dismissButton = { TextButton(onClick = { discardPrompt = false }) { Text(stringResource(R.string.cancel)) } })
    DiagnosticScaffold(title = stringResource(R.string.recording_title), provenance = record?.provenance,
        banner = R.string.recording_banner,
        navigationIcon = { BackButton { leave() } }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recording_explainer))
            if (record == null) {
                Text(if (live is LiveState.Failed) stringResource(R.string.live_failed, (live as LiveState.Failed).device, (live as LiveState.Failed).reason)
                    else stringResource(if (pending) R.string.recording_connecting else R.string.recording_setup_ended))
                OutlinedButton(onClick = { vm.cancelLiveConnect(); onDone() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (pending) R.string.cancel else R.string.done)) }
            } else {
                val r = record.recording!!
                val currentElapsed = if (finished) (r.events.lastOrNull()?.elapsedMillis ?: r.samples.lastOrNull()?.elapsedMillis ?: 0L) else elapsed
                val phase = when (state) {
                    is LiveReadState.Preparing -> R.string.recording_preparing
                    is LiveReadState.Ready -> R.string.recording_ready
                    is LiveReadState.Recording -> R.string.recording_running
                    is LiveReadState.Pausing -> R.string.recording_pausing
                    is LiveReadState.Paused -> R.string.recording_paused
                    else -> R.string.recording_finished
                }
                Text(stringResource(phase), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.recording_elapsed, currentElapsed / 1000, 600))
                if (state is LiveReadState.Ready) {
                    Text(stringResource(R.string.recording_select, selection.size))
                    val known = record.supportedPids as? SupportedPids.Known
                    val advertised = known?.pages.orEmpty().flatMap { it.supportedIds }
                    (Mode01Catalog.readings.map { it.pid } + advertised).distinct().sorted().forEach { pid ->
                        val descriptor = Mode01Catalog.lookup(pid)
                        val capability = record.supportedPids.capability(pid).capability
                        val enabled = descriptor != null && capability == Capability.SUPPORTED_AND_DECODABLE
                        val label = descriptor?.let { stringResource(mode01LabelId(pid)) } ?: stringResource(R.string.sensor_pid_label, pid)
                        val selectable = enabled && (pid in selection || selection.size < 4)
                        Row(Modifier.fillMaxWidth().testTag("recording_pid_$pid").toggleable(value = pid in selection, enabled = selectable,
                            role = Role.Checkbox, onValueChange = { checked ->
                                if (checked) { if (selection.size < 4) selection.add(pid) } else selection.remove(pid)
                            }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = pid in selection, enabled = selectable, onCheckedChange = null)
                            Text(stringResource(R.string.recording_selection_label, label, pid, descriptor?.unit.orEmpty(), stringResource(supportLabel(capability))), modifier = Modifier.weight(1f))
                        }
                        known?.pages?.mapNotNull { it.ecu }?.distinct()?.forEach { ecu ->
                            Text(stringResource(R.string.sensor_support_ecu_row, label, ecu,
                                stringResource(supportLabel(record.supportedPids.capability(pid, ecu).capability, true))),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = { vm.startSelectedRecording(selection.toList()) }, enabled = selection.size in 1..4,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_start)) }
                }
                if (state is LiveReadState.Recording) OutlinedButton(onClick = { vm.pauseRecording() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_pause)) }
                if (state is LiveReadState.Paused) OutlinedButton(onClick = { vm.resumeRecording() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_resume)) }
                if (!finished) OutlinedButton(onClick = { vm.stopRecording() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_stop)) }
                if (finished) Text(stringResource(R.string.recording_end_reason, stringResource(recordingStopReasonLabel(r.stopReason))))
                if (r.selectedPids.isNotEmpty()) {
                    r.selectedPids.forEach { pid ->
                        val label = stringResource(mode01LabelId(pid))
                        val rows = r.samples.filter { it.pid == pid }
                        val responders = (record.supportedPids as? SupportedPids.Known)?.pages.orEmpty()
                            .mapNotNull { it.ecu }.distinct().filter { record.supportedPids.capability(pid, it).capability == Capability.SUPPORTED_AND_DECODABLE }
                        Text(stringResource(R.string.recording_signal_label, label, pid, Mode01Catalog.lookup(pid)?.unit.orEmpty()), style = MaterialTheme.typography.titleSmall)
                        (responders + rows.map { it.ecu }).distinct().forEach { ecu ->
                            val last = rows.lastOrNull { it.ecu == ecu }
                            val good = rows.lastOrNull { it.ecu == ecu && it.outcome == LiveSampleOutcome.VALUE }
                            val age = good?.let { currentElapsed - it.elapsedMillis }
                            val status = when {
                                last == null -> stringResource(R.string.recording_waiting)
                                last.outcome == LiveSampleOutcome.MISSING -> stringResource(R.string.recording_missing)
                                last.outcome == LiveSampleOutcome.FAILURE -> stringResource(R.string.recording_failed, last.reason ?: "")
                                age != null && age > 5000 -> stringResource(R.string.recording_stale)
                                else -> stringResource(R.string.recording_current)
                            }
                            val parts = mutableListOf(stringResource(R.string.recording_responder_status, ecu ?: stringResource(R.string.recording_unknown_responder), status))
                            if (age != null && age > 5000 && last?.outcome != LiveSampleOutcome.VALUE) parts += stringResource(R.string.recording_stale)
                            good?.let { parts += stringResource(R.string.recording_last_value, it.value.toString(), it.unit,
                                stringResource(R.string.recording_age, (age ?: 0L).coerceAtLeast(0) / 1000)) }
                            Text(parts.joinToString(" · "))
                        }
                    }
                    Text(stringResource(R.string.recording_stale_explainer), style = MaterialTheme.typography.bodySmall)
                    RecordingTrends(recordingSeries(r.samples, r.events, currentElapsed, if (all) null else 60_000), currentElapsed, all) { all = it }
                }
                if (finished) {
                    val status = save[record.id]
                    val result = (status as? SaveStatus.Done)?.result
                    if (status == SaveStatus.Saving) Text(stringResource(R.string.recording_saving))
                    if (result is SaveResult.Saved) Text(stringResource(R.string.saved_ok))
                    if (result is SaveResult.Failed) Text(stringResource(R.string.save_failed, result.reason))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::saveRecording, enabled = status != SaveStatus.Saving && result !is SaveResult.Saved,
                            modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
                        OutlinedButton(onClick = { leave() }, enabled = !saving, modifier = Modifier.weight(1f)) { Text(stringResource(if (saved) R.string.done else R.string.recording_discard)) }
                    }
                }
            }
        }
    }
}

internal fun recordingStopReasonLabel(reason: LiveStopReason?) = when (reason) {
    LiveStopReason.USER -> R.string.recording_stop_user
    LiveStopReason.DURATION_LIMIT -> R.string.recording_stop_duration
    LiveStopReason.WRITE_LIMIT -> R.string.recording_stop_writes
    LiveStopReason.RETRY_LIMIT -> R.string.recording_stop_retries
    LiveStopReason.SAMPLE_LIMIT -> R.string.recording_stop_samples
    LiveStopReason.CANCELLED -> R.string.recording_stop_cancelled
    LiveStopReason.DISCONNECTED -> R.string.recording_stop_disconnected
    LiveStopReason.FAILED -> R.string.recording_stop_failed
    null -> R.string.recording_stop_unknown
}
