package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.LiveState
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.session.ReadScope

/** One selected read scope on a paired adapter; the session screen shows the result. */
@Composable
fun LiveReadScreen(vm: AppViewModel, onStarted: () -> Unit, onRecording: () -> Unit, onBack: () -> Unit) {
    val state by vm.live.collectAsStateWithLifecycle()
    // A new entry starts narrow; save through recreation while a connection is in progress.
    var selectedMode by rememberSaveable { mutableStateOf("codes") }
    val selectedScope = if (selectedMode == "snapshot") ReadScope.FULL else ReadScope.CODES_ONLY
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onLivePermissionResult)

    LaunchedEffect(Unit) { vm.refreshLiveIfIdle() }
    // Consume the hand-off once, then show the session: a read that finished while the
    // app was in the background is shown on return, and only once.
    LaunchedEffect(state) {
        if (state == LiveState.Started) {
            vm.acknowledgeLiveStarted()
            onStarted()
        }
    }
    BackHandler {
        vm.cancelLiveConnect()
        onBack()
    }

    DiagnosticScaffold(
        title = stringResource(R.string.live_title),
        banner = when (selectedMode) { "snapshot" -> R.string.live_snapshot_banner; "recording" -> R.string.recording_banner; else -> R.string.live_read_banner },
        navigationIcon = { BackButton { vm.cancelLiveConnect(); onBack() } },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(when (selectedMode) { "snapshot" -> R.string.live_snapshot_explainer; "recording" -> R.string.recording_explainer; else -> R.string.live_explainer }), style = MaterialTheme.typography.bodyMedium)
            val canEditScope = state is LiveState.Available || state is LiveState.Failed
            Column(Modifier.selectableGroup()) {
                for (mode in listOf("codes", "snapshot", "recording")) {
                    val label = when (mode) { "snapshot" -> R.string.live_sensor_snapshot; "recording" -> R.string.recording_option; else -> R.string.live_codes_only }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selectedMode == mode,
                            enabled = canEditScope,
                            role = Role.RadioButton,
                            onClick = { selectedMode = mode },
                        ).padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selectedMode == mode, onClick = null, enabled = canEditScope)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            when (val s = state) {
                is LiveState.Available -> Availability(
                    availability = s.availability,
                    permissionDenied = s.permissionDenied,
                    onRequest = { vm.bluetoothPermission?.let(permission::launch) },
                    onRefresh = { vm.refreshLive() },
                    onPick = { device -> if (selectedMode == "recording") { vm.startRecording(device); onRecording() } else vm.startLive(device, selectedScope) },
                    pickPrompt = R.string.live_pick,
                )
                is LiveState.Connecting -> Progress(stringResource(R.string.live_connecting, s.device), vm::cancelLiveConnect)
                is LiveState.Failed -> {
                    Text(stringResource(R.string.live_failed, s.device, s.reason), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(onClick = { vm.refreshLive() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.live_again)) }
                }
                LiveState.Started -> Unit
            }
        }
    }
}
