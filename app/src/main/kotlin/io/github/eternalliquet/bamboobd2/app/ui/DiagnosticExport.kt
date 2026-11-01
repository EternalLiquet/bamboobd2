package io.github.eternalliquet.bamboobd2.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.eternalliquet.bamboobd2.app.AppViewModel
import io.github.eternalliquet.bamboobd2.app.DiagnosticExportFile
import io.github.eternalliquet.bamboobd2.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export diagnostic log": prepares the redacted JSON, explains what it holds, then
 * hands it to Android's share sheet or a save location the user picks. Nothing is
 * sent anywhere without that choice.
 */
@Composable
fun DiagnosticExportButton(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<DiagnosticExportFile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.diag_failed)
    val savedText = stringResource(R.string.diag_saved)
    val saveFailedText = stringResource(R.string.diag_save_failed)
    val chooserTitle = stringResource(R.string.diag_share_chooser)
    // The document picker can recreate the activity; keep the prepared file's path.
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val source = pendingSave?.let(::File)?.takeIf { it.isFile }
        pendingSave = null
        if (uri != null && source != null) scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use { out -> source.inputStream().use { it.copyTo(out) } }
                }
                savedText
            } catch (_: Exception) { saveFailedText }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                message = null
                scope.launch { prepared = runCatching { vm.prepareDiagnosticExport() }.getOrElse { message = failedText; null } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.diag_export)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
    val export = prepared ?: return
    val file = export.file
    if (file == null) {
        AlertDialog(
            onDismissRequest = { prepared = null },
            title = { Text(stringResource(R.string.diag_none_title)) },
            text = { Text(stringResource(R.string.diag_none_body)) },
            confirmButton = { TextButton(onClick = { prepared = null }) { Text(stringResource(R.string.done)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { prepared = null },
        title = { Text(stringResource(R.string.diag_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.diag_body, export.sessions, export.sessions))
                Text(stringResource(R.string.diag_privacy))
                if (export.truncated) Text(stringResource(R.string.diag_truncated))
                if (export.unfinished > 0) Text(pluralStringResource(R.plurals.diag_unfinished, export.unfinished, export.unfinished))
            }
        },
        confirmButton = {
            TextButton(onClick = { prepared = null; share(context, file, chooserTitle) }) { Text(stringResource(R.string.diag_share)) }
        },
        dismissButton = {
            TextButton(onClick = { prepared = null; pendingSave = file.path; save.launch(file.name) }) { Text(stringResource(R.string.diag_save)) }
        },
    )
}

private fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
