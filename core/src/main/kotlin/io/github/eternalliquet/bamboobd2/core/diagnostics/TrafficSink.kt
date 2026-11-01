package io.github.eternalliquet.bamboobd2.core.diagnostics

/**
 * What a transport reports about bytes it actually wrote and received. Every call must
 * return immediately: the transport calls it on its write path and read pump.
 */
interface TrafficSink {
    /** The caller requested a write; it may still be cancelled before I/O starts. */
    fun onWriteAttempt(request: String)
    /** Immediately before calling the socket OutputStream on the I/O thread. */
    fun onWrite(request: String)
    /** The host write and flush returned. This does not establish ECU receipt. */
    fun onWriteCompleted(request: String)
    fun onWriteFailed(request: String, error: String)
    fun onRead(bytes: ByteArray)
    /** The read pump ended: [detail] is "end of stream" or the read error. */
    fun onStreamEnded(detail: String)
    /** The app closed the connection. */
    fun onLocalClose()
}
