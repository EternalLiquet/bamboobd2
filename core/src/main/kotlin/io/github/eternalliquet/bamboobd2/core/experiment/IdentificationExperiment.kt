package io.github.eternalliquet.bamboobd2.core.experiment

import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.protocol.*
import kotlin.time.TimeSource
import kotlinx.coroutines.*

enum class ExperimentOutcome { RUNNING, OBSERVED, STOPPED, CANCELLED }

data class ExperimentResult(
    val outcome: ExperimentOutcome,
    val explanation: String,
    val recording: ReplayScript,
    val cleanupConfirmed: Boolean,
) {
    val requiresAdapterPowerCycle: Boolean get() = !cleanupConfirmed
}

/** Fixed software rehearsal. No activation flag, arbitrary commands, LIVE or imported REPLAY execution. */
object IdentificationExperiment {
    const val PLAN_ID = "two-endpoint-f187-no-flow-control-v2"
    const val RAW_REQUEST = "0322F18700000000"
    private val setup = listOf("ATPC", "ATD", "ATM0", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATD0",
        "ATR1", "ATAT0", "ATST7D", "ATTP6", "ATCAF0", "ATCFC0", "ATCEA", "ATAL")
    private val routeA = listOf("ATSH729", "ATCRA793", "ATBI")
    private val routeB = listOf("ATPC", "ATCAF0", "ATCFC0", "ATCEA", "ATAL", "ATSH717", "ATCRA781", "ATBI")
    val commands: List<String> get() = setup + routeA + RAW_REQUEST + routeB + RAW_REQUEST
    private const val UNKNOWN = "Diagnostic family, module identity and Porsche applicability remain unknown. No service fields or reset verified."

    fun preview(): String = buildString {
        appendLine("$PLAN_ID — LIVE locked; synthetic rehearsal only.")
        appendLine("Assumption: normal ISO-TP, 11-bit CAN, 500 kbit/s. A 729 -> 793 is reported PCM; B 717 -> 781 is a low-confidence cluster hypothesis.")
        commands.forEachIndexed { index, command ->
            if (index == setup.size + routeA.size + 1) appendLine("Wait at least 1 second after A's terminal prompt.")
            appendLine("${index + 1}. $command\\r")
        }
        appendLine("Two raw 03 22 F1 87 00 00 00 00 frames maximum. No retries, flow control, other ECU requests or cleanup writes.")
        appendLine("2s exchange / pending up to 5s from request / 60s total; 64 KiB transcript and 256 received lines.")
        appendLine("First frame means incomplete, no flow control sent. Silence is inconclusive. Abort on malformed/mismatched data, denial, lost prompt or cancellation.")
        appendLine("Future live integration requires exclusive adapter ownership and a locally established idle boundary, then disconnect without reconnect. Neither exists in this rehearsal.")
        appendLine("ATD restores configurable defaults; ATBI bypasses protocol validation. Reads may cause warnings, wake modules or drain power. Host limits do not bound physical controller retries.")
        appendLine(UNKNOWN)
    }

    /** FAKE has no physical connection; caller must provide a fresh, idle synthetic stream. */
    suspend fun run(
        transport: AdapterTransport,
        timeSource: TimeSource = TimeSource.Monotonic,
        onUpdate: (ExperimentResult) -> Unit = {},
    ): ExperimentResult {
        require(transport.kind == TransportKind.FAKE) { "LIVE and imported REPLAY are locked pending exact-test owner review and separate integration" }
        val recorder = CaptureRecorder(timeSource)
        val observations = mutableListOf<String>()
        var retainedBytes = 0
        var receivedLines = 0
        var previousCr = false
        var attemptedFrames = 0
        var currentEvidence: String? = null
        fun publish(outcome: ExperimentOutcome, reason: String): ExperimentResult = ExperimentResult(
            outcome, (observations + listOfNotNull(currentEvidence) + reason + UNKNOWN).distinct().joinToString(" "),
            recorder.script(PLAN_ID, "Constructed software rehearsal; attempted host writes and timed reads, not a CAN capture.")
                .copy(provenance = "SYNTHETIC", sourceTier = "constructed"),
            false, // No default-restoration command is part of this proposal.
        ).also(onUpdate)

        suspend fun exchange(command: String, responder: String? = null): String {
            currentCoroutineContext().ensureActive()
            val vehicle = responder != null
            currentEvidence = null
            if (vehicle && ++attemptedFrames > 2) throw ExperimentStop("Two-frame budget exhausted")
            val writeBytes = command.length + 1
            if (writeBytes > 65536 - retainedBytes) throw ExperimentStop("64 KiB transcript cap exceeded before write")
            retainedBytes += writeBytes
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
            fun consumeLine() {
                val text = line.toString().trim()
                line.clear()
                if (text.isEmpty()) return
                val compact = text.replace(" ", "").uppercase()
                if (compact == command && !sawEcho && status == null && adapterLines.isEmpty()) { sawEcho = true; return }
                if (!vehicle) { adapterLines += text; return }
                if (terminalData) throw ExperimentStop("Unexpected additional response at $command")
                if (text == "NO DATA" && status == null) {
                    status = "No data; route, transport, timing or support remain inconclusive."
                    terminalData = true
                    return
                }
                val result = inspectFrame(text, responder)
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
                if (chunk.isEmpty()) throw ExperimentStop("Empty chunk at $command")
                if (chunk.size > 65536 - retainedBytes) throw ExperimentStop("64 KiB transcript cap exceeded; oversized chunk omitted")
                retainedBytes += chunk.size
                recorder.onRead(chunk)
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
                    return status ?: throw ExperimentStop("Empty vehicle response")
                }
            }
        }

