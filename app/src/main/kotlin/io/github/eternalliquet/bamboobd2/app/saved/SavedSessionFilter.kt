package io.github.eternalliquet.bamboobd2.app.saved

import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession

private val canonicalCode = Regex("[PCBU][0-3][0-9A-F]{3}")

/** Project the already loaded saved list without changing entries or searching unreadable files. */
internal fun filterSavedSessions(entries: List<StoredSession>, codeQuery: String, provenance: Provenance?): List<StoredSession> {
    val query = codeQuery.trim().uppercase()
    if (query.isEmpty() && provenance == null) return entries
    return entries.filter { entry ->
        when (entry) {
            is StoredSession.Unreadable -> true
            is StoredSession.Readable -> (provenance == null || entry.record.provenance == provenance) &&
                (query.isEmpty() || entry.record.dtcs.any { canonicalCode.matches(it.code) && it.code.startsWith(query) })
        }
    }
}
