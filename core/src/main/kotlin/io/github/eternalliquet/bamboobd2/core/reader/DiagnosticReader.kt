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

    suspend fun readSupportedPids(): SupportedPids
    suspend fun readDtcs(kind: DtcKind): List<Dtc>
    suspend fun readPid(pid: String): ReadingResult
}
