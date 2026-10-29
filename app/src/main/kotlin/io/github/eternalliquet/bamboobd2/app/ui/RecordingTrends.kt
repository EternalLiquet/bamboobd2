package io.github.eternalliquet.bamboobd2.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.eternalliquet.bamboobd2.app.R
import io.github.eternalliquet.bamboobd2.app.live.RecordingSeries
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.eternalliquet.bamboobd2.core.model.LiveSampleOutcome
import java.text.DateFormat
import java.util.Date

/** Visual plot plus a complete textual inspection path; no interpolated readings. */
@Composable
fun RecordingTrends(series: List<RecordingSeries>, nowElapsed: Long, all: Boolean, onAll: (Boolean) -> Unit) {
    Column {
        FilterChip(selected = !all, onClick = { onAll(false) }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.recording_window_recent)) })
        FilterChip(selected = all, onClick = { onAll(true) }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.recording_window_all)) })
    }
    if (series.isEmpty()) Text(stringResource(R.string.recording_no_observations))
    series.forEach { s ->
        val label = if (Mode01Catalog.lookup(s.key.pid) != null) stringResource(mode01LabelId(s.key.pid)) else stringResource(R.string.sensor_pid_label, s.key.pid)
        Text(stringResource(R.string.recording_series_label, label, s.key.pid,
            s.key.ecu ?: stringResource(R.string.recording_unknown_responder), s.key.unit),
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val min = s.minimum
        val max = s.maximum
        if (min != null && max != null) Text(stringResource(R.string.recording_observed_range, min.toString(), max.toString(), s.key.unit))
        else Text(stringResource(R.string.recording_no_numeric))
        val values = s.segments.flatten()
        if (values.isNotEmpty()) {
            val low = min ?: 0.0
            val span = ((max ?: low) - low).takeIf { it > 0.0 } ?: 1.0
            val from = if (all) s.history.first().elapsedMillis else (nowElapsed - 60_000).coerceAtLeast(0)
            val duration = (nowElapsed - from).coerceAtLeast(1).toDouble()
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                fun x(t: Long) = ((t - from) / duration * size.width).toFloat().coerceIn(0f, size.width)
                fun y(v: Double) = (size.height - ((v - low) / span * size.height).toFloat()).coerceIn(0f, size.height)
                s.segments.forEach { segment ->
                    if (segment.size > 1) {
                        val path = Path()
                        segment.forEachIndexed { i, point ->
                            if (i == 0) path.moveTo(x(point.elapsedMillis), y(point.value!!))
                            else path.lineTo(x(point.elapsedMillis), y(point.value!!))
                        }
                        drawPath(path, Color(0xFF31688A), style = Stroke(width = 2.dp.toPx()))
                    }
                    segment.forEach { point -> drawCircle(Color(0xFF31688A), 3.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x(point.elapsedMillis), y(point.value!!))) }
                }
            }
            Text(stringResource(R.string.recording_axis, from / 1000, nowElapsed / 1000), style = MaterialTheme.typography.bodySmall)
        }
        var expanded by remember(s.key) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringResource(R.string.recording_hide_history) else stringResource(R.string.recording_show_history, s.history.size)) }
        var index by remember(s.key) { mutableIntStateOf(0) }
        if (expanded && s.history.isNotEmpty()) {
            val selected = index.coerceIn(0, s.history.lastIndex)
            Text(stringResource(R.string.recording_history_position, selected + 1, s.history.size))
            TextButton(onClick = { index = selected - 1 }, enabled = selected > 0,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_previous)) }
            TextButton(onClick = { index = selected + 1 }, enabled = selected < s.history.lastIndex,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recording_next)) }
            val point = s.history[selected]
            val status = when (point.outcome) {
                LiveSampleOutcome.VALUE -> stringResource(R.string.recording_sample_value, point.value.toString())
                LiveSampleOutcome.MISSING -> stringResource(R.string.recording_sample_missing, point.reason ?: stringResource(R.string.recording_no_response))
                LiveSampleOutcome.FAILURE -> stringResource(R.string.recording_failed, point.reason ?: stringResource(R.string.recording_read_failed))
            }
            Text(stringResource(R.string.recording_history_sample, DateFormat.getDateTimeInstance().format(Date(point.atMillis)),
                point.elapsedMillis / 1000, point.ecu ?: stringResource(R.string.recording_unknown_responder), status, point.unit),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
