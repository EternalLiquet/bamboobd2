package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.app.connection.VehicleStatus
import io.github.eternalliquet.bamboobd2.core.session.ReadScope

/** One scan on the shared vehicle connection; the session or recording screen shows the result. */
@Composable
fun LiveReadScreen(vm: AppViewModel, onStarted: () -> Unit, onRecording: () -> Unit, onBack: () -> Unit) {
    val state by vm.live.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    // A new entry starts narrow; saved through recreation.
    var selectedMode by rememberSaveable { mutableStateOf("codes") }
    val selectedScope = if (selectedMode == "snapshot") ReadScope.FULL else ReadScope.CODES_ONLY
    val ready = connection as? ConnectionState.Ready

    LaunchedEffect(Unit) { vm.refreshLive() }
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
        title = stringResource(R.string.scan_title),
        banner = when (selectedMode) { "snapshot" -> R.string.live_snapshot_banner; "recording" -> R.string.recording_banner; else -> R.string.live_read_banner },
        navigationIcon = { BackButton { vm.cancelLiveConnect(); onBack() } },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (ready != null) stringResource(R.string.scan_connection, ready.adapter.name, vehicleLabel(ready))
                else stringResource(R.string.scan_not_connected),
                style = MaterialTheme.typography.titleSmall,
            )
            Column(Modifier.selectableGroup()) {
                for (mode in listOf("codes", "snapshot", "recording")) {
                    val label = when (mode) { "snapshot" -> R.string.live_sensor_snapshot; "recording" -> R.string.recording_option; else -> R.string.live_codes_only }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selectedMode == mode,
                            role = Role.RadioButton,
                            onClick = { selectedMode = mode },
                        ).padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selectedMode == mode, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Text(stringResource(when (selectedMode) { "snapshot" -> R.string.live_snapshot_explainer; "recording" -> R.string.recording_explainer; else -> R.string.live_explainer }), style = MaterialTheme.typography.bodyMedium)
            (state as? LiveState.Failed)?.let {
                Text(stringResource(R.string.live_failed, it.device, it.reason), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(
                onClick = { if (selectedMode == "recording") { if (vm.startRecording()) onRecording() } else vm.startLive(selectedScope) },
                enabled = ready != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scan_start)) }
        }
    }
}

@Composable
private fun vehicleLabel(ready: ConnectionState.Ready): String {
    val context = (ready.vehicle as? VehicleStatus.Known)?.context
    val make = context?.make?.entryOrNull?.make ?: return stringResource(R.string.vc_not_identified)
    return context.profile?.let { "$make ${it.name}" } ?: stringResource(R.string.vc_model_unconfirmed, make)
}
