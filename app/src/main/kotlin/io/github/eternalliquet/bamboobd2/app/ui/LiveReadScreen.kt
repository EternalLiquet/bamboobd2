package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** Codes-only (L2) live read: pick a paired adapter; the session screen shows the result. Wiring only. */
@Composable
fun LiveReadScreen(vm: AppViewModel, onStarted: () -> Unit, onBack: () -> Unit) {
    val state by vm.live.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onLivePermissionResult)

    LaunchedEffect(Unit) { vm.refreshLiveIfIdle() }
    LaunchedEffect(state) { if (state == LiveState.Started) onStarted() }
    BackHandler {
        vm.cancelLiveConnect()
        onBack()
    }

    DiagnosticScaffold(
        title = stringResource(R.string.live_title),
        banner = R.string.live_read_banner,
        navigationIcon = { BackButton { vm.cancelLiveConnect(); onBack() } },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.live_explainer), style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                is LiveState.Available -> Availability(
                    availability = s.availability,
                    permissionDenied = s.permissionDenied,
                    onRequest = { vm.bluetoothPermission?.let(permission::launch) },
                    onRefresh = { vm.refreshLive() },
                    onPick = vm::startLive,
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
