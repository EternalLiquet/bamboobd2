package io.github.eternalliquet.bamboobd2.core.protocol

import kotlin.time.TimeSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A replayed adapter conversation. Each step names the request the reader must
 * send next and the bytes the adapter returns, each chunk at a time measured from
 * that write. Chunks can arrive after the next write, which is how late replies
 * are scripted. A step can also end the connection.
 *
 * Provenance follows the fixture rules in docs/PROTOCOL.md (F16): `SYNTHETIC`, or
 * `SANITIZED_CAPTURE` with the original's SHA-256 and an evidence-log link.
 */
@Serializable
data class ReplayScript(
    val id: String,
    val label: String,
    val provenance: String,
    val sourceTier: String,
    val source: String,
    val sha256: String? = null,
    val evidenceLog: String? = null,
    /** False scripts a connection that is already gone before the first write. */
    val connected: Boolean = true,
    val steps: List<ReplayStep>,
) {
    /** Metadata and allowlist problems; empty when the script is usable. */
    fun problems(): List<String> = buildList {
        if (id.isBlank() || label.isBlank() || source.isBlank()) add("missing id, label or source")
        when (provenance) {
            "SYNTHETIC" -> Unit
            "SANITIZED_CAPTURE" -> if (sha256.isNullOrBlank() || evidenceLog.isNullOrBlank()) add("capture without sha256 or evidenceLog")
            else -> add("unknown provenance '$provenance'")
        }
        if (sourceTier !in setOf("primary", "constructed", "manufacturer", "capture")) add("unknown sourceTier '$sourceTier'")
        steps.forEach { step ->
            val decision = RequestPolicy.evaluate(step.request)
            if (decision is PolicyDecision.Rejected) add("step '${step.request}': ${decision.reason}")
            if (step.chunks.any { it.atMillis < 0 }) add("step '${step.request}': negative time")
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /** Parses and validates a script; throws [IllegalArgumentException] when it is unusable. */
        fun parse(text: String): ReplayScript {
            val script = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("not a replay script: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            val problems = script.problems()
            require(problems.isEmpty()) { "replay script '${script.id}': ${problems.joinToString("; ")}" }
            return script
        }
    }
}

@Serializable
data class ReplayStep(
    val request: String,
    val chunks: List<ReplayChunk> = emptyList(),
    /** Ends the connection this long after the write; chunks due later are never delivered. */
    val disconnectAfterMillis: Long? = null,
)

/** Adapter output, ASCII, arriving [atMillis] after the step's write. */
@Serializable
data class ReplayChunk(val atMillis: Long, val text: String)

/** The reader sent something other than the script's next request. */
class ReplayDivergedException(message: String) : IllegalStateException(message)

/**
 * Offline transport that replays a [ReplayScript]. Times come from [timeSource]:
 * a test passes the coroutine test scheduler's, so delays run in virtual time.
 * Silence is scripted by leaving chunks out; [read] then waits until cancelled.
 */
class ReplayTransport(
    private val script: ReplayScript,
    timeSource: TimeSource = TimeSource.Monotonic,
) : AdapterTransport {
    override val kind: TransportKind = TransportKind.REPLAY

    private class Due(val atMillis: Long, val order: Int, val bytes: ByteArray)

    private val origin = timeSource.markNow()
    private val queue = mutableListOf<Due>()
    private var nextStep = 0
    private var scheduled = 0
    private var disconnectAt: Long? = if (script.connected) null else 0L

    /** Requests written so far, in order. */
    val writes = mutableListOf<String>()

    private fun now(): Long = origin.elapsedNow().inWholeMilliseconds

    private fun closed(): Boolean = disconnectAt?.let { now() >= it } ?: false

    override suspend fun write(request: Request) {
        if (closed()) throw TransportClosedException("replay connection closed")
        val step = script.steps.getOrNull(nextStep)
            ?: throw ReplayDivergedException("unscripted request '${request.text}' after ${script.steps.size} steps")
        if (step.request != request.text) {
            throw ReplayDivergedException("step ${nextStep + 1} expects '${step.request}', got '${request.text}'")
        }
        nextStep++
        writes += request.text
        val at = now()
        step.chunks.forEach { queue += Due(at + it.atMillis, scheduled++, it.text.toByteArray(Charsets.US_ASCII)) }
        queue.sortWith(compareBy<Due> { it.atMillis }.thenBy { it.order })
        step.disconnectAfterMillis?.let { d -> disconnectAt = minOf(disconnectAt ?: Long.MAX_VALUE, at + d) }
    }

    override suspend fun read(): ByteArray? {
        while (true) {
            val next = queue.firstOrNull()
            val end = disconnectAt
            if (end != null && (next == null || next.atMillis >= end)) {
                if (now() < end) delay(end - now())
                queue.clear()
                return null
            }
            if (next == null) awaitCancellation()
            if (now() < next.atMillis) {
                // A write during this wait can schedule an earlier chunk, so look again afterwards.
                delay(next.atMillis - now())
                continue
            }
            queue.removeAt(0)
            return next.bytes
        }
    }

    companion object {
        /** Scripts bundled with `:core` for demonstrations; all synthetic. */
        val bundledIds = listOf("can-complete", "non-can-complete", "no-response", "disconnect-mid-reply")

        fun bundled(id: String): ReplayScript {
            require(id in bundledIds) { "unknown bundled replay '$id'" }
            val stream = ReplayTransport::class.java.getResourceAsStream("/replay/$id.json")
                ?: error("bundled replay '$id' missing from resources")
            return ReplayScript.parse(stream.use { it.readBytes() }.toString(Charsets.UTF_8))
        }
    }
}