        try {
            withTimeout(60000L) {
                for (command in setup + routeA) exchange(command)
                observations += "A (729 -> 793): ${exchange(RAW_REQUEST, "793")}"
                delay(1000L)
                for (command in routeB) exchange(command)
                observations += "B (717 -> 781): ${exchange(RAW_REQUEST, "781")}"
                currentEvidence = null
            }
        } catch (e: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                publish(ExperimentOutcome.CANCELLED, "Caller timeout/cancellation; no further writes. Partial transcript retained.")
                throw e
            }
            return publish(ExperimentOutcome.STOPPED, "Deadline reached without settled prompt; no further writes.")
        } catch (e: CancellationException) {
            publish(ExperimentOutcome.CANCELLED, "Cancelled; no further writes. Partial transcript retained.")
            throw e
        } catch (e: ExperimentStop) {
            return publish(ExperimentOutcome.STOPPED, e.message ?: "Stopped")
        } catch (e: TransportClosedException) {
            return publish(ExperimentOutcome.STOPPED, "Transport closed; write delivery uncertain. No retry or cleanup.")
        }
        return publish(ExperimentOutcome.OBSERVED, "Synthetic attempt ended at settled prompts; no vehicle validation. No cleanup writes.")
    }

    private data class FrameResult(val description: String, val pending: Boolean = false)

    /** Raw CAN lines only: explicit 11-bit ID, no DLC digit, PCI included, no ISO-TP reassembly. */
    private fun inspectFrame(line: String, responder: String): FrameResult {
        val compact = line.replace(" ", "").uppercase()
        if (!Regex("[0-9A-F]{5,19}").matches(compact) || (compact.length - 3) % 2 != 0)
            throw ExperimentStop("Malformed raw CAN line or unexpected adapter text")
        if (compact.take(3) != responder) throw ExperimentStop("Unexpected responder")
        val bytes = compact.drop(3).chunked(2).map { it.toInt(16) }
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
                if (announced <= 7) throw ExperimentStop("Malformed first-frame length")
                payload = bytes.drop(2)
            }
            else -> throw ExperimentStop("Unsolicited consecutive/flow-control frame")
        }
        if (payload.take(3) == listOf(0x62, 0xf1, 0x87) && payload.size > 3) {
            return if (announced != null) FrameResult("Incomplete—no flow control sent; announced $announced bytes; prefix retained, no complete identity.")
            else FrameResult("Complete single-frame 62 F1 87 reply; identity bytes opaque and attribution provisional.")
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
        if (request.text == "ATCRA781") responder = "781"
        val reply = when {
            request.text == "ATI" -> "ELM327 simulated\r>"
            request.kind == Request.Kind.ADAPTER -> "OK\r>"
            responder == "793" -> "793 10 0A 62 F1 87 44 45 4D\r>"
            else -> "781 04 62 F1 87 41 00 00 00\r>"
        }
        pending.add(reply.toByteArray(Charsets.US_ASCII))
    }
    override suspend fun read(): ByteArray? = pending.removeFirstOrNull()
}
