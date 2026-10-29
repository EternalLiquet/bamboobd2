package io.github.eternalliquet.bamboobd2.core.live

/** One gate shared by setup, discovery, code reads, sensor reads and retries. */
interface RequestGate {
    suspend fun beforeWrite(retry: Boolean)
    fun wrote(retry: Boolean)
    fun rejectedWrite()
}
