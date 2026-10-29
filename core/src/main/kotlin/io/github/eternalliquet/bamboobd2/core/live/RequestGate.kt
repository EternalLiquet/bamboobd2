package io.github.eternalliquet.bamboobd2.core.live

/** One gate shared by setup, discovery, code reads, sensor reads and retries. */
interface RequestGate {
    /** Runs [awaitIdle] after spacing/pause waits, then rechecks admission before returning. */
    suspend fun beforeWrite(retry: Boolean, awaitIdle: suspend () -> Unit = {})
    fun wrote(retry: Boolean)
    fun rejectedWrite()
}
