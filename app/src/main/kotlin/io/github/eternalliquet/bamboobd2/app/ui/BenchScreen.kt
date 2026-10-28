package io.github.eternalliquet.bamboobd2.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.BenchState
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.core.bench.BenchOutcome
import io.github.eternalliquet.bamboobd2.core.bench.BenchResult
import io.github.eternalliquet.bamboobd2.core.bench.BenchStop

/** The adapter-only bench check (L1 path). Display and wiring only; the check itself is in :core. */
@Composable
fun BenchScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.bench.collectAsStateWithLifecycle()
    val busy = state is BenchState.Connecting || state is BenchState.Running
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onPermissionResult)

    // A finished result survives rotation and re-entry; Refresh or Run again clears it.
    LaunchedEffect(Unit) { vm.refreshBluetoothIfIdle() }
    // Leaving the screen stops a running check rather than leaving it in the background.
    BackHandler {
        if (busy) vm.cancelBench()
        onBack()
    }

    DiagnosticScaffold(
        title = stringResource(R.string.bench_title),
        banner = R.string.bench_banner,
        navigationIcon = { BackButton { if (busy) vm.cancelBench(); onBack() } },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bench_explainer), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.bench_platform, vm.platform), style = MaterialTheme.typography.bodyLarge)
            CopyButton(text = vm.platform)

            when (val s = state) {
                is BenchState.Available -> Availability(s, onRequest = { vm.bluetoothPermission?.let(permission::launch) }, vm = vm)
                is BenchState.Connecting -> Progress(stringResource(R.string.bench_connecting, s.device), vm::cancelBench)
                is BenchState.Running -> Progress(stringResource(R.string.bench_running, s.device), vm::cancelBench)
                is BenchState.Failed -> {
                    Text(stringResource(R.string.bench_failed, s.device, s.reason), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(onClick = { vm.refreshBluetooth() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bench_again)) }
                }
                is BenchState.Done -> {
                    Result(s.device, s.result)
                    CopyButton(text = vm.benchReport(s.result))
                    OutlinedButton(onClick = { vm.refreshBluetooth() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bench_again)) }
                }
            }
        }
    }
}

@Composable
private fun Availability(s: BenchState.Available, onRequest: () -> Unit, vm: AppViewModel) {
    when (val a = s.availability) {
        BluetoothAvailability.NoAdapter -> Text(stringResource(R.string.bench_no_adapter))
        BluetoothAvailability.PermissionNeeded -> {
            Text(stringResource(if (s.permissionDenied) R.string.bench_permission_denied else R.string.bench_permission_needed))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bench_permission_request)) }
        }
        BluetoothAvailability.Off -> Text(stringResource(R.string.bench_off))
        is BluetoothAvailability.Ready -> {
            if (a.bonded.isEmpty()) {
                Text(stringResource(R.string.bench_no_paired))
            } else {
                Text(stringResource(R.string.bench_pick))
                a.bonded.forEach { device ->
                    OutlinedButton(onClick = { vm.startBench(device) }, modifier = Modifier.fillMaxWidth()) { Text(device.name) }
                }
            }
        }
    }
    OutlinedButton(onClick = { vm.refreshBluetooth() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bench_refresh)) }
}

@Composable
private fun Progress(text: String, onCancel: () -> Unit) {
    Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun Result(device: String, result: BenchResult) {
    val stop = stringResource(
        when (result.stop) {
            BenchStop.COMPLETE -> R.string.bench_stop_complete
            BenchStop.NO_RESPONSE -> R.string.bench_stop_no_response
            BenchStop.DISCONNECTED -> R.string.bench_stop_disconnected
            BenchStop.CANCELLED -> R.string.bench_stop_cancelled
        },
    )
    Text(stringResource(R.string.bench_result, device, stop), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    result.steps.forEach { step ->
        val outcome = when (val o = step.outcome) {
            is BenchOutcome.Reply -> o.lines.joinToString(" | ").ifEmpty { stringResource(R.string.bench_empty_reply) }
            is BenchOutcome.NoReply -> stringResource(R.string.bench_no_reply) + if (o.partial.isNotEmpty()) ": ${o.partial}" else ""
            is BenchOutcome.Interrupted -> stringResource(R.string.bench_interrupted) + if (o.partial.isNotEmpty()) ": ${o.partial}" else ""
            BenchOutcome.WriteInterrupted -> stringResource(R.string.bench_write_interrupted)
            BenchOutcome.NotRun -> stringResource(R.string.bench_not_run)
        }
        Text("${step.command}: $outcome", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CopyButton(text: String) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("BambooBD2", text))
            copied = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (copied) R.string.bench_copied else R.string.bench_copy)) }
}
