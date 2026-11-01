package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.experiment.ExperimentOutcome
import io.github.eternalliquet.bamboobd2.core.experiment.IdentificationExperiment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owner-triggered fixed reads only; screen exit/background closes the owned connection. */
@Composable
fun IdentificationExperimentScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.experiment.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    // Only when this connection's identity offers the experiment; recomputed as it changes.
    val adapter = remember(connection) { vm.experimentAdapter() }
    val reuseRequired by vm.experimentReuseRequired.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf("") }
    var reviewExport by remember { mutableStateOf(false) }
    var identifiersReviewed by remember { mutableStateOf(false) }
    var previousReport by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val reviewedReport = vm.takeExperimentExport()
        if (uri != null && reviewedReport == null) {
            exportMessage = "The reviewed report is no longer available. Reopen the private report and export again; no data was written."
        } else if (uri != null && reviewedReport != null) scope.launch {
            exportMessage = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(reviewedReport) }
                }
                "Report exported. Review unexpected identifiers before sharing."
            } catch (_: Exception) { "Could not export the report. Your private copy is unchanged." }
        }
    }
    LaunchedEffect(Unit) { previousReport = vm.savedExperimentReport() }
    val leave = { vm.stopExperiment(); onBack() }
    BackHandler(onBack = leave)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stopExperiment() }
        lifecycle.addObserver(observer)
        onDispose { vm.stopExperiment(); lifecycle.removeObserver(observer) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80CBC4), background = Color(0xFF111519), surface = Color(0xFF171D22))) {
        if (reviewExport) AlertDialog(
            onDismissRequest = { reviewExport = false },
            title = { Text("Review before exporting") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Raw replies can contain unexpected identifiers. Export only to a destination you trust; this report is not automatically redacted.")
                    SelectionContainer {
                        Text(exportText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    }
                    Row {
                        Checkbox(identifiersReviewed, onCheckedChange = { identifiersReviewed = it })
                        Text("I reviewed this report for private identifiers.")
                    }
                }
            },
            confirmButton = { Button(enabled = identifiersReviewed, onClick = {
                if (vm.prepareExperimentExport(exportText)) {
                    reviewExport = false
                    export.launch("BambooBD2-identification.json")
                } else exportMessage = "No report is available to export."
            }) { Text("Choose export destination") } },
            dismissButton = { TextButton(onClick = { reviewExport = false }) { Text("Cancel") } },
        )
        DiagnosticScaffold(
            title = "Cayenne identification",
            banner = R.string.experiment_live_banner,
            navigationIcon = { BackButton(onBack = leave) },
        ) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Read complete part numbers", style = MaterialTheme.typography.headlineSmall)
                Text("Read part-number information from two candidate modules. Their identities are not yet confirmed.")
                Text("Each module gets one read and, if needed, one message asking for the rest of its reply. No vehicle settings are changed.")
                TextButton(onClick = { showPlan = !showPlan }) { Text(if (showPlan) "Hide exact requests and setup" else "Review exact requests and setup") }
                if (showPlan) SelectionContainer {
                    Text(IdentificationExperiment.preview(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.result == null) "Connecting to scanner…" else "Reading module replies…")
                    Button(onClick = vm::stopExperiment, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop and disconnect") }
                } else {
                    if (reuseRequired) {
                        Text("Unplug and reconnect the scanner before any further scan. Closing Bluetooth does not restore adapter settings.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { vm.confirmExperimentAdapterPowerCycle(); reviewed = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("I unplugged and reconnected the scanner")
                        }
                    } else if (adapter == null) {
                        Text("Available only while connected to a car identified as a Porsche, with the second-generation (92A) Cayenne confirmed. Go back and connect first.")
                    } else {
                        adapter.let { device ->
                            Text("Scanner: ${device.name}. Starting closes the normal connection and opens a separate one for this experiment.", style = MaterialTheme.typography.titleMedium)
                            Text("Park securely, parking brake applied, ignition on and engine off. Use stable power and close other diagnostic apps. Stop for warnings, display disturbance or abnormal sounds.")
                            Row {
                                Checkbox(checked = reviewed, onCheckedChange = { reviewed = it })
                                Text("I reviewed the exact test and approve these two reads and their flow-control frames now. The car is parked and ready.", modifier = Modifier.padding(top = 8.dp))
                            }
                            Button(onClick = {
                                if (vm.startExperiment(reviewed)) { reviewed = false; showTranscript = false; exportMessage = null }
                                else exportMessage = "Another session is active, or the adapter needs a power cycle. Finish that before starting."
                            }, enabled = reviewed, modifier = Modifier.fillMaxWidth()) { Text("Connect and run two reads") }
                        }
                    }
                    if (state.result != null || state.phase != "Choose your paired scanner") {
                        Text(when (state.result?.outcome) {
                            ExperimentOutcome.OBSERVED -> "Read attempt finished"
                            ExperimentOutcome.CANCELLED -> "Read cancelled"
                            else -> "Read stopped"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(if (state.result?.outcome == ExperimentOutcome.OBSERVED)
                            "Reply details are available below. Export the report for module identification; this does not confirm maintenance support."
                        else "The attempt ended before completion. Open details for the reason, or export the report for analysis.")
                        if (state.saved) Text("Report saved privately on this phone.")
                        state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick = { exportText = vm.experimentReport(); identifiersReviewed = false; reviewExport = true }, modifier = Modifier.fillMaxWidth()) { Text("Export private report") }
                        // The redacted connection log, with timing, for an attempt that didn't finish.
                        if (state.result?.outcome != ExperimentOutcome.OBSERVED) DiagnosticExportButton(vm)
                    } else if (previousReport != null) {
                        OutlinedButton(onClick = { exportText = previousReport!!; identifiersReviewed = false; reviewExport = true }, modifier = Modifier.fillMaxWidth()) { Text("Export previous private report") }
                    }
                }
                if (state.result != null || state.phase != "Choose your paired scanner") {
                    TextButton(onClick = { showTranscript = !showTranscript }) { Text(if (showTranscript) "Hide reply details" else "Show reply details") }
                    if (showTranscript) SelectionContainer {
                        Text(state.result?.privateReport() ?: state.phase, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                exportMessage?.let { Text(it) }
            }
        }
    }
}
