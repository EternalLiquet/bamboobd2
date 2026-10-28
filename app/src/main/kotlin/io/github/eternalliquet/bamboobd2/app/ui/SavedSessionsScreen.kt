package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedSessionsScreen(vm: AppViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSaved() }
    val saved by vm.saved.collectAsStateWithLifecycle()
    DiagnosticScaffold(
        title = stringResource(R.string.saved_sessions),
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        if (saved.isEmpty()) {
            Text(stringResource(R.string.no_saved_sessions), modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(saved, key = { it.fileName }) { entry ->
                    when (entry) {
                        is StoredSession.Readable -> {
                            val r = entry.record
                            val status = if (r.outcome == SessionOutcome.COMPLETE) {
                                stringResource(R.string.status_complete)
                            } else {
                                stringResource(R.string.status_cancelled)
                            }
                            ListItem(
                                headlineContent = { Text(DateFormat.getDateTimeInstance().format(Date(r.startedAt))) },
                                supportingContent = { Text("${r.provenance} · ${r.sourceLabel} · $status") },
                                modifier = Modifier.fillMaxWidth().clickable { onOpen(r.id) },
                            )
                        }
                        is StoredSession.Unreadable -> ListItem(
                            headlineContent = { Text(stringResource(R.string.unreadable_file, entry.fileName, entry.reason)) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
