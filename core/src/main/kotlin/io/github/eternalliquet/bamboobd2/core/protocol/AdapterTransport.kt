package io.github.eternalliquet.bamboobd2.core.protocol

/**
 * What kind of source a transport is. Every [AdapterTransport] implementation in
 * any module is listed with its kind in `transport-registry.txt` and checked by
 * the `verifyTransportRegistry` task (G2). Only FAKE exists in this milestone.
 */
enum class TransportKind { FAKE }

/** Byte stream to an ELM327-style adapter. Writes accept only policy-built [Request]s. */
interface AdapterTransport {
    val kind: TransportKind

    suspend fun write(request: Request)

    /** Next chunk of bytes from the adapter, or null when the stream has ended. */
    suspend fun read(): ByteArray?
}
