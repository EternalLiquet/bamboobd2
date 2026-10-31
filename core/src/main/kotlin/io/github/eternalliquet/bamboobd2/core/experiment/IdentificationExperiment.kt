package io.github.eternalliquet.bamboobd2.core.experiment

import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.protocol.*
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ExperimentOutcome { RUNNING, OBSERVED, STOPPED, CANCELLED }

@Serializable
data class ExperimentResult(
    val outcome: ExperimentOutcome,
    val explanation: String,
    val recording: ReplayScript,
    val cleanupConfirmed: Boolean,
    val preflightChunks: List<ReplayChunk> = emptyList(),
    val requestAtMillis: List<Long> = emptyList(),
) {
    val requiresAdapterPowerCycle: Boolean get() = recording.steps.isNotEmpty() && !cleanupConfirmed

    /** Private evidence only: raw bytes may contain identifiers and must be reviewed before sharing. */
    fun privateReport(): String = Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(serializer(), this)
}

/** Fixed experiment. Explicit LIVE entry point is separate from rehearsal and never accepts imported commands. */
object IdentificationExperiment {
    const val PLAN_ID = "two-endpoint-f187-manual-flow-control-v5"
    const val RAW_REQUEST = "0322F18700000000"
    const val FLOW_CONTROL = "30020A0000000000"
    private val setup = listOf("ATPC", "ATD", "ATM0", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATD0",
        "ATR1", "ATAT0", "ATST7D", "ATTP6", "ATCAF0", "ATCFC0", "ATCEA", "ATAL")
    private val routeA = listOf("ATSH729", "ATCRA793", "ATBI")
    private val routeB = listOf("ATPC", "ATCAF0", "ATCFC0", "ATCEA", "ATAL", "ATSH714", "ATCRA77E", "ATBI")
    val commands: List<String> get() = setup + routeA + RAW_REQUEST + routeB + RAW_REQUEST
    private const val UNKNOWN = "Diagnostic family, module identity and Porsche applicability remain unknown. No service fields or reset verified."

    fun preview(): String = buildString {
        appendLine("$PLAN_ID — fixed identification experiment; a live run requires an explicit operator action.")
        appendLine("Assumption: normal ISO-TP, 11-bit CAN, 500 kbit/s. A 729 -> 793 is an unverified reported endpoint, module identity unknown; B 714 -> 77E is a low-confidence Golf/VAG-analogy cluster hypothesis, not verified for Cayenne.")
        commands.forEachIndexed { index, command ->
            if (index == setup.size + routeA.size + 1) appendLine("Wait at least 1 second after A's terminal prompt.")
            appendLine("${index + 1}. $command\\r")
            if (command == RAW_REQUEST) appendLine("Conditional: after matching 14-byte 62 F1 87 first frame AND terminal prompt, send $FLOW_CONTROL\\r once on this same header. CTS, block size 2, separation 10ms.")
        }
        appendLine("Two F187 requests plus at most two manual flow-control frames (four host CAN writes total). Automatic flow control stays off. No retries, other ECU requests or cleanup writes.")
        appendLine("2s exchange / pending up to 5s from request / 60s total; 64 KiB transcript and 256 received lines.")
        appendLine("Only a 14-byte positive first frame permits one flow control after its prompt; collect CF21 and CF22 within 2s. Reject other lengths or sequences. The ECU flow-control timeout is unknown; waiting for the prompt may be too slow. Silence is inconclusive.")
        appendLine("Live execution drains startup bytes for up to 2s; a prompt or 250ms quiet period permits the existing ATPC handshake. Partial or malformed input stops before writing. ATPC must return OK and a prompt before further setup. The caller must exclusively own and close the connection, without reconnect.")
        appendLine("ATD restores configurable defaults; ATBI bypasses protocol validation. Reads may cause warnings, wake modules or drain power. Host limits do not bound physical controller retries.")
        appendLine(UNKNOWN)
    }

    /** FAKE has no physical connection; caller must provide a fresh, idle synthetic stream. */
    suspend fun run(
        transport: AdapterTransport,
        timeSource: TimeSource = TimeSource.Monotonic,
        onUpdate: (ExperimentResult) -> Unit = {},
    ): ExperimentResult {
        require(transport.kind == TransportKind.FAKE) { "Rehearsal accepts FAKE only; use the explicit live entry point for an operator-triggered run" }
        return execute(transport, timeSource, false, onUpdate)
    }

    /** Caller owns this exclusive connection and must close it on every exit, including cancellation. */
    suspend fun runLive(
        transport: AdapterTransport,
        timeSource: TimeSource = TimeSource.Monotonic,
        onUpdate: (ExperimentResult) -> Unit = {},
    ): ExperimentResult {
        require(transport.kind == TransportKind.LIVE) { "Live experiment requires a LIVE transport; imported REPLAY and FAKE cannot activate it" }
        return execute(transport, timeSource, true, onUpdate)
    }

