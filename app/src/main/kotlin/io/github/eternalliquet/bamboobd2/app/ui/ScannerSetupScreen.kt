package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability

/** Pick one paired scanner. Saving it returns to the home screen's normal connection flow. */
@Composable
fun ScannerSetupScreen(vm: AppViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    var availability by remember { mutableStateOf<BluetoothAvailability>(BluetoothAvailability.PermissionNeeded) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        availability = vm.bluetoothAvailability()
    }
    LaunchedEffect(Unit) { availability = vm.bluetoothAvailability() }
    DiagnosticScaffold(
        title = stringResource(R.string.setup_title),
        banner = R.string.home_banner,
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.setup_explainer), style = MaterialTheme.typography.bodyMedium)
            Availability(
                availability = availability,
                permissionDenied = denied,
                onRequest = { vm.bluetoothPermission?.let(permission::launch) },
                onRefresh = { availability = vm.bluetoothAvailability() },
                onPick = { device -> vm.chooseScanner(device); onDone() },
                pickPrompt = R.string.setup_pick,
            )
        }
    }
}
