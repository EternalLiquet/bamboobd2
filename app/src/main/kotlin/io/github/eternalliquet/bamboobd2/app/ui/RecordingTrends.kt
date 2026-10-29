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
import io.github.eternalliquet.bamboobd2.app.live.RecordingSeries
import io.github.eternalliquet.bamboobd2.core.model.LiveSampleOutcome
import java.text.DateFormat
import java.util.Date

/** Visual plot plus a complete textual inspection path; no interpolated readings. */
@Composable
fun RecordingTrends(series: List<RecordingSeries>, nowElapsed: Long, all: Boolean, onAll: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !all, onClick = { onAll(false) }, label = { Text("Last 60 seconds") })
        FilterChip(selected = all, onClick = { onAll(true) }, label = { Text("All recorded") })
    }
    if (series.isEmpty()) Text("No observations in this window.")
    series.forEach { s ->
        Text("PID ${s.key.pid} · ECU ${s.key.ecu ?: "unknown"} · ${s.key.unit}", style = MaterialTheme.typography.titleSmall)
        val min = s.minimum
        val max = s.maximum
        if (min != null && max != null) Text("Observed min $min, max $max ${s.key.unit}")
        else Text("No numeric observation in this window.")
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
            Text("Elapsed ${from / 1000}s–${nowElapsed / 1000}s; dots are recorded observations. Gaps are not connected.", style = MaterialTheme.typography.bodySmall)
        }
        var expanded by remember(s.key) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide history" else "Inspect history (${s.history.size})") }
        if (expanded) s.history.forEach { point ->
            val status = when (point.outcome) {
                LiveSampleOutcome.VALUE -> "${point.value} ${point.unit}"
                LiveSampleOutcome.MISSING -> "Missing: ${point.reason ?: "no response"}"
                LiveSampleOutcome.FAILURE -> "Failed: ${point.reason ?: "read failed"}"
            }
            Text("${DateFormat.getDateTimeInstance().format(Date(point.atMillis))} · +${point.elapsedMillis / 1000}s · ECU ${point.ecu ?: "unknown"} · $status",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
