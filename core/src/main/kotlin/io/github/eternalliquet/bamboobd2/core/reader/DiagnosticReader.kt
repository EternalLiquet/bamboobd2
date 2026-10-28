package io.github.eternalliquet.bamboobd2.core.reader

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids

/** Read-only source of generic diagnostic data. Its provenance is fixed by the implementation. */
interface DiagnosticReader {
    val provenance: Provenance
    val sourceLabel: String

    /** Prepares the source before the first read. */
    suspend fun open() {}

    suspend fun readSupportedPids(): SupportedPids
    suspend fun readDtcs(kind: DtcKind): DtcRead

    /** One result per responder; non-value results are a single entry. */
    suspend fun readPid(pid: String): List<ReadingResult>

    /** Notes since the last call (retries, discarded replies). The controller timestamps and records them. */
    fun takeNotes(): List<ReaderNote> = emptyList()
}

/** Result of reading one trouble-code list. Only [Read] means the list is known. */
sealed interface DtcRead {
    data class Read(val codes: List<Dtc>) : DtcRead
    data class NotRead(val reason: String) : DtcRead
}

data class ReaderNote(val kind: String, val detail: String = "")

/** Why a reader stopped the session. Partial data is kept; nothing is retried or reconnected. */
enum class StopReason { DISCONNECTED, NO_RESPONSE, ADAPTER_REJECTED, ADAPTER_RESET, NO_PROTOCOL }

class ReaderStoppedException(val reason: StopReason, detail: String) : Exception("${reason.name.lowercase()}: $detail")
