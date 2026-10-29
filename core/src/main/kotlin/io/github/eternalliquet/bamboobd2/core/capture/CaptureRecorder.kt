package io.github.eternalliquet.bamboobd2.core.capture

import io.github.eternalliquet.bamboobd2.core.protocol.ReplayChunk
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayStep
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json

/**
 * Records a live adapter conversation in the [ReplayScript] format: each written
 * request, then every chunk received after it with its time since that write.
 * A transport calls [onWrite] before sending and [onRead] when bytes arrive; both
 * may come from different threads.
 *
 * The end of the stream (a disconnect) is not recorded, so a capture of a session
 * that ended DISCONNECTED replays as silence and ends as a timeout instead.
 *
 * The output is marked `RAW_CAPTURE`, which [ReplayScript.parse] rejects. It becomes
 * a fixture only through docs/CAPTURE_IMPORT.md: kept outside Git, hashed, reviewed,
 * sanitized and relabelled `SANITIZED_CAPTURE`.
 */
class CaptureRecorder(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private class Open(val request: String, val at: TimeMark, val chunks: MutableList<ReplayChunk> = mutableListOf())

    private val steps = mutableListOf<Open>()
    private var bytesBeforeFirstWrite = 0

    @Synchronized
    fun onWrite(request: String) {
        steps += Open(request, timeSource.markNow())
    }

    @Synchronized
    fun onRead(bytes: ByteArray) {
        val step = steps.lastOrNull()
        if (step == null) {
            bytesBeforeFirstWrite += bytes.size
            return
        }
        // ISO-8859-1 keeps every byte as one character, so nothing is lost or rewritten.
        step.chunks += ReplayChunk(step.at.elapsedNow().inWholeMilliseconds, bytes.toString(Charsets.ISO_8859_1))
    }

    @Synchronized
    fun script(id: String, source: String): ReplayScript = ReplayScript(
        id = id,
        label = "Raw capture $id",
        provenance = RAW_CAPTURE,
        sourceTier = "capture",
        source = source + if (bytesBeforeFirstWrite > 0) " $bytesBeforeFirstWrite byte(s) arrived before the first write and are not included." else "",
        steps = steps.map { ReplayStep(it.request, it.chunks.toList()) },
    )

    fun json(id: String, source: String): String = json.encodeToString(ReplayScript.serializer(), script(id, source)) + "\n"

    companion object {
        /** Not a provenance [ReplayScript.parse] accepts: a raw capture must be reviewed first. */
        const val RAW_CAPTURE = "RAW_CAPTURE"

        private val json = Json { prettyPrint = true }
    }
}
