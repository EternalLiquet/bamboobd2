package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.LiveSampleOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord

/** An outcome count is an observation count, never an estimated sampling rate. */
data class RecordingKey(val pid: String, val ecu: String?, val unit: String)
data class RecordingSeriesReport(
    val key: RecordingKey,
    val observedCount: Int,
    val missingCount: Int,
    val failureCount: Int,
    val firstAtMillis: Long?,
    val lastAtMillis: Long?,
    val minimum: Double?,
    val maximum: Double?,
    val lastValue: Double?,
    val lastValueAtMillis: Long?,
)
enum class RecordingNotComparable { MISSING_SIDE, UNIT_MISMATCH, UNKNOWN_RESPONDER }
data class RecordingComparisonRow(
    val key: RecordingKey,
    val first: RecordingSeriesReport?,
    val second: RecordingSeriesReport?,
    val reason: RecordingNotComparable?,
)

/** Pure projection of saved outcomes; the sample sequence is retained, including identical timestamps. */
object RecordingReport {
    fun series(record: SessionRecord): List<RecordingSeriesReport> = record.recording?.samples.orEmpty()
        .groupBy { RecordingKey(it.pid, it.ecu, it.unit) }
        .map { (key, samples) ->
            val ordered = samples.sortedWith(compareBy({ it.elapsedMillis }, { it.sequence }))
            val values = ordered.filter { it.outcome == LiveSampleOutcome.VALUE && it.value?.isFinite() == true }
            RecordingSeriesReport(
                key, values.size, ordered.count { it.outcome == LiveSampleOutcome.MISSING },
                ordered.count { it.outcome == LiveSampleOutcome.FAILURE || it.outcome == LiveSampleOutcome.VALUE && it.value?.isFinite() != true },
                ordered.firstOrNull()?.atMillis, ordered.lastOrNull()?.atMillis,
                values.minOfOrNull { it.value!! }, values.maxOfOrNull { it.value!! },
                values.lastOrNull()?.value, values.lastOrNull()?.atMillis,
            )
        }.sortedWith(compareBy<RecordingSeriesReport> { it.key.pid }.thenBy { it.key.ecu ?: "" }.thenBy { it.key.unit })

    fun compare(first: SessionRecord, second: SessionRecord): List<RecordingComparisonRow> {
        val a = series(first).associateBy { it.key }
        val b = series(second).associateBy { it.key }
        val firstIdentities = a.keys.map { it.pid to it.ecu }.toSet()
        val secondIdentities = b.keys.map { it.pid to it.ecu }.toSet()
        return (a.keys + b.keys).distinct().sortedWith(compareBy<RecordingKey> { it.pid }.thenBy { it.ecu ?: "" }.thenBy { it.unit })
            .map { key ->
                val left = a[key]
                val right = b[key]
                val identity = key.pid to key.ecu
                val otherUnit = if (left == null) identity in firstIdentities else identity in secondIdentities
                RecordingComparisonRow(key, left, right, when {
                    key.ecu == null || !RESPONDER.matches(key.ecu) -> RecordingNotComparable.UNKNOWN_RESPONDER
                    left == null || right == null -> if (otherUnit) RecordingNotComparable.UNIT_MISMATCH else RecordingNotComparable.MISSING_SIDE
                    else -> null
                })
            }
    }

    private val RESPONDER = Regex("(?:[0-9A-F]{2}|[0-7][0-9A-F]{2})")
}
