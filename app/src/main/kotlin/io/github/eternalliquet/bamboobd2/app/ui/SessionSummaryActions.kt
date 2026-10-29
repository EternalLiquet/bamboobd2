package io.github.eternalliquet.bamboobd2.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.report.SessionSummary
import java.text.DateFormat
import java.util.Date

/** Entry point appears only where the caller has an immutable finished or reopened record. */
@Composable
fun SessionSummaryActions(record: SessionRecord) {
    val context = LocalContext.current
    var preview by remember(record.id) { mutableStateOf<String?>(null) }
    var shareUnavailable by remember(record.id) { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown version"
            val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            preview = SessionSummary.render(record, version) { formatter.format(Date(it)) }
            shareUnavailable = false
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.summary_preview)) }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.summary_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.summary_review_notice))
                    Text(text)
                    CopyButton(text = text, label = R.string.summary_copy)
                    if (shareUnavailable) Text(stringResource(R.string.summary_share_unavailable))
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.summary_share_chooser)))
                        } catch (_: ActivityNotFoundException) {
                            shareUnavailable = true
                        }
                    }) { Text(stringResource(R.string.summary_share)) }
                    TextButton(onClick = { preview = null }) { Text(stringResource(R.string.summary_close)) }
                }
            },
        )
    }
}
