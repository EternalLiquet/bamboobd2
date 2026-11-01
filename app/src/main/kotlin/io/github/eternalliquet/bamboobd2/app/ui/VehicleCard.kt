package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.connection.Blocker
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import io.github.eternalliquet.bamboobd2.app.connection.VehicleStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.CapabilityStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.MakeEvidence
import io.github.eternalliquet.bamboobd2.core.manufacturer.VehicleContext
import io.github.eternalliquet.bamboobd2.core.manufacturer.VehicleProfile
import io.github.eternalliquet.bamboobd2.core.vehicle.VinRead
import io.github.eternalliquet.bamboobd2.core.vehicle.YearEvidence

/** The actions the vehicle card can trigger; all are plain user requests to the ViewModel. */
class VehicleActions(
    val choose: () -> Unit,
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val forget: () -> Unit,
    val requestPermission: () -> Unit,
    val identifyAgain: () -> Unit,
    val confirmProfile: (String?) -> Unit,
    val openExperiment: () -> Unit,
)

/** Connect → identify → ready, or one actionable problem. Technical text sits behind a toggle. */
@Composable
fun VehicleCard(state: ConnectionState, profiles: List<VehicleProfile>, busy: Boolean, actions: VehicleActions) {
    var details by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.vc_heading), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
            when (state) {
                ConnectionState.NotSetUp -> {
                    Status(stringResource(R.string.vc_not_set_up), stringResource(R.string.vc_not_set_up_explainer))
                    Button(onClick = actions.choose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_choose)) }
                }
                is ConnectionState.Blocked -> Blocked(state, actions)
                is ConnectionState.Disconnected -> {
                    Status(stringResource(R.string.vc_not_connected), state.reason)
                    Button(onClick = actions.connect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_connect)) }
                }
                is ConnectionState.Connecting -> Working(stringResource(R.string.vc_connecting, state.adapter.name), null, actions.disconnect)
                is ConnectionState.Identifying -> Working(stringResource(R.string.vc_identifying), stringResource(R.string.vc_identifying_explainer), actions.disconnect)
                is ConnectionState.Ready -> when (val v = state.vehicle) {
                    is VehicleStatus.NoAnswer -> {
                        Status(stringResource(R.string.vc_no_answer), v.reason)
                        Button(onClick = actions.identifyAgain, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_identify_again)) }
                    }
                    is VehicleStatus.Known -> Identity(v.context, profiles, busy) { choosing = true }
                }
            }
            val adapter = when (state) {
                ConnectionState.NotSetUp -> null
                is ConnectionState.Blocked -> state.adapter
                is ConnectionState.Disconnected -> state.adapter
                is ConnectionState.Connecting -> state.adapter
                is ConnectionState.Identifying -> state.adapter
                is ConnectionState.Ready -> state.adapter
            }
            if (adapter != null) {
                HorizontalDivider()
                Text(stringResource(R.string.vc_scanner, adapter.name), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state is ConnectionState.Ready) OutlinedButton(onClick = actions.disconnect, enabled = !busy) { Text(stringResource(R.string.vc_disconnect)) }
                    TextButton(onClick = actions.choose, enabled = !busy) { Text(stringResource(R.string.vc_change)) }
                    TextButton(onClick = actions.forget, enabled = !busy) { Text(stringResource(R.string.vc_forget)) }
                }
                val technical = technicalDetails(state)
                if (technical.isNotEmpty()) {
                    TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.vc_details_hide else R.string.vc_details_show)) }
                    if (details) SelectionContainer {
                        Text(technical, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
    }
    val known = ((state as? ConnectionState.Ready)?.vehicle as? VehicleStatus.Known)?.context
    if (choosing && known != null) ProfileDialog(known, profiles, onDismiss = { choosing = false }) {
        choosing = false
        actions.confirmProfile(it)
    }
}

@Composable
private fun Status(title: String, explanation: String?) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Working(title: String, explanation: String?, onCancel: () -> Unit) {
    Status(title, explanation)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun Blocked(state: ConnectionState.Blocked, actions: VehicleActions) {
    val name = state.adapter.name
    when (state.blocker) {
        Blocker.PERMISSION_NEEDED -> {
            Status(stringResource(R.string.vc_permission_needed), stringResource(R.string.vc_permission_needed_explainer))
            Button(onClick = actions.requestPermission, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_permission_allow)) }
        }
        Blocker.PERMISSION_DENIED -> {
            Status(stringResource(R.string.vc_permission_denied), stringResource(R.string.vc_permission_denied_explainer))
            OutlinedButton(onClick = actions.connect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_try_again)) }
        }
        Blocker.BLUETOOTH_OFF -> {
            Status(stringResource(R.string.vc_bluetooth_off), stringResource(R.string.vc_bluetooth_off_explainer))
            OutlinedButton(onClick = actions.connect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_try_again)) }
        }
        Blocker.NO_BLUETOOTH -> Status(stringResource(R.string.vc_no_bluetooth), null)
        Blocker.NOT_PAIRED -> {
            Status(stringResource(R.string.vc_not_paired, name), stringResource(R.string.vc_not_paired_explainer))
            OutlinedButton(onClick = actions.connect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_try_again)) }
        }
        Blocker.POWER_CYCLE_REQUIRED -> {
            Status(stringResource(R.string.vc_power_cycle), stringResource(R.string.vc_power_cycle_explainer))
            OutlinedButton(onClick = actions.openExperiment, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_power_cycle_open)) }
        }
        Blocker.OTHER_TOOL_ACTIVE -> {
            Status(stringResource(R.string.vc_other_tool), stringResource(R.string.vc_other_tool_explainer))
            OutlinedButton(onClick = actions.connect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vc_try_again)) }
        }
    }
}

