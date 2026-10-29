package io.github.eternalliquet.bamboobd2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.saved.filterSavedSessions
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedSessionsScreen(vm: AppViewModel, onOpen: (String) -> Unit, onCompare: (String, String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSaved() }
    val saved by vm.saved.collectAsStateWithLifecycle()
    var codeQuery by rememberSaveable { mutableStateOf("") }
    var sourceName by rememberSaveable { mutableStateOf("") }
    val source = Provenance.entries.firstOrNull { it.name == sourceName }
    var sourceMenu by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var firstId by remember { mutableStateOf<String?>(null) }
    var secondId by remember { mutableStateOf<String?>(null) }
    fun resetPicker() { comparing = false; firstId = null; secondId = null }
    val filtered = filterSavedSessions(saved, codeQuery, source)
    val visibleReadable = filtered.filterIsInstance<StoredSession.Readable>()
    val allReadable = saved.filterIsInstance<StoredSession.Readable>()
    LaunchedEffect(visibleReadable.map { it.record.id }) {
        val ids = visibleReadable.map { it.record.id }
        if (firstId !in ids) { firstId = null; secondId = null }
        if (secondId !in ids) secondId = null
    }
    val first = visibleReadable.firstOrNull { it.record.id == firstId }?.record
    val second = visibleReadable.firstOrNull { it.record.id == secondId }?.record
    val sameSourceType = first != null && second != null && first.provenance == second.provenance
    val activeFilters = codeQuery.isNotBlank() || source != null
    BackHandler(comparing) { resetPicker() }
    DiagnosticScaffold(
        title = stringResource(R.string.saved_sessions),
        navigationIcon = { BackButton { if (comparing) resetPicker() else onBack() } },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "finder_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = codeQuery, onValueChange = { resetPicker(); codeQuery = it },
                        label = { Text(stringResource(R.string.finder_code_label)) },
                        placeholder = { Text(stringResource(R.string.finder_code_hint)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Box {
                        OutlinedButton(onClick = { sourceMenu = true }) {
                            Text(stringResource(R.string.finder_source_label, sourceLabel(source)))
                        }
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            val choices: List<Provenance?> = listOf(null) + Provenance.entries
                            choices.forEach { choice ->
                                DropdownMenuItem(text = { Text(sourceLabel(choice)) }, onClick = {
                                    sourceMenu = false
                                    resetPicker()
                                    sourceName = choice?.name ?: ""
                                })
                            }
                        }
                    }
                    OutlinedButton(onClick = { resetPicker(); codeQuery = ""; sourceName = "" }) {
                        Text(stringResource(R.string.finder_clear))
                    }
                    Text(pluralStringResource(R.plurals.finder_match_count, visibleReadable.size, visibleReadable.size))
                    if (codeQuery.isNotBlank()) Text(stringResource(R.string.finder_scope))
                    if (saved.any { it is StoredSession.Unreadable }) Text(stringResource(R.string.finder_unreadable_notice))
                    if (comparing) {
                        Text(stringResource(R.string.compare_vehicle_notice))
                        Text(stringResource(R.string.compare_picker_progress,
                            first?.let { "${it.provenance} · ${DateFormat.getDateTimeInstance().format(Date(it.startedAt))}" } ?: "—",
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
                        OutlinedButton(onClick = { comparing = true; firstId = null; secondId = null }, enabled = visibleReadable.size >= 2) {
                            Text(stringResource(R.string.compare_choose))
                        }
                        if (visibleReadable.size < 2) Text(stringResource(
                            if (activeFilters && allReadable.size >= 2) R.string.finder_compare_clear else R.string.compare_need_two,
                        ))
                    }
                    if (saved.isEmpty()) Text(stringResource(R.string.no_saved_sessions))
                    else if (activeFilters && visibleReadable.isEmpty()) Text(stringResource(R.string.finder_no_matches))
                }
            }
            items(filtered, key = { it.fileName }) { entry ->
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

@Composable
private fun sourceLabel(source: Provenance?): String = when (source) {
    null -> stringResource(R.string.finder_source_all)
    Provenance.SIMULATED -> stringResource(R.string.finder_source_simulated)
    Provenance.REPLAY -> stringResource(R.string.finder_source_replay)
    Provenance.LIVE -> stringResource(R.string.finder_source_live)
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
