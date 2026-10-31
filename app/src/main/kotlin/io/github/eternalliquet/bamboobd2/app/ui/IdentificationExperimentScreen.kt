package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.experiment.ExperimentResult
import io.github.eternalliquet.bamboobd2.core.experiment.IdentificationExperiment
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Review-only: no Bluetooth dependency, transport selection, or live activation switch. */
@Composable
fun IdentificationExperimentScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExperimentResult?>(null) }
    val leave = { job?.cancel(); onBack() }
    BackHandler(onBack = leave)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { job?.cancel(); lifecycle.removeObserver(observer) }
    }
    DiagnosticScaffold(
        title = stringResource(R.string.experiment_title),
        provenance = Provenance.SIMULATED,
        navigationIcon = { BackButton(onBack = leave) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.experiment_locked), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.experiment_explainer))
            Button(onClick = {
                busy = true
                result = null
                job = scope.launch {
                    try { result = IdentificationExperiment.rehearse(onUpdate = { result = it }) }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.experiment_rehearse))
            }
            if (busy) OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.experiment_stop))
            }
            result?.let { current ->
                Text("${current.outcome}: ${current.explanation}")
                Text(stringResource(R.string.experiment_transcript), style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(buildString {
                        current.recording.steps.forEachIndexed { index, step ->
                            appendLine("${index + 1}. TX ${step.request}")
                            step.chunks.forEach { appendLine("  +${it.atMillis}ms RX ${it.text.replace("\r", "\\r").replace("\n", "\\n")}") }
                        }
                    }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.experiment_plan), style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(remember { IdentificationExperiment.preview() }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
