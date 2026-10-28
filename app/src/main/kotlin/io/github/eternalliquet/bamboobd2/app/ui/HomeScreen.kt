package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario

@Composable
fun HomeScreen(onStart: (FakeScenario) -> Unit, onOpenSaved: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(FakeScenario.NO_CODES) }
    DiagnosticScaffold(title = stringResource(R.string.app_name)) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_explainer), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup()) {
                FakeScenario.entries.forEach { scenario ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = scenario == selected, onClick = { selected = scenario }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = scenario == selected, onClick = null)
                        Text(scenario.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Button(onClick = { onStart(selected) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.start))
            }
            OutlinedButton(onClick = onOpenSaved, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.saved_sessions))
            }
        }
    }
}
