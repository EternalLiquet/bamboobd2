package io.github.eternalliquet.bamboobd2.app.live

import io.github.eternalliquet.bamboobd2.core.model.LiveEvent
import io.github.eternalliquet.bamboobd2.core.model.LiveEventKind
import io.github.eternalliquet.bamboobd2.core.model.LiveSample
import io.github.eternalliquet.bamboobd2.core.model.LiveSampleOutcome
import io.github.eternalliquet.bamboobd2.core.model.LiveStopReason

/** A recorded unit is part of identity: a changed unit never joins an old line. */
data class SeriesKey(val pid: String, val ecu: String?, val unit: String)
data class RecordingSeries(
    val key: SeriesKey,
    val history: List<LiveSample>,
    val segments: List<List<LiveSample>>,
    val minimum: Double?,
    val maximum: Double?,
)

/** Project actual elapsed observations; failures, pauses and silence break the line. */
fun recordingSeries(samples: List<LiveSample>, events: List<LiveEvent>, nowElapsed: Long, windowMillis: Long? = 60_000): List<RecordingSeries> {
    val since = windowMillis?.let { (nowElapsed - it).coerceAtLeast(0) }
    val ordered = samples.sortedWith(compareBy<LiveSample> { it.elapsedMillis }.thenBy { it.sequence })
    val lastUnits = mutableMapOf<Pair<String, String?>, String>()
    val unitChanges = mutableSetOf<Long>()
    ordered.forEach { row ->
        val previousUnit = lastUnits.put(row.pid to row.ecu, row.unit)
        if (previousUnit != null && previousUnit != row.unit) unitChanges += row.sequence
    }
    return ordered.asSequence().filter { since == null || it.elapsedMillis >= since }
        .groupBy { SeriesKey(it.pid, it.ecu, it.unit) }
        .map { (key, entries) ->
            val segments = mutableListOf<List<LiveSample>>()
            var current = mutableListOf<LiveSample>()
            var previous: LiveSample? = null
            fun flush() { if (current.isNotEmpty()) segments += current.toList(); current = mutableListOf() }
            entries.forEach { row ->
                if (row.outcome != LiveSampleOutcome.VALUE || row.value?.isFinite() != true) {
                    flush()
                    previous = null
                } else {
                    val prior = previous
                    if (prior != null && (row.sequence in unitChanges || row.elapsedMillis - prior.elapsedMillis > 5_000 ||
                        events.any { (it.kind == LiveEventKind.PAUSE ||
                            it.kind == LiveEventKind.END && it.reason == LiveStopReason.DISCONNECTED) &&
                            it.elapsedMillis in prior.elapsedMillis..row.elapsedMillis })) flush()
                    current += row
                    previous = row
                }
            }
            flush()
            val values = segments.flatten().mapNotNull { it.value }
            RecordingSeries(key, entries, segments, values.minOrNull(), values.maxOrNull())
        }.sortedWith(compareBy<RecordingSeries> { it.key.pid }.thenBy { it.key.ecu ?: "" }.thenBy { it.key.unit })
}