    private suspend fun execute(
        transport: AdapterTransport,
        timeSource: TimeSource,
        live: Boolean,
        onUpdate: (ExperimentResult) -> Unit,
    ): ExperimentResult {
        val started = timeSource.markNow()
        val preflightChunks = mutableListOf<ReplayChunk>()
        val requestAtMillis = mutableListOf<Long>()
        val recorder = CaptureRecorder(timeSource)
        val observations = mutableListOf<String>()
        var retainedBytes = 0
        var receivedLines = 0
        var previousCr = false
        var attemptedFrames = 0
        var currentEvidence: String? = null
        fun publish(outcome: ExperimentOutcome, reason: String): ExperimentResult = ExperimentResult(
            outcome, (observations + listOfNotNull(currentEvidence) + reason + UNKNOWN).distinct().joinToString(" "),
            recorder.script(PLAN_ID, (if (live) "Bluetooth Classic identification attempt" else "Constructed software rehearsal") +
                "; attempted host writes and timed reads, not a CAN capture. Outcome: $outcome. $reason")
                .copy(provenance = if (live) "RAW_CAPTURE" else "SYNTHETIC", sourceTier = if (live) "capture" else "constructed"),
            false, // No default-restoration command is part of this proposal.
            preflightChunks.toList(), requestAtMillis.toList(),
        ).also(onUpdate)

        fun retainRead(chunk: ByteArray, preflight: Boolean = false) {
            if (chunk.isEmpty()) throw ExperimentStop("Empty adapter chunk")
            if (chunk.size > 65536 - retainedBytes) throw ExperimentStop("64 KiB transcript cap exceeded; oversized chunk omitted")
            retainedBytes += chunk.size
            if (preflight) preflightChunks += ReplayChunk(started.elapsedNow().inWholeMilliseconds, chunk.toString(Charsets.ISO_8859_1))
            else recorder.onRead(chunk)
        }

        suspend fun drainStartup() {
            publish(ExperimentOutcome.RUNNING, "Checking startup stream before ATPC; no bytes sent.")
            withTimeout(2000L) {
                var textInLine = false
                while (true) {
                    val chunk = withTimeoutOrNull(250L) {
                        transport.read() ?: throw ExperimentStop("Disconnected before startup handshake; no bytes sent.")
                    } ?: run {
                        if (textInLine) throw ExperimentStop("Partial startup line; no bytes sent.")
                        return@withTimeout
                    }
                    retainRead(chunk, preflight = true)
                    var prompt = false
                    for (byte in chunk) {
                        val c = (byte.toInt() and 255).toChar()
                        if (c == '\r' || c == '\n') {
                            if (!(c == '\n' && previousCr) && ++receivedLines > 256) throw ExperimentStop("256 received-line cap exceeded before idle prompt")
                            previousCr = c == '\r'
                            textInLine = false
                        } else {
                            previousCr = false
                            if (c == '>') {
                                if (prompt || textInLine) throw ExperimentStop("Unsettled initial stream; no bytes sent.")
                                prompt = true
                            } else {
                                if (c.code !in 32..126 || (prompt && c != ' '))
                                    throw ExperimentStop("Unsettled initial stream; no bytes sent.")
                                if (c != ' ') textInLine = true
                            }
                        }
                    }
                    publish(ExperimentOutcome.RUNNING, "Observed passive adapter bytes; no bytes sent.")
                    if (prompt) return@withTimeout
                }
            }
        }

        suspend fun exchange(command: String, responder: String? = null, assembly: MutableList<Int> = mutableListOf()): String {
            currentCoroutineContext().ensureActive()
            val vehicle = responder != null
            if (command != FLOW_CONTROL) currentEvidence = null
            if (vehicle && ++attemptedFrames > 4) throw ExperimentStop("Four-frame budget exhausted")
            val collecting = command == FLOW_CONTROL
            if (collecting && assembly.size != 6) throw ExperimentStop("No validated first frame for flow control")
            val writeBytes = command.length + 1
            if (writeBytes > 65536 - retainedBytes) throw ExperimentStop("64 KiB transcript cap exceeded before write")
            retainedBytes += writeBytes
            requestAtMillis += started.elapsedNow().inWholeMilliseconds
            recorder.onWrite(command)
            publish(ExperimentOutcome.RUNNING, "Attempting $command; delivery not established.")
            val start = timeSource.markNow()
            // The write is inside the same two-second deadline as the initial response.
            withTimeout(2000L) { transport.write(Request(command, if (vehicle) Request.Kind.VEHICLE else Request.Kind.ADAPTER)) }
            var deadline = 2000L
            val line = StringBuilder()
            val adapterLines = mutableListOf<String>()
            var status: String? = null
            var terminalData = false
            var sawEcho = false
            var nextSequence = 1
            fun consumeLine() {
                val text = line.toString().trim()
                line.clear()
                if (text.isEmpty()) return
                val compact = text.replace(" ", "").uppercase()
                if (compact == command && !sawEcho && status == null && adapterLines.isEmpty()) { sawEcho = true; return }
                if (!vehicle) { adapterLines += text; return }
                if (terminalData) throw ExperimentStop("Unexpected additional response at $command")
                if (collecting) {
                    val bytes = rawFrame(text, responder!!)
                    if (bytes.first() != 0x20 + nextSequence || nextSequence > 2)
                        throw ExperimentStop("Wrong consecutive-frame sequence; no retry")
                    val needed = minOf(7, 14 - assembly.size)
                    if (bytes.size < needed + 1) throw ExperimentStop("Truncated consecutive frame")
                    assembly += bytes.drop(1).take(needed)
                    nextSequence++
                    terminalData = assembly.size == 14
                    status = if (terminalData) identityDescription(assembly)
                        else "Incomplete F187 transfer: ${assembly.size}/14 payload bytes retained."
                    currentEvidence = "Responder $responder: $status"
                    return
                }
                if (text == "NO DATA" && status == null) {
                    status = "No data; route, transport, timing or support remain inconclusive."
                    terminalData = true
                    return
                }
                val result = inspectFrame(text, responder)
                result.firstPayload?.let { assembly.addAll(it) }
                status = result.description
                currentEvidence = "Responder $responder: $status"
                if (result.pending) deadline = 5000L else terminalData = true
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = deadline - start.elapsedNow().inWholeMilliseconds
                if (remaining <= 0) throw ExperimentStop("Deadline without terminal prompt at $command")
                val chunk = withTimeout(remaining) { transport.read() }
                    ?: throw ExperimentStop("Disconnected before terminal prompt at $command")
                retainRead(chunk)
                publish(ExperimentOutcome.RUNNING, "Received bytes for $command.")
                var prompt = false
                for (byte in chunk) {
                    val c = (byte.toInt() and 255).toChar()
                    if (prompt) {
                        if (c != ' ' && c != '\r' && c != '\n') throw ExperimentStop("Extra bytes after terminal prompt")
                        continue
                    }
                    if (c == '\r' || c == '\n') {
                        if (!(c == '\n' && previousCr) && ++receivedLines > 256) throw ExperimentStop("256 received-line cap exceeded")
                        previousCr = c == '\r'
                        consumeLine()
                    } else {
                        previousCr = false
                        if (c == '>') { consumeLine(); prompt = true }
                        else {
                            if (c.code !in 32..126) throw ExperimentStop("Non-ASCII or control byte in adapter response")
                            line.append(c)
                        }
                    }
                }
                if (prompt) {
                    if (!vehicle) {
                        validateAdapter(command, adapterLines)
                        return "Adapter accepted $command"
                    }
                    if (collecting && assembly.size != 14)
                        throw ExperimentStop("Incomplete F187 transfer after flow control; no retry")
                    return status ?: throw ExperimentStop("Empty vehicle response")
                }
            }
        }

        suspend fun readIdentity(responder: String): String {
            val assembly = mutableListOf<Int>()
            val first = exchange(RAW_REQUEST, responder, assembly)
            // exchange returns only after a fully consumed terminal prompt. Never
            // interrupt an outstanding ELM command to send flow control.
            if (assembly.isEmpty()) return first
            return exchange(FLOW_CONTROL, responder, assembly)
        }

        try {
            withTimeout(60000L) {
                if (live) drainStartup()
                for (command in setup + routeA) exchange(command)
                observations += "A (729 -> 793): ${readIdentity("793")}"
                delay(1000L)
                for (command in routeB) exchange(command)
                observations += "B (714 -> 77E): ${readIdentity("77E")}"
                currentEvidence = null
            }
        } catch (e: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                publish(ExperimentOutcome.CANCELLED, "Caller timeout/cancellation; no further writes. Partial transcript retained.")
                throw e
            }
            return publish(ExperimentOutcome.STOPPED, if (live && requestAtMillis.isEmpty())
                "Startup stream did not settle within 2 seconds; no bytes sent."
                else "Deadline reached without settled prompt; no further writes.")
        } catch (e: CancellationException) {
            publish(ExperimentOutcome.CANCELLED, "Cancelled; no further writes. Partial transcript retained.")
            throw e
        } catch (e: ExperimentStop) {
            return publish(ExperimentOutcome.STOPPED, e.message ?: "Stopped")
        } catch (e: TransportClosedException) {
            return publish(ExperimentOutcome.STOPPED, "Transport closed; write delivery uncertain. No retry or cleanup.")
        }
        return publish(ExperimentOutcome.OBSERVED, if (live) "Attempt ended at settled prompts; replies do not establish module identity or maintenance definitions. No cleanup writes."
            else "Synthetic attempt ended at settled prompts; no vehicle validation. No cleanup writes.")
    }

    private data class FrameResult(val description: String, val pending: Boolean = false, val firstPayload: List<Int>? = null)

    /** Raw CAN lines only: explicit 11-bit ID, no DLC digit, PCI included. */
    private fun rawFrame(line: String, responder: String): List<Int> {
        val compact = line.replace(" ", "").uppercase()
        if (!Regex("[0-9A-F]{5,19}").matches(compact) || (compact.length - 3) % 2 != 0)
            throw ExperimentStop("Malformed raw CAN line or unexpected adapter text")
        if (compact.take(3) != responder) throw ExperimentStop("Unexpected responder")
        return compact.drop(3).chunked(2).map { it.toInt(16) }
    }

    private fun identityDescription(payload: List<Int>): String {
        val identity = payload.drop(3)
        val hex = identity.joinToString(" ") { it.toString(16).uppercase().padStart(2, '0') }
        val text = if (identity.all { it in 32..126 }) identity.map { it.toChar() }.joinToString("") else "non-printable; see hex"
        return "Complete F187 response. Reported part-number text: $text; hex: $hex. Module attribution unverified."
    }

    private fun inspectFrame(line: String, responder: String): FrameResult {
        val bytes = rawFrame(line, responder)
        val pci = bytes.first()
        val payload: List<Int>
        var announced: Int? = null
        when (pci ushr 4) {
            0 -> {
                val length = pci and 15
                if (length !in 1..7 || bytes.size < length + 1) throw ExperimentStop("Malformed single-frame length")
                payload = bytes.drop(1).take(length)
            }
            1 -> {
                if (bytes.size != 8) throw ExperimentStop("Truncated first frame")
                announced = ((pci and 15) shl 8) + bytes[1]
                if (announced != 14) throw ExperimentStop("First-frame length outside this 14-byte experiment; no flow control")
                payload = bytes.drop(2)
            }
            else -> throw ExperimentStop("Unsolicited consecutive/flow-control frame")
        }
        if (payload.take(3) == listOf(0x62, 0xf1, 0x87) && payload.size > 3) {
            return if (announced != null) FrameResult("Incomplete F187 first frame: 6/14 payload bytes retained; awaiting terminal prompt before one flow control.", firstPayload = payload)
            else FrameResult(identityDescription(payload))
        }
        if (announced == null && payload.size == 3 && payload.take(2) == listOf(0x7f, 0x22)) {
            return when (payload[2]) {
                0x11, 0x31 -> FrameResult("Coherent negative ${payload[2].toString(16).uppercase()}; read rejected under these conditions.")
                0x78 -> FrameResult("Pending/incomplete; no final result observed.", pending = true)
                else -> throw ExperimentStop("Security, conditions, session or unclassified denial; stop whole run")
            }
        }
        throw ExperimentStop("Mismatched service/identifier or malformed reply")
    }

    private fun validateAdapter(command: String, lines: List<String>) {
        val value = lines.singleOrNull() ?: throw ExperimentStop("Unexpected adapter response at $command")
        val valid = if (command == "ATI") value.length in 1..128 && value != "?" &&
            !Regex("ERROR|NO DATA|SEARCHING|BUS INIT|STOPPED|BUFFER|UNABLE", RegexOption.IGNORE_CASE).containsMatchIn(value)
        else value == "OK"
        if (!valid) throw ExperimentStop("Adapter precondition failed at $command")
    }

    suspend fun rehearse(onUpdate: (ExperimentResult) -> Unit = {}): ExperimentResult =
        run(IdentificationRehearsalTransport(), onUpdate = onUpdate)

    private class ExperimentStop(message: String) : Exception(message)
}

/** Constructed responses, no device access. Route state is needed because both payloads are identical. */
internal class IdentificationRehearsalTransport : AdapterTransport {
    override val kind = TransportKind.FAKE
    private var responder = "793"
    private val pending = ArrayDeque<ByteArray>()
    override suspend fun write(request: Request) {
        if (request.text == "ATCRA77E") responder = "77E"
        val reply = when {
            request.text == "ATI" -> "ELM327 simulated\r>"
            request.kind == Request.Kind.ADAPTER -> "OK\r>"
            request.text == IdentificationExperiment.FLOW_CONTROL -> "793 21 4F 31 32 33 34 35 36\r793 22 37 00 00 00 00 00 00\r>"
            responder == "793" -> "793 10 0E 62 F1 87 44 45 4D\r>"
            else -> "77E 04 62 F1 87 41 00 00 00\r>"
        }
        pending.add(reply.toByteArray(Charsets.US_ASCII))
    }
    override suspend fun read(): ByteArray? = pending.removeFirstOrNull()
}