@Composable
private fun Identity(context: VehicleContext, profiles: List<VehicleProfile>, busy: Boolean, onChoose: () -> Unit) {
    val make = context.make.entryOrNull?.make
    val profile = context.profile
    val title = when {
        make != null && profile != null -> "$make ${profile.name}"
        make != null -> stringResource(R.string.vc_model_unconfirmed, make)
        else -> stringResource(R.string.vc_not_identified)
    }
    Status(title, if (make == null) stringResource(R.string.vc_not_identified_explainer) else null)
    Fact(stringResource(R.string.vc_make), when (val m = context.make) {
        is MakeEvidence.FromVin -> stringResource(R.string.vc_from_vin, m.entry.make)
        is MakeEvidence.Manual -> stringResource(R.string.vc_your_choice, m.entry.make)
        is MakeEvidence.Unknown -> stringResource(R.string.vc_unknown)
    })
    Fact(stringResource(R.string.vc_model), context.profile?.let { stringResource(R.string.vc_confirmed, it.name) } ?: stringResource(R.string.vc_not_confirmed))
    Fact(stringResource(R.string.vc_year), when (val y = context.evidence.modelYear) {
        is YearEvidence.FromVin -> stringResource(R.string.vc_from_vin, y.year.toString())
        is YearEvidence.Ambiguous -> stringResource(R.string.vc_year_ambiguous, y.candidates.joinToString(" or "))
        is YearEvidence.Unknown -> stringResource(R.string.vc_unknown)
    })
    Fact(stringResource(R.string.vc_vin), when (val vin = context.evidence.vin) {
        is VinRead.Read -> stringResource(R.string.vc_vin_hidden, vin.vin.masked())
        is VinRead.Conflict -> stringResource(R.string.vc_vin_conflict)
        is VinRead.NotAvailable -> stringResource(R.string.vc_vin_not_read)
    })
    if (profiles.isNotEmpty()) OutlinedButton(onClick = onChoose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(when {
            context.profile != null -> R.string.vc_change_model
            make == null -> R.string.vc_choose_vehicle
            else -> R.string.vc_confirm_model
        }))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun ProfileDialog(context: VehicleContext, profiles: List<VehicleProfile>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(context.profile?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vc_model_dialog_title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (context.make is MakeEvidence.FromVin) R.string.vc_model_dialog_vin else R.string.vc_model_dialog_manual),
                    style = MaterialTheme.typography.bodyMedium)
                (profiles.map { it.id to "${it.make} ${it.name}" } + (null to stringResource(R.string.vc_model_none))).forEach { (id, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected == id, role = Role.RadioButton, onClick = { selected = id }).padding(vertical = 6.dp)) {
                        RadioButton(selected = selected == id, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Manufacturer section: the selected service's summary and each capability's status and reason. */
@Composable
fun ManufacturerCard(context: VehicleContext, onOpenExperiment: () -> Unit) {
    val service = context.service ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.maker_heading, service.make), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(service.summary, style = MaterialTheme.typography.bodyMedium)
            context.capabilities.forEach { capability ->
                HorizontalDivider()
                Text(capability.definition.title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when (capability.status) {
                    CapabilityStatus.RESEARCH_ONLY -> R.string.maker_status_research
                    CapabilityStatus.NEEDS_CONFIRMATION -> R.string.maker_status_confirm
                    CapabilityStatus.UNAVAILABLE -> R.string.maker_status_unavailable
                }), style = MaterialTheme.typography.labelLarge, color = when (capability.status) {
                    CapabilityStatus.RESEARCH_ONLY -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
                Text(capability.reason, style = MaterialTheme.typography.bodySmall)
                if (capability.status == CapabilityStatus.RESEARCH_ONLY)
                    OutlinedButton(onClick = onOpenExperiment, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.maker_open_experiment)) }
            }
        }
    }
}

private fun technicalDetails(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> state.detail.orEmpty()
    is ConnectionState.Ready -> buildString {
        when (val v = state.vehicle) {
            is VehicleStatus.NoAnswer -> appendLine(v.detail)
            is VehicleStatus.Known -> {
                v.context.evidence.protocol?.let { appendLine("Protocol: $it") }
                appendLine("Responding modules: " + v.context.evidence.responders.joinToString { it ?: "unknown" }.ifEmpty { "none" })
                appendLine("VIN result: " + when (val vin = v.context.evidence.vin) {
                    is VinRead.Read -> "read from ${vin.responders.joinToString { it ?: "unknown" }}" +
                        if (vin.vin.checkDigitMatches) " (check digit matches)" else " (check digit does not match; normal outside North America)"
                    is VinRead.Conflict -> "different VINs from ${vin.responders.joinToString { it ?: "unknown" }}"
                    is VinRead.NotAvailable -> vin.reason
                })
                (v.context.make as? MakeEvidence.Unknown)?.let { appendLine("Make: ${it.reason}") }
            }
        }
        if (state.transcript.isNotEmpty()) {
            appendLine()
            appendLine("Identification exchange (VIN reply hidden):")
            append(state.transcript)
        }
    }.trim()
    else -> ""
}
