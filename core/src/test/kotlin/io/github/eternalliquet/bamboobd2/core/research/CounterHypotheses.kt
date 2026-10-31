package io.github.eternalliquet.bamboobd2.core.research

// All declarations in this file are confined to the test source set. Values and
// observations supplied to this experiment are synthetic, not vehicle evidence.
internal enum class CounterOrder { BIG_ENDIAN, LITTLE_ENDIAN }
internal enum class CounterMeaning { REMAINING, ELAPSED, DUE_AT, LAST_SERVICE_PLUS_INTERVAL }
internal enum class CounterUnit { KILOMETERS, DAYS }
internal enum class ServiceCategory { OIL, INTERMEDIATE, MAIN }
internal enum class AllOnesMeaning { UNSPECIFIED, NUMERIC, UNAVAILABLE }
internal enum class ApplicabilityNote { UNKNOWN, RELATED_PLATFORM_ONLY }

internal data class FieldSpec(
    val fieldKey: String,
    val widthBits: Int?,
    val unit: CounterUnit?,
    val category: ServiceCategory?,
)

internal data class CounterObservation(val field: FieldSpec, val bytes: List<Int>, val position: Long)
internal data class DeclaredInterval(val value: Long, val unit: CounterUnit, val category: ServiceCategory)

internal data class CounterHypothesis(
    val id: String,
    val order: CounterOrder,
    val signed: Boolean,
    val unitsPerCount: Int,
    val meaning: CounterMeaning,
    val interval: DeclaredInterval? = null,
    val allOnes: AllOnesMeaning = AllOnesMeaning.UNSPECIFIED,
)

internal data class CounterFit(val hypothesisId: String, val remaining: List<Long>)
internal data class CounterRejection(val hypothesisId: String, val reason: String)
internal data class CounterReport(
    val fits: List<CounterFit>,
    val rejections: List<CounterRejection>,
    val applicability: ApplicabilityNote,
)

internal fun evaluateCounterHypotheses(
    observations: List<CounterObservation>,
    candidates: List<CounterHypothesis>,
    applicability: ApplicabilityNote,
): CounterReport {
    require(observations.size in 1..16) { "Expected 1..16 synthetic observations" }
    require(candidates.size in 1..32) { "Expected 1..32 explicit hypotheses" }
    require(candidates.all { it.id.isNotBlank() } && candidates.map { it.id }.distinct().size == candidates.size) {
        "Hypothesis IDs must be nonblank and unique"
    }

    val field = observations.first().field
    require(field.fieldKey.isNotBlank() && field.widthBits == 16 && field.unit != null && field.category != null) {
        "Synthetic field needs a key, 16-bit width, unit, and category"
    }
    require(observations.all { observation ->
        observation.field == field && observation.bytes.size == 2 &&
            observation.bytes.all { it in 0..255 } && observation.position in 0..1_000_000_000L
    }) { "Synthetic observations need matching field metadata, two valid bytes, and bounded positions" }

    val fits = mutableListOf<CounterFit>()
    val rejections = mutableListOf<CounterRejection>()
    for (candidate in candidates) {
        val interval = candidate.interval
        val reason = when {
            candidate.unitsPerCount != 1 && candidate.unitsPerCount != 100 -> "Scale must be 1 or 100 units per count"
            interval != null && interval.value !in 1..1_000_000_000L -> "Declared interval is out of bounds"
            interval != null && (interval.unit != field.unit || interval.category != field.category) ->
                "Declared interval unit or category does not match the observation"
            candidate.meaning in listOf(CounterMeaning.ELAPSED, CounterMeaning.LAST_SERVICE_PLUS_INTERVAL) && interval == null ->
                "This meaning needs a declared interval"
            observations.any { it.bytes == listOf(255, 255) } && candidate.allOnes == AllOnesMeaning.UNSPECIFIED ->
                "All-ones meaning is ambiguous without an explicit declaration"
            observations.any { it.bytes == listOf(255, 255) } && candidate.allOnes == AllOnesMeaning.UNAVAILABLE ->
                "All-ones was declared unavailable"
            else -> null
        }
        if (reason != null) {
            rejections += CounterRejection(candidate.id, reason)
            continue
        }

        val values = observations.map { observation ->
            val first = observation.bytes[0]
            val second = observation.bytes[1]
            val raw = if (candidate.order == CounterOrder.BIG_ENDIAN) first * 256 + second else second * 256 + first
            val count = if (candidate.signed && raw >= 32768) raw - 65536 else raw
            count.toLong() * candidate.unitsPerCount
        }
        val contradiction = (1 until observations.size).firstOrNull { index ->
            val positionDelta = observations[index].position - observations[index - 1].position
            val expectedDelta = when (candidate.meaning) {
                CounterMeaning.REMAINING -> -positionDelta
                CounterMeaning.ELAPSED -> positionDelta
                CounterMeaning.DUE_AT, CounterMeaning.LAST_SERVICE_PLUS_INTERVAL -> 0L
            }
            values[index] - values[index - 1] != expectedDelta
        }
        if (contradiction != null) {
            rejections += CounterRejection(candidate.id, "Decoded change contradicts ${candidate.meaning} at observation $contradiction")
            continue
        }
        val remaining = values.mapIndexed { index, value ->
            when (candidate.meaning) {
                CounterMeaning.REMAINING -> value
                CounterMeaning.ELAPSED -> interval!!.value - value
                CounterMeaning.DUE_AT -> value - observations[index].position
                CounterMeaning.LAST_SERVICE_PLUS_INTERVAL -> value + interval!!.value - observations[index].position
            }
        }
        fits += CounterFit(candidate.id, remaining)
    }
    return CounterReport(fits, rejections, applicability)
}
