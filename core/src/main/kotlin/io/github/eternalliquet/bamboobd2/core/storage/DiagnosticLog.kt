package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.diagnostics.DiagnosticRedactor
import io.github.eternalliquet.bamboobd2.core.diagnostics.TrafficSink

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Storage bounds. Defaults keep at most about 2 MB on the phone. */
data class DiagnosticLimits(
    val maxSessions: Int = 8,
    /** Traffic events stop being stored past this many bytes in one session. */
    val sessionTrafficBytes: Int = 240_000,
    /** Every event except the final truncation note and session end stops here. */
    val sessionBytes: Int = 256_000,
)

/**
 * A persistent, bounded log of Bluetooth diagnostic sessions, one JSON-lines file per
 * session in [dir]. Callers never block: events are timestamped on the calling thread
 * and written by one background thread, so logging adds no adapter request, delay or
 * lock to the Bluetooth path. Text is redacted before it reaches disk.
 *
 * Event categories: `traffic` is what the transport actually wrote or received;
 * `app` is what the app did (connection attempts, operation boundaries); and
 * `interpretation` is the app's explanation of what it saw, which may be wrong.
 */
class DiagnosticLog(
    private val dir: File,
    /** Static facts for every export: app version, build, Android version. */
    private val appInfo: Map<String, String>,
    private val limits: DiagnosticLimits = DiagnosticLimits(),
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val monoNanos: () -> Long = System::nanoTime,
) {
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "diagnostic-log").apply { isDaemon = true } }

    /** Starts a session file, rotating out the oldest sessions first. */
    fun startSession(purpose: String, adapterName: String?): DiagnosticSession {
        val startWall = wallMillis()
        // Wall time orders sessions across restarts; the counter orders sessions started
        // in the same millisecond; the random part keeps names unique.
        val id = "${FILE_TIME.get().format(Date(startWall))}-${"%06d".format(Locale.US, counter.incrementAndGet() % 1_000_000)}-${UUID.randomUUID().toString().take(8)}"
        val session = DiagnosticSession(id, startWall, monoNanos())
        worker.execute {
            rotate()
            session.open(File(dir, "$id.jsonl"))
            session.append("app", "session_start", startWall, session.startMono) {
                put("purpose", purpose)
                put("adapterName", adapterName?.let { DiagnosticRedactor.text(it).text })
                appInfo.forEach { (k, v) -> put(k, v) }
            }
        }
        return session
    }

    /** Waits until every event submitted so far is on disk (tests and export). */
    fun flush(timeoutMillis: Long = 5_000) {
        worker.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Result of building an export. [json] is null when no session is stored. */
    class Export(val json: String?, val sessions: Int, val truncated: Boolean, val unfinished: Int)

    /** All stored sessions, oldest first, as one JSON document. Already redacted on disk; redacted again here. */
    fun export(): Export {
        flush()
        val files = sessionFiles()
        if (files.isEmpty()) return Export(null, 0, false, 0)
        var truncated = false
        var unfinished = 0
        val sessions = buildJsonArray {
            files.forEach { file ->
                val events = file.readLines().filter { it.isNotBlank() }.map { line ->
                    runCatching { redact(json.parseToJsonElement(line)) }
                        .getOrElse { buildJsonObject { put("category", "app"); put("kind", "unreadable_line") } }
                }
                val kinds = events.mapNotNull { (it as? JsonObject)?.get("kind")?.jsonPrimitive?.content }
                val sessionTruncated = "truncated" in kinds
                val ended = "session_end" in kinds
                truncated = truncated || sessionTruncated
                if (!ended) unfinished++
                add(buildJsonObject {
                    put("id", file.nameWithoutExtension)
                    put("ended", ended)
                    put("truncated", sessionTruncated)
                    put("events", JsonArray(events))
                })
            }
        }
        val document = buildJsonObject {
            put("format", "bamboobd2-diagnostic-log")
            put("formatVersion", 1)
            put("exportedAtUtc", iso(wallMillis()))
            put("app", buildJsonObject { appInfo.forEach { (k, v) -> put(k, v) } })
            put("privacy", buildJsonObject {
                put("redactedByDefault", true)
                put("hidden", buildJsonArray {
                    add(JsonPrimitive("VINs, including VINs encoded in raw hex replies"))
                    add(JsonPrimitive("Bluetooth addresses"))
                })
                put("marker", "[REDACTED:...]")
                put("notAnonymous", "Adapter name, timestamps, protocol and module replies (for example part numbers) remain. Review before sharing.")
            })
            put("legend", buildJsonObject {
                put("traffic", "Bytes the app actually wrote to or received from the adapter. 'reply' is the exact text received; CR is shown as \\r.")
                put("app", "What the app did: connection attempts, operation start and end, closing the connection.")
                put("interpretation", "The app's own explanation or decoded outcome. Not observed traffic; it can be wrong.")
                put("tMs", "Milliseconds since the session started (monotonic clock).")
                put("exchange", "One request and everything received until the adapter's '>' prompt, the next write, or the end. prompt=false with endedBy=next_write means no prompt arrived in time.")
            })
            put("truncated", truncated)
            put("sessions", sessions)
        }
        return Export(json.encodeToString(JsonElement.serializer(), document), files.size, truncated, unfinished)
    }

    private fun sessionFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }

    private fun rotate() {
        if (!dir.isDirectory) dir.mkdirs()
        val files = sessionFiles()
        files.take((files.size - (limits.maxSessions - 1)).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(element.map { redact(it) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(DiagnosticRedactor.text(element.content).text) else element
    }

    /**
     * One Bluetooth connection's log. All state is confined to the log's worker thread;
     * public methods only take timestamps and queue work.
     */
    inner class DiagnosticSession internal constructor(val id: String, private val startWall: Long, internal val startMono: Long) : TrafficSink {
        private var file: File? = null
        private var bytes = 0
        private var seq = 0
        private var dropped = 0
        private var truncationNoted = false
        private var ended = false

        private inner class Exchange(val request: String?, val wroteMono: Long) {
            val text = StringBuilder()
            val chunks = mutableListOf<Pair<Long, Int>>()
        }
        private var open: Exchange? = null

        internal fun open(target: File) { file = target }

        override fun onWrite(request: String) = queue { wall, mono ->
            finishExchange("next_write", wall, mono)
            open = Exchange(request, mono)
        }

        override fun onWriteFailed(request: String, error: String) = queue { wall, mono ->
            finishExchange("write_failed", wall, mono)
            append("traffic", "write_failed", wall, mono) { put("request", request); put("error", error) }
        }

        override fun onRead(bytes: ByteArray) {
            val copy = bytes.copyOf()
            queue { wall, mono ->
                val exchange = open ?: Exchange(null, mono).also { open = it }
                exchange.chunks += (mono - exchange.wroteMono) to copy.size
                exchange.text.append(String(copy, Charsets.ISO_8859_1))
                if ('>' in exchange.text) finishExchange("prompt", wall, mono)
            }
        }

        override fun onStreamEnded(detail: String) = queue { wall, mono ->
            finishExchange("stream_end", wall, mono)
            append("traffic", "stream_end", wall, mono) { put("detail", detail) }
        }

        override fun onLocalClose() = queue { wall, mono ->
            finishExchange("local_close", wall, mono)
            append("app", "connection_closed", wall, mono) {}
        }

        /** An app action (connection attempt, result) with optional detail. */
        fun app(kind: String, detail: String? = null) = queue { wall, mono ->
            finishExchange("app_event", wall, mono)
            append("app", kind, wall, mono) { detail?.let { put("detail", it) } }
        }

        fun operationStart(name: String, detail: String? = null) = queue { wall, mono ->
            finishExchange("next_operation", wall, mono)
            append("app", "operation_start", wall, mono) { put("operation", name); detail?.let { put("detail", it) } }
        }

        fun operationEnd(name: String, outcome: String, detail: String? = null) = queue { wall, mono ->
            finishExchange("operation_end", wall, mono)
            append("app", "operation_end", wall, mono) { put("operation", name); put("outcome", outcome); detail?.let { put("detail", it) } }
        }

        /** The app's explanation of something; never confused with traffic. */
        fun interpretation(source: String, text: String) = queue { wall, mono ->
            finishExchange("app_event", wall, mono)
            append("interpretation", "note", wall, mono) { put("source", source); put("text", text) }
        }

        /** Ends the session; later calls are ignored. */
        fun end(reason: String) = queue { wall, mono ->
            if (ended) return@queue
            finishExchange("session_end", wall, mono)
            if (dropped > 0) append("app", "truncated", wall, mono, force = true) { put("droppedEvents", dropped); put("limitBytes", limits.sessionBytes) }
            append("app", "session_end", wall, mono, force = true) { put("reason", reason) }
            ended = true
        }

        private fun queue(block: (Long, Long) -> Unit) {
            val wall = wallMillis()
            val mono = monoNanos()
            runCatching { worker.execute { if (!ended) runCatching { block(wall, mono) } } }
        }

        private fun finishExchange(endedBy: String, wall: Long, mono: Long) {
            val exchange = open ?: return
            open = null
            val redacted = DiagnosticRedactor.reply(exchange.request, exchange.text.toString())
            append("traffic", "exchange", wall, exchange.wroteMono) {
                put("request", exchange.request)
                put("reply", redacted.text)
                put("replyBytes", exchange.text.length)
                put("prompt", '>' in exchange.text)
                put("endedBy", endedBy)
                put("endedTMs", (mono - exchange.wroteMono) / 1_000_000)
                put("chunks", buildJsonArray {
                    exchange.chunks.forEach { (at, size) -> add(buildJsonObject { put("afterMs", at / 1_000_000); put("bytes", size) }) }
                })
                if (redacted.redactions.isNotEmpty()) put("redactions", buildJsonArray { redacted.redactions.distinct().forEach { add(JsonPrimitive(it)) } })
            }
        }

        internal fun append(category: String, kind: String, wall: Long, mono: Long, force: Boolean = false, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
            val target = file ?: return
            val line = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("seq", seq)
                put("atUtc", iso(wall))
                put("tMs", ((mono - startMono) / 1_000_000).coerceAtLeast(0))
                put("category", category)
                put("kind", kind)
                fields()
            }.let { redact(it) }) + "\n"
            val limit = if (category == "traffic") limits.sessionTrafficBytes else limits.sessionBytes
            if (!force && bytes + line.length > limit) { dropped++; return }
            seq++
            bytes += line.length
            runCatching { target.appendText(line) }
        }
    }

    private companion object {
        val json = Json { prettyPrint = false }
        val FILE_TIME: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        private val ISO: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        fun iso(millis: Long): String = ISO.get().format(Date(millis))
    }
}

/** Kept for callers that only need the session type. */
typealias DiagnosticSession = DiagnosticLog.DiagnosticSession
