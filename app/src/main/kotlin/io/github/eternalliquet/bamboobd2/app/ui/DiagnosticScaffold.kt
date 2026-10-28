package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.model.Provenance

const val PROVENANCE_BANNER_TAG = "provenance_banner"

/**
 * Every screen renders inside this scaffold, so a provenance banner is always
 * visible (F10). Milestone 1 has no live data, so the banner is unconditional:
 * it names the shown session's provenance, or says the app is offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScaffold(
    title: String,
    provenance: Provenance? = null,
    navigationIcon: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, modifier = Modifier.semantics { heading() }) },
                    navigationIcon = navigationIcon,
                )
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            when (provenance) {
                                Provenance.SIMULATED -> R.string.simulated_banner
                                Provenance.REPLAY -> R.string.replay_banner
                                null -> R.string.offline_banner
                            },
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(PROVENANCE_BANNER_TAG),
                    )
                }
            }
        },
        bottomBar = bottomBar,
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}
