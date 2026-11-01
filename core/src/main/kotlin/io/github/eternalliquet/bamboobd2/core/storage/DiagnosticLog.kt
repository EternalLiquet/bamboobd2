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
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
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
 * session in [dir]. Transport callbacks take timestamps and admit bounded work without
 * waiting for disk. One background thread redacts, checkpoints and writes the files.
 * Logging adds no adapter request. Export and explicit flush wait for a worker snapshot.
 *
 * Event categories: `traffic` records receive data and host write boundaries;
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
    // Ordinary admission is nonblocking and precedes copying incoming bytes.
    // Start/end tasks are small, reserved once per admitted session; export waits off transport threads.
    private val pendingSlots = Semaphore(256)
    private val pendingBytes = AtomicInteger()
    private val sessionSlots = Semaphore(limits.maxSessions.coerceAtLeast(1) * 2)
    private val active = ConcurrentHashMap.newKeySet<DiagnosticSession>()
    private val lossWake = AtomicBoolean()
    private val exportLock = Any()

    internal data class Loss(val wall: Long, val mono: Long, val reason: String)

    private fun drainLoss() { active.forEach { s -> s.writeLoss(s.loss.getAndSet(null)) } }

    private fun wakeLoss() {
        if (lossWake.compareAndSet(false, true)) worker.execute {
            try { drainLoss() } finally {
                lossWake.set(false)
                if (active.any { it.loss.get() != null }) wakeLoss()
            }
        }
    }

    /** Starts a session file, rotating out the oldest sessions first. */
    fun startSession(purpose: String, adapterName: String?): DiagnosticSession {
        val startWall = wallMillis()
        // Wall time orders sessions across restarts; the counter orders sessions started
        // in the same millisecond; the random part keeps names unique.
        val id = "${FILE_TIME.get().format(Date(startWall))}-${"%06d".format(Locale.US, counter.incrementAndGet() % 1_000_000)}-${UUID.randomUUID().toString().take(8)}"
        val session = DiagnosticSession(id, startWall, monoNanos(), sessionSlots.tryAcquire())
        if (!session.admitted) {
            active.firstOrNull()?.lose("pending_session_limit")
            return session
        }
        val boundedPurpose = purpose.take(1024)
        val boundedAdapterName = adapterName?.take(1024)
        active += session
        worker.execute {
            runCatching {
                rotate()
                session.open(File(dir, "$id.jsonl"))
                session.append("app", "session_start", startWall, session.startMono) {
                    put("purpose", boundedPurpose)
                    put("adapterName", boundedAdapterName)
                    appInfo.forEach { (k, v) -> put(k, v) }
                }
            }.onFailure { session.retire() }
        }
        return session
    }

    /** Waits until every event submitted so far is on disk (tests and export). */
    fun flush(timeoutMillis: Long = 5_000) {
        worker.submit { drainLoss() }.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Result of building an export. [json] is null when no session is stored. */
    class Export(val json: String?, val sessions: Int, val truncated: Boolean, val unfinished: Int)

    /** All stored sessions, oldest first, as one JSON document. Already redacted on disk; redacted again here. */
    fun export(): Export = synchronized(exportLock) {
        worker.submit<Export> { drainLoss(); exportSnapshot() }.get(5_000, TimeUnit.MILLISECONDS)
    }

    private fun exportSnapshot(): Export {
        val files = sessionFiles()
        if (files.isEmpty()) return Export(null, 0, false, 0)
        var truncated = false
        var unfinished = 0
        val sessions = buildJsonArray {
            files.forEach { file ->
                val context = DiagnosticRedactor.Context()
                val events = file.readLines().filter { it.isNotBlank() }.map { line ->
                    runCatching { redact(json.parseToJsonElement(line), context) }
                        .getOrElse { buildJsonObject { put("category", "app"); put("kind", "unreadable_line") } }
                }
                var incomplete: JsonObject? = null
                val checkpoint = File(dir, file.nameWithoutExtension + ".pending")
                if (checkpoint.isFile) runCatching {
                    val pending = redact(json.parseToJsonElement(checkpoint.readText()), context).jsonObject
                    val exchangeId = pending["exchangeId"]
                    // A crash after append but before checkpoint deletion must not duplicate an exchange.
                    if (events.none { it.jsonObject["kind"]?.jsonPrimitive?.content == "exchange" && it.jsonObject["exchangeId"] == exchangeId }) {
                        incomplete = JsonObject(pending - setOf("seq", "inProgress", "endedBy") +
                            ("recovery" to JsonPrimitive("completion_not_recorded")))
                    }
                }
                val kinds = events.mapNotNull { (it as? JsonObject)?.get("kind")?.jsonPrimitive?.content }
                val sessionTruncated = "truncated" in kinds || "unreadable_line" in kinds || events.any {
                    ((it as? JsonObject)?.get("droppedEvents") as? JsonPrimitive)?.content?.toLongOrNull()?.let { count -> count > 0 } == true
                }
                val ended = "session_end" in kinds
                truncated = truncated || sessionTruncated
                if (!ended) unfinished++
                add(buildJsonObject {
                    put("id", file.nameWithoutExtension)
                    put("ended", ended)
                    put("truncated", sessionTruncated)
                    put("events", JsonArray(events))
                    incomplete?.let { put("incompleteExchange", it) }
                })
            }
        }
        val document = buildJsonObject {
            put("format", "bamboobd2-diagnostic-log")
            put("formatVersion", 2)
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
                put("traffic", "Observed receive data and host I/O boundaries. write_started is immediately before OutputStream.write; write_completed means write and flush returned, not ECU acknowledgement. Missing completion means delivery is unknown. Replies retain delimiters except marked redaction/withholding.")
                put("app", "What the app did: connection attempts, operation start and end, closing the connection.")
                put("interpretation", "The app's own explanation or decoded outcome. Not observed traffic; it can be wrong.")
                put("tMs", "Milliseconds since the session started (monotonic clock).")
                put("exchange", "One started write and its following input, or unsolicited input with request=null. write_attempt alone does not mean bytes were sent. incompleteExchange is the last durable checkpoint, with ambiguous payload withheld; completion may be missing due to process death or truncation. endedTMs is session-relative and durationMs is exchange-relative.")
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
        files.take((files.size - (limits.maxSessions - 1)).coerceAtLeast(0)).forEach {
            active.firstOrNull { session -> session.id == it.nameWithoutExtension }?.retire()
            File(dir, it.nameWithoutExtension + ".pending").delete()
            File(dir, it.nameWithoutExtension + ".pending.tmp").delete()
            check(it.delete()) { "Could not rotate diagnostic session" }
        }
    }

    private fun redact(element: JsonElement, context: DiagnosticRedactor.Context): JsonElement = when (element) {
        is JsonObject -> {
            val rawReply = (element["reply"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val isExchange = element["kind"]?.jsonPrimitive?.content == "exchange" && rawReply != null
            val reply = if (isExchange) {
                val request = (element["request"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (element["inProgress"]?.jsonPrimitive?.content == "true") DiagnosticRedactor.partialReply(request, rawReply)
                else if (element["prompt"]?.jsonPrimitive?.content == "true") context.reply(request, rawReply)
                else { context.clear(); DiagnosticRedactor.partialReply(request, rawReply) }
            } else null
            JsonObject(element.mapValues { (key, value) ->
                if (key == "reply" && reply != null) JsonPrimitive(reply.text) else redact(value, context)
            })
        }
        is JsonArray -> JsonArray(element.map { redact(it, context) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(DiagnosticRedactor.text(element.content).text) else element
    }

    /**
     * One Bluetooth connection's log. All state is confined to the log's worker thread;
     * public methods only take timestamps and queue work.
     */
    inner class DiagnosticSession internal constructor(val id: String, private val startWall: Long, internal val startMono: Long, internal val admitted: Boolean) : TrafficSink {
        private var file: File? = null
        private var bytes = 0
        private var seq = 0
        private val dropped = AtomicLong()
        private var truncationNoted = false
        // Retried only by later queued work/flush/export, never by a busy failure loop.
        private var pendingLoss: Loss? = null
        private var ended = false
        private val endRequested = AtomicBoolean()
        private val slotReleased = AtomicBoolean()
        private val redactionContext = DiagnosticRedactor.Context()
        private val trafficSealed = AtomicBoolean()
        internal val loss = AtomicReference<Loss?>()
        private var exchangeCounter = 0L
        private var lastWriteId = 0L
        private var attemptPending = false

        private inner class Exchange(val request: String?, val wroteWall: Long, val wroteMono: Long, val exchangeId: Long) {
            val text = StringBuilder()
            val chunks = mutableListOf<Pair<Long, Int>>()
            var saturated = false
        }
        private var open: Exchange? = null

        internal fun open(target: File) { file = target }

        // Rotation permanently retires a live handle, including callbacks already queued.
        internal fun retire() {
            endRequested.set(true); trafficSealed.set(true); ended = true
            open = null; file = null; loss.set(null); pendingLoss = null
            releaseSlot()
        }

        private fun releaseSlot() {
            active -= this
            if (slotReleased.compareAndSet(false, true)) sessionSlots.release()
        }

        override fun onWriteAttempt(request: String) = queue(request.length, traffic = true) { wall, mono ->
            lastWriteId = ++exchangeCounter
            attemptPending = true
            append("app", "write_attempt", wall, mono) { put("request", request); put("exchangeId", lastWriteId) }
        }

        override fun onWrite(request: String) = queue(request.length, traffic = true) { wall, mono ->
            finishExchange("next_write", wall, mono)
            if (!attemptPending) lastWriteId = ++exchangeCounter
            attemptPending = false
            open = Exchange(request, wall, mono, lastWriteId)
            append("traffic", "write_started", wall, mono) { put("request", request); put("exchangeId", lastWriteId) }
            checkpoint(wall, mono)
        }

        override fun onWriteCompleted(request: String) = queue(request.length, traffic = true) { wall, mono ->
            append("traffic", "write_completed", wall, mono) { put("request", request); put("exchangeId", lastWriteId) }
            checkpoint(wall, mono)
        }

        override fun onWriteFailed(request: String, error: String) = queue(request.length + error.length) { wall, mono ->
            finishExchange("write_failed", wall, mono)
            append("traffic", "write_failed", wall, mono) { put("request", request); put("error", error); put("exchangeId", lastWriteId); put("delivery", "unknown") }
        }

        override fun onRead(bytes: ByteArray) {
            submit(bytes.size, traffic = true) {
                val copy = bytes.copyOf()
                return@submit block@{ wall, mono ->
                    val exchange = open ?: Exchange(null, wall, mono, ++exchangeCounter).also { open = it }
                    if (exchange.saturated) return@block
                    exchange.chunks += (mono - exchange.wroteMono) to copy.size
                    val remaining = MAX_EXCHANGE_BYTES - exchange.text.length
                    exchange.text.append(String(copy, 0, minOf(copy.size, remaining), Charsets.ISO_8859_1))
                    if (copy.size > remaining || exchange.chunks.size >= MAX_EXCHANGE_CHUNKS) {
                        exchange.saturated = true
                        lose("exchange_buffer_limit", wall, mono)
                    }
                    if ('>' in exchange.text) finishExchange("prompt", wall, mono) else checkpoint(wall, mono)
                }
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
        fun app(kind: String, detail: String? = null) = queue(kind.length + (detail?.length ?: 0)) { wall, mono ->
            finishExchange("app_event", wall, mono)
            append("app", kind, wall, mono) { detail?.let { put("detail", it) } }
        }

        fun operationStart(name: String, detail: String? = null) = queue(name.length + (detail?.length ?: 0)) { wall, mono ->
            finishExchange("next_operation", wall, mono)
            append("app", "operation_start", wall, mono) { put("operation", name); detail?.let { put("detail", it) } }
        }

        fun operationEnd(name: String, outcome: String, detail: String? = null) = queue(name.length + outcome.length + (detail?.length ?: 0)) { wall, mono ->
            finishExchange("operation_end", wall, mono)
            append("app", "operation_end", wall, mono) { put("operation", name); put("outcome", outcome); detail?.let { put("detail", it) } }
        }

        /** The app's explanation of something; never confused with traffic. */
        fun interpretation(source: String, text: String) = queue(source.length + text.length) { wall, mono ->
            finishExchange("app_event", wall, mono)
            append("interpretation", "note", wall, mono) { put("source", source); put("text", text) }
        }

        /** Ends the session; later calls are ignored. */
        fun end(reason: String) {
            if (!admitted || !endRequested.compareAndSet(false, true)) return
            val wall = wallMillis(); val mono = monoNanos()
            val boundedReason = reason.take(1024)
            worker.execute {
                try {
                    if (ended) return@execute
                    drainLoss()
                    finishExchange("session_end", wall, mono)
                    drainLoss()
                    append("app", "session_end", wall, mono, force = true) { put("reason", boundedReason); put("droppedEvents", dropped.get()) }
                    ended = true
                } finally { releaseSlot() }
            }
        }

        private fun queue(cost: Int = 0, traffic: Boolean = false, block: (Long, Long) -> Unit) = submit(cost, traffic) { block }

        private fun submit(cost: Int, traffic: Boolean, build: () -> ((Long, Long) -> Unit)) {
            if (!admitted || endRequested.get() || (traffic && trafficSealed.get())) return
            val wall = wallMillis()
            val mono = monoNanos()
            if (cost > MAX_PENDING_BYTES || !pendingSlots.tryAcquire()) { lose("pending_event_limit", wall, mono); return }
            if (pendingBytes.addAndGet(cost) > MAX_PENDING_BYTES) {
                pendingBytes.addAndGet(-cost); pendingSlots.release(); lose("pending_byte_limit", wall, mono); return
            }
            val block = build()
            worker.execute {
                try { drainLoss(); if (!ended) runCatching { block(wall, mono) }.onFailure { lose("storage_or_log_error", wall, mono) } }
                finally { pendingBytes.addAndGet(-cost); pendingSlots.release(); drainLoss() }
            }
        }

        internal fun lose(reason: String, wall: Long = wallMillis(), mono: Long = monoNanos()) {
            trafficSealed.set(true)
            dropped.incrementAndGet()
            loss.compareAndSet(null, Loss(wall, mono, reason))
            wakeLoss()
        }

        internal fun writeLoss(value: Loss?) {
            if (truncationNoted || ended) return
            if (pendingLoss == null) pendingLoss = value
            val first = pendingLoss ?: return
            if (file == null) return
            if (append("app", "truncated", first.wall, first.mono, force = true) {
                put("reason", first.reason); put("droppedEvents", dropped.get()); put("limitBytes", limits.sessionBytes)
            }) {
                truncationNoted = true
                pendingLoss = null
            }
        }

        private fun finishExchange(endedBy: String, wall: Long, mono: Long) {
            val exchange = open ?: return
            open = null
            val stored = append("traffic", "exchange", exchange.wroteWall, exchange.wroteMono) {
                put("exchangeId", exchange.exchangeId)
                put("request", exchange.request)
                put("reply", exchange.text.toString())
                put("replyBytes", exchange.text.length)
                put("prompt", '>' in exchange.text)
                put("endedBy", endedBy)
                put("inProgress", false)
                put("endedAtUtc", iso(wall))
                put("endedTMs", (mono - startMono) / 1_000_000)
                put("durationMs", (mono - exchange.wroteMono) / 1_000_000)
                put("replyTruncated", exchange.saturated)
                put("chunks", buildJsonArray {
                    exchange.chunks.forEach { (at, size) -> add(buildJsonObject { put("afterMs", at / 1_000_000); put("bytes", size) }) }
                })
            }
            if (stored) File(dir, "$id.pending").delete()
        }

        private fun checkpoint(wall: Long, mono: Long) {
            val exchange = open ?: return
            val event = buildJsonObject {
                put("seq", seq); put("category", "traffic"); put("kind", "exchange")
                put("exchangeId", exchange.exchangeId); put("request", exchange.request)
                put("atUtc", iso(exchange.wroteWall)); put("tMs", (exchange.wroteMono - startMono) / 1_000_000)
                put("reply", exchange.text.toString())
                put("replyBytes", exchange.text.length); put("prompt", false); put("inProgress", true)
                put("endedBy", "in_progress"); put("checkpointAtUtc", iso(wall)); put("checkpointTMs", (mono - startMono) / 1_000_000)
                put("replyTruncated", exchange.saturated)
                put("chunks", buildJsonArray { exchange.chunks.forEach { (at, size) ->
                    add(buildJsonObject { put("afterMs", at / 1_000_000); put("bytes", size) })
                } })
            }
            val text = json.encodeToString(JsonElement.serializer(), redact(event, redactionContext))
            if (text.toByteArray(Charsets.UTF_8).size + bytes > limits.sessionTrafficBytes) {
                lose("session_byte_limit", wall, mono)
                return
            }
            val temp = File(dir, "$id.pending.tmp")
            val target = File(dir, "$id.pending")
            temp.writeText(text)
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }

        internal fun append(category: String, kind: String, wall: Long, mono: Long, force: Boolean = false, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Boolean {
            val target = file ?: return false
            val line = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("seq", seq)
                put("atUtc", iso(wall))
                put("tMs", ((mono - startMono) / 1_000_000).coerceAtLeast(0))
                put("category", category)
                put("kind", kind)
                fields()
            }.let { redact(it, redactionContext) }) + "\n"
            val limit = if (category == "traffic") limits.sessionTrafficBytes else limits.sessionBytes
            val size = line.toByteArray(Charsets.UTF_8).size
            // A completed exchange replaces its checkpoint; other events share its budget.
            val pendingSize = if (kind == "exchange") 0 else File(dir, "$id.pending").length()
            if (!force && bytes + size + pendingSize > limit) { lose("session_byte_limit", wall, mono); return false }
            if (runCatching { target.appendText(line) }.isFailure) {
                // writeLoss retains an undurable marker for the next external task.
                // Re-enqueuing its own failure here would spin while storage is unavailable.
                if (kind != "truncated") lose("storage_error", wall, mono)
                return false
            }
            seq++
            bytes += size
            return true
        }
    }

    private companion object {
        const val MAX_PENDING_BYTES = 64_000
        const val MAX_EXCHANGE_BYTES = 64_000
        const val MAX_EXCHANGE_CHUNKS = 512
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
