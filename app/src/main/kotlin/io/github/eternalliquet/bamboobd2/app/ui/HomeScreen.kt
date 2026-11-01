package io.github.eternalliquet.bamboobd2.app.ui

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.app.connection.VehicleStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.VehicleProfile
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario

@Composable
fun HomeScreen(
    replays: List<ReplayScript>,
    liveResultPending: Boolean,
    pendingRecording: Boolean = false,
    connection: ConnectionState = ConnectionState.NotSetUp,
    profiles: List<VehicleProfile> = emptyList(),
    /** A scan or recording holds the connection. */
    connectionBusy: Boolean = false,
    vehicleActions: VehicleActions? = null,
    onStart: (FakeScenario) -> Unit,
    onStartReplay: (String) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenBench: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenExperiment: () -> Unit,
    onOpenPendingLive: () -> Unit,
    onDiscardPendingLive: () -> Unit,
) {
    // "fake:<scenario>" or "replay:<script id>", so the choice survives recreation.
    var selected by rememberSaveable { mutableStateOf("fake:${FakeScenario.NO_CODES.name}") }
    DiagnosticScaffold(title = stringResource(R.string.app_name), banner = R.string.home_banner) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A live read that was never opened: keep it reachable, and let only the user drop it.
            if (liveResultPending) {
                Text(stringResource(if (pendingRecording) R.string.pending_recording else R.string.pending_live), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenPendingLive, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (pendingRecording) R.string.pending_recording_open else R.string.pending_live_open)) }
                OutlinedButton(onClick = onDiscardPendingLive, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (pendingRecording) R.string.pending_recording_discard else R.string.pending_live_discard)) }
            }
            if (vehicleActions != null) {
                VehicleCard(connection, profiles, connectionBusy, vehicleActions)
                val ready = connection as? ConnectionState.Ready
                Text(stringResource(R.string.scan_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(if (ready != null) R.string.scan_explainer else R.string.scan_connect_first), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenLive, enabled = ready != null && !liveResultPending, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_open))
                }
                ((ready?.vehicle as? VehicleStatus.Known)?.context)?.let { ManufacturerCard(it, onOpenExperiment) }
            }
            OutlinedButton(onClick = onOpenSaved, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.saved_sessions))
            }
            Text(stringResource(R.string.practice_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            Text(stringResource(R.string.home_explainer), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.simulated_heading), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                FakeScenario.entries.forEach { ChoiceRow("fake:${it.name}", it.label, selected) { key -> selected = key } }
                Text(stringResource(R.string.replay_heading), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                Text(stringResource(R.string.replay_explainer), style = MaterialTheme.typography.bodySmall)
                replays.forEach { ChoiceRow("replay:${it.id}", it.label, selected) { key -> selected = key } }
            }
            Button(
                onClick = {
                    val (kind, name) = selected.split(":", limit = 2)
                    if (kind == "replay") onStartReplay(name) else onStart(FakeScenario.valueOf(name))
                },
                enabled = !liveResultPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start))
            }
            Text(stringResource(R.string.tools_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            OutlinedButton(onClick = onOpenBench, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bench_open))
            }
        }
    }
}

@Composable
private fun ChoiceRow(key: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = key == selected, onClick = { onSelect(key) }, role = Role.RadioButton)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = key == selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
