package io.github.eternalliquet.bamboobd2.core.report

import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.Reading
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.session.SessionController

sealed interface ComparisonResult {
    data class Blocked(val reason: ComparisonBlock) : ComparisonResult
    data class Ready(val codeKinds: List<CodeKindComparison>, val readings: List<ReadingComparison>) : ComparisonResult
}

enum class ComparisonBlock { SAME_SESSION, DIFFERENT_PROVENANCE }
enum class CodeCoverage { READ, INCOMPLETE, NOT_READ }
data class CodeKey(val kind: DtcKind, val code: String, val ecu: String?)
data class CodeList(val coverage: CodeCoverage, val codes: List<CodeKey>)
data class CodeDifferences(val reportedInBoth: List<CodeKey>, val onlyFirst: List<CodeKey>, val onlySecond: List<CodeKey>)
data class CodeKindComparison(val kind: DtcKind, val first: CodeList, val second: CodeList, val differences: CodeDifferences?)

enum class ReadingStatus { VALUE, INVALID_VALUE, UNSUPPORTED, UNKNOWN_CAPABILITY, NO_DATA, FAILED, NOT_REQUESTED, NOT_READ }
data class ReadingObservation(val status: ReadingStatus, val value: Double? = null, val atMillis: Long? = null)
data class ReadingKey(val pid: ObdPid, val ecu: String?)
enum class NotComparable { MISSING_SIDE, UNKNOWN_RESPONDER, DUPLICATE, NON_VALUE, INVALID_VALUE, NON_FINITE_DELTA }
data class ReadingComparison(
    val key: ReadingKey,
    val first: List<ReadingObservation>,
    val second: List<ReadingObservation>,
    /** Second minus first, in [ReadingKey.pid]'s canonical unit. */
    val delta: Double?,
    val reason: NotComparable?,
)

/** Compares only existing structured generic observations; it makes no vehicle identity or health claim. */
object SessionComparison {
    private val responder = Regex("(?:[0-9A-F]{2}|[0-7][0-9A-F]{2})")

    fun compare(first: SessionRecord, second: SessionRecord): ComparisonResult {
        if (first.id == second.id) return ComparisonResult.Blocked(ComparisonBlock.SAME_SESSION)
        if (first.provenance != second.provenance) return ComparisonResult.Blocked(ComparisonBlock.DIFFERENT_PROVENANCE)
        return ComparisonResult.Ready(
            DtcKind.entries.map { kind -> compareCodes(kind, first, second) },
            ObdPid.entries.flatMap { pid -> compareReadings(pid, first, second) },
        )
    }

    private fun compareCodes(kind: DtcKind, first: SessionRecord, second: SessionRecord): CodeKindComparison {
        fun list(record: SessionRecord): CodeList {
            val codes = record.dtcs.filter { it.kind == kind }.map { CodeKey(kind, it.code, it.ecu) }
                .distinct().sortedWith(compareBy<CodeKey> { it.code }.thenBy { it.ecu ?: "" })
            val incompleteEvent = record.events.any {
                it.kind == SessionController.CODES_INCOMPLETE && it.detail.substringBefore(':') == kind.name.lowercase()
            }
            val coverage = when {
                kind in record.dtcKindsRead -> CodeCoverage.READ
                codes.isNotEmpty() || incompleteEvent -> CodeCoverage.INCOMPLETE
                else -> CodeCoverage.NOT_READ
            }
            return CodeList(coverage, codes)
        }
        val a = list(first)
        val b = list(second)
        val differences = if (a.coverage == CodeCoverage.READ && b.coverage == CodeCoverage.READ) {
            val aKeys = a.codes.toSet()
            val bKeys = b.codes.toSet()
            CodeDifferences(a.codes.filter { it in bKeys }, a.codes.filter { it !in bKeys }, b.codes.filter { it !in aKeys })
        } else null
        return CodeKindComparison(kind, a, b, differences)
    }

    private fun compareReadings(pid: ObdPid, first: SessionRecord, second: SessionRecord): List<ReadingComparison> {
        fun Reading.key(): String? = (result as? ReadingResult.Value)?.ecu
        val firstRows = first.readings.filter { it.pid == pid.code }
        val secondRows = second.readings.filter { it.pid == pid.code }
        val ecus = (firstRows.map { it.key() } + secondRows.map { it.key() }).distinct()
            .sortedWith(compareBy<String?> { it == null }.thenBy { it ?: "" })
            .ifEmpty { listOf(null) }
        return ecus.map { ecu ->
            val a = firstRows.filter { it.key() == ecu }
            val b = secondRows.filter { it.key() == ecu }
            val aObserved = observe(pid, first, a)
            val bObserved = observe(pid, second, b)
            val aValue = a.singleOrNull()?.result as? ReadingResult.Value
            val bValue = b.singleOrNull()?.result as? ReadingResult.Value
            val reason = when {
                a.isEmpty() || b.isEmpty() -> NotComparable.MISSING_SIDE
                ecu == null || !responder.matches(ecu) -> NotComparable.UNKNOWN_RESPONDER
                a.size != 1 || b.size != 1 -> NotComparable.DUPLICATE
                aValue == null || bValue == null -> NotComparable.NON_VALUE
                !aValue.value.isFinite() || !bValue.value.isFinite() || aValue.unit != pid.unit || bValue.unit != pid.unit -> NotComparable.INVALID_VALUE
                !(bValue.value - aValue.value).isFinite() -> NotComparable.NON_FINITE_DELTA
                else -> null
            }
            ReadingComparison(
                key = ReadingKey(pid, ecu), first = aObserved, second = bObserved,
                delta = if (reason == null) bValue!!.value - aValue!!.value else null,
                reason = reason,
            )
        }
    }

    private fun observe(pid: ObdPid, record: SessionRecord, rows: List<Reading>): List<ReadingObservation> {
        if (rows.isEmpty()) {
            val notRequested = record.events.any {
                (it.kind == SessionController.READ_SCOPE && it.detail == "CODES_ONLY") || it.kind == SessionController.READINGS_NOT_REQUESTED
            }
            return listOf(ReadingObservation(if (notRequested) ReadingStatus.NOT_REQUESTED else ReadingStatus.NOT_READ))
        }
        return rows.map { row ->
            when (val result = row.result) {
                is ReadingResult.Value -> ReadingObservation(
                    if (result.value.isFinite() && result.unit == pid.unit) ReadingStatus.VALUE else ReadingStatus.INVALID_VALUE,
                    result.value.takeIf { it.isFinite() }, row.atMillis,
                )
                ReadingResult.Unsupported -> ReadingObservation(ReadingStatus.UNSUPPORTED, atMillis = row.atMillis)
                ReadingResult.UnknownCapability -> ReadingObservation(ReadingStatus.UNKNOWN_CAPABILITY, atMillis = row.atMillis)
                ReadingResult.NoData -> ReadingObservation(ReadingStatus.NO_DATA, atMillis = row.atMillis)
                is ReadingResult.Failed -> ReadingObservation(ReadingStatus.FAILED, atMillis = row.atMillis)
            }
        }
    }
}
