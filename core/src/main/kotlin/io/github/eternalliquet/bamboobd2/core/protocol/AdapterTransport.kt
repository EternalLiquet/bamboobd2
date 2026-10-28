package io.github.eternalliquet.bamboobd2.core.protocol

/**
 * What kind of source a transport is. Every [AdapterTransport] implementation in
 * any module is listed with its kind in `transport-registry.txt` and checked by
 * the `verifyTransportRegistry` task (G2). A LIVE entry must also name its review
 * in that file; declaring LIVE is not permission to run it on hardware.
 */
enum class TransportKind {
    /** Scripted in code; sessions over it are SIMULATED. */
    FAKE,

    /** Replays a [ReplayScript]; sessions over it are REPLAY. */
    REPLAY,

    /** A real connection to an adapter; sessions over it are LIVE. */
    LIVE,
}

/** The connection is gone. Readers never reconnect; a new connection means a new session. */
class TransportClosedException(message: String) : Exception(message)

/** Byte stream to an ELM327-style adapter. Writes accept only policy-built [Request]s. */
interface AdapterTransport {
    val kind: TransportKind

    /** Throws [TransportClosedException] when the connection is gone. */
    suspend fun write(request: Request)

    /**
     * Next chunk of bytes from the adapter, or null when the stream has ended.
     * Must be cancellable without losing bytes: readers wait with timeouts.
     */
    suspend fun read(): ByteArray?
}
