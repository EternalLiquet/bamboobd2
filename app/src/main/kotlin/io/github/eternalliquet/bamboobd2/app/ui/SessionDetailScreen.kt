package io.github.eternalliquet.bamboobd2.app.ui

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
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord

@Composable
fun SessionDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    var loaded by remember(id) { mutableStateOf<Result<SessionRecord?>?>(null) }
    LaunchedEffect(id) { loaded = Result.success(vm.load(id)) }
    DiagnosticScaffold(
        title = stringResource(R.string.session_title),
        provenance = loaded?.getOrNull()?.provenance,
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val record = loaded?.getOrNull()
            when {
                loaded == null -> Unit
                record == null -> Text(stringResource(R.string.session_missing))
                else -> {
                    Text("${record.provenance} · ${record.sourceLabel}", style = MaterialTheme.typography.labelLarge)
                    SessionResults(record, showStatus = true)
                    SessionSummaryActions(record)
                }
            }
        }
    }
}
