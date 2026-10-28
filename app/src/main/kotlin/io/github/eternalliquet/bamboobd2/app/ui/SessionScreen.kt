package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.SaveStatus
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult

@Composable
fun SessionScreen(vm: AppViewModel, onDone: () -> Unit) {
    val state by vm.session.collectAsStateWithLifecycle()
    val saveStatuses by vm.saveStatus.collectAsStateWithLifecycle()

    // Back cancels a running session; after it finishes, back leaves the screen.
    BackHandler {
        if (state is SessionState.Running) vm.cancel() else onDone()
    }

    // Show the outcome first when the session finishes.
    val scroll = rememberScrollState()
    val finished = state is SessionState.Finished
    LaunchedEffect(finished) { if (finished) scroll.scrollTo(0) }

    val provenance = when (val s = state) {
        SessionState.Idle -> null
        is SessionState.Running -> s.record.provenance
        is SessionState.Finished -> s.record.provenance
    }
    DiagnosticScaffold(title = stringResource(R.string.session_title), provenance = provenance) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                SessionState.Idle -> Text(stringResource(R.string.reading_progress))
                is SessionState.Running -> {
                    Text(stringResource(R.string.reading_progress), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SessionResults(s.record, showStatus = false)
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is SessionState.Finished -> {
                    SessionResults(s.record, showStatus = true)
                    // Only this session's save counts, not one still finishing for an earlier session.
                    val status = saveStatuses[s.record.id]
                    val result = (status as? SaveStatus.Done)?.result
                    val saveMessage = when (result) {
                        is SaveResult.Saved -> stringResource(R.string.saved_ok)
                        is SaveResult.Failed -> stringResource(R.string.save_failed, result.reason)
                        null -> null
                    }
                    saveMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = vm::save, enabled = status != SaveStatus.Saving && result !is SaveResult.Saved, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.save))
                        }
                        OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            }
        }
    }
}
