package io.github.eternalliquet.bamboobd2.core.protocol

/**
 * Scripted offline transport. Each written request queues the scripted reply for
 * its text, split into the given chunks. Replies are synthetic, never vehicle data.
 */
class FakeTransport(
    private val replies: Map<String, List<ByteArray>>,
) : AdapterTransport {
    override val kind: TransportKind = TransportKind.FAKE

    private val written = mutableListOf<Request>()
    private val pending = ArrayDeque<ByteArray>()

    /** Requests written so far, in order. */
    val writes: List<Request> get() = written.toList()

    override suspend fun write(request: Request) {
        written += request
        val reply = replies[request.text] ?: listOf("NO DATA\r\r>".toByteArray(Charsets.US_ASCII))
        pending.addAll(reply)
    }

    override suspend fun read(): ByteArray? = pending.removeFirstOrNull()

    companion object {
        /** A reply given as text, delivered in one chunk. */
        fun text(reply: String): List<ByteArray> = listOf(reply.toByteArray(Charsets.US_ASCII))
    }
}
