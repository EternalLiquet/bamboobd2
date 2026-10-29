package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedSessionsScreen(vm: AppViewModel, onOpen: (String) -> Unit, onCompare: (String, String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSaved() }
    val saved by vm.saved.collectAsStateWithLifecycle()
    var comparing by remember { mutableStateOf(false) }
    var firstId by remember { mutableStateOf<String?>(null) }
    var secondId by remember { mutableStateOf<String?>(null) }
    val readable = saved.filterIsInstance<StoredSession.Readable>()
    LaunchedEffect(readable.map { it.record.id }) {
        if (firstId !in readable.map { it.record.id }) { firstId = null; secondId = null }
        if (secondId !in readable.map { it.record.id }) secondId = null
    }
    val first = readable.firstOrNull { it.record.id == firstId }?.record
    val second = readable.firstOrNull { it.record.id == secondId }?.record
    val sameSourceType = first != null && second != null && first.provenance == second.provenance
    fun resetPicker() { comparing = false; firstId = null; secondId = null }
    BackHandler(comparing) { resetPicker() }
    DiagnosticScaffold(
        title = stringResource(R.string.saved_sessions),
        navigationIcon = { BackButton { if (comparing) resetPicker() else onBack() } },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comparing) {
                Text(stringResource(R.string.compare_vehicle_notice))
                Text(stringResource(R.string.compare_picker_progress, first?.let { "${it.provenance} · ${DateFormat.getDateTimeInstance().format(Date(it.startedAt))}" } ?: "—",
                    second?.let { "${it.provenance} · ${DateFormat.getDateTimeInstance().format(Date(it.startedAt))}" } ?: "—"))
                if (first != null && second != null && !sameSourceType) Text(stringResource(R.string.compare_same_provenance))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::resetPicker) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = {
                        val a = requireNotNull(firstId)
                        val b = requireNotNull(secondId)
                        resetPicker()
                        onCompare(a, b)
                    }, enabled = sameSourceType && firstId != secondId) { Text(stringResource(R.string.compare_open)) }
                }
            } else {
                OutlinedButton(onClick = { comparing = true; firstId = null; secondId = null }, enabled = readable.size >= 2) {
                    Text(stringResource(R.string.compare_choose))
                }
                if (readable.size < 2) Text(stringResource(R.string.compare_need_two))
            }
            if (saved.isEmpty()) Text(stringResource(R.string.no_saved_sessions))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(saved, key = { it.fileName }) { entry ->
                    when (entry) {
                        is StoredSession.Readable -> {
                            val r = entry.record
                            val status = statusText(r)
                            ListItem(
                                headlineContent = { Text(DateFormat.getDateTimeInstance().format(Date(r.startedAt))) },
                                supportingContent = {
                                    Text(if (comparing) {
                                        val choice = when (r.id) {
                                            firstId -> stringResource(R.string.compare_first)
                                            secondId -> stringResource(R.string.compare_second)
                                            else -> stringResource(R.string.compare_select)
                                        }
                                        "$choice · ${r.provenance} · $status"
                                    } else "${r.provenance} · ${r.sourceLabel} · $status")
                                },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (!comparing) onOpen(r.id)
                                    else if (firstId == null || r.id == firstId) { firstId = if (r.id == firstId) null else r.id; secondId = null }
                                    else secondId = r.id
                                },
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
