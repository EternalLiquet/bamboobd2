package io.github.eternalliquet.bamboobd2.core.experiment

import io.github.eternalliquet.bamboobd2.core.capture.CaptureRecorder
import io.github.eternalliquet.bamboobd2.core.protocol.*
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout

enum class ExperimentOutcome { RUNNING, OBSERVED, STOPPED, CANCELLED }

data class ExperimentResult(
    val outcome: ExperimentOutcome,
    val explanation: String,
    val recording: ReplayScript,
    val cleanupConfirmed: Boolean,
) {
    val requiresAdapterPowerCycle: Boolean get() = !cleanupConfirmed
}

/**
 * A fixed, simulated rehearsal of the proposal in docs/IDENTIFICATION_EXPERIMENT.md.
 * There is deliberately no public activation flag or imported command plan. LIVE
 * and REPLAY transports are refused before any write. This is not a car reader.
 */
object IdentificationExperiment {
    const val PLAN_ID = "729-identity-v1"
    private val setup = listOf("ATD", "ATE0", "ATL0", "ATH1", "ATI", "ATRV", "ATM0", "ATTP6",
        "ATCAF1", "ATAT0", "ATSTFF", "0100", "ATDPN", "ATSH729", "ATCRA793",
        "ATFCSH729", "ATFCSD300000", "ATFCSM1", "ATCFC1")
    val commands: List<String> get() = setup + listOf("22F187", "22F189", "ATD")
    private val format = ResponseFormat(true, ProtocolForm.CAN_11BIT)
    private const val EXCHANGE_BYTES = 8192
    private const val UNKNOWN = "Module identity and Porsche applicability remain unknown; selected UDS hypothesis only. No service fields or reset verified."

    fun preview(): String = buildString {
        appendLine("$PLAN_ID — DISABLED for LIVE; hypothesis route 729 -> 793, possibly PCM, not verified cluster.")
        commands.forEachIndexed { index, command -> appendLine("${index + 1}. $command\\r") }
        appendLine("Only issue 22F189 after a complete correlated 62 F1 87 payload from 793.")
        appendLine("2s setup / 3s vehicle / 45s total; 8192 response bytes per request; no retries or fallback.")
        appendLine("All host writes/chunks retained. Automatic CAN flow-control frames are not observed by this transcript.")
        appendLine("Failure/cancellation without checked ATD requires adapter power-cycle before normal scans.")
        appendLine(UNKNOWN)
    }

    /** onUpdate retains a partial transcript even if caller cancellation prevents a returned result. */
    suspend fun run(
        transport: AdapterTransport,
        timeSource: TimeSource = TimeSource.Monotonic,
        onUpdate: (ExperimentResult) -> Unit = {},
    ): ExperimentResult {
        require(transport.kind == TransportKind.FAKE) { "Experiment is disabled for LIVE and imported REPLAY; exact-test owner approval and separate integration required" }
        val recorder = CaptureRecorder(timeSource)
        val started = timeSource.markNow()
        var atPrompt = false
        var cleaned = false
        var defaultsAccepted = false
        var cleanupAttempted = false
        fun snapshot(outcome: ExperimentOutcome, explanation: String): ExperimentResult = ExperimentResult(
            outcome, "$explanation $UNKNOWN",
            recorder.script(PLAN_ID, "Constructed simulation of $PLAN_ID; host transcript, not CAN capture.")
                .copy(provenance = "SYNTHETIC", sourceTier = "constructed"), cleaned,
        )
        fun publish(outcome: ExperimentOutcome, explanation: String): ExperimentResult = snapshot(outcome, explanation).also(onUpdate)

        suspend fun exchange(command: String): List<EcuMessage> {
            currentCoroutineContext().ensureActive()
            atPrompt = false
            recorder.onWrite(command) // Records an attempted write; not proof of wire delivery.
            publish(ExperimentOutcome.RUNNING, "Attempting $command.")
            val vehicle = !command.startsWith("AT")
            return withTimeout(if (vehicle) 3000L else 2000L) {
                currentCoroutineContext().ensureActive()
                transport.write(Request(command, if (vehicle) Request.Kind.VEHICLE else Request.Kind.ADAPTER))
                val assembler = ResponseAssembler()
                var byteCount = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val chunk = transport.read() ?: throw ExperimentStop("Disconnected or incomplete response to $command")
                    if (chunk.isEmpty()) throw ExperimentStop("Empty chunk at $command; transport made no progress")
                    // Bound retention, too. Do not silently claim an omitted oversized chunk was captured.
                    if (chunk.size > EXCHANGE_BYTES - byteCount) throw ExperimentStop("Response limit exceeded at $command; oversized chunk not retained")
                    byteCount += chunk.size
                    recorder.onRead(chunk)
                    publish(ExperimentOutcome.RUNNING, "Received bytes for $command.")
                    val responses = assembler.feed(chunk)
                    if (responses.isEmpty()) continue
                    // A second prompt or trailing non-whitespace may be unsolicited/late data. Never assign it to the next request.
                    if (responses.size != 1 || assembler.pending().isNotBlank()) throw ExperimentStop("Unexpected extra response bytes at $command")
                    atPrompt = true
                    val text = responses.single()
                    if (!vehicle) {
                        validateAdapter(command, text)
                        return@withTimeout emptyList()
                    }
                    if (text.lineSequence().any { line ->
                        line.uppercase().contains("SEARCHING") || line.uppercase().contains("BUS INIT")
                    }) throw ExperimentStop("Unexpected search or bus initialization at $command")
                    val parsed = ElmResponseParser.parseRecorded(text, command, format)
                    if (parsed !is ParsedResponse.Messages) throw ExperimentStop("$command: $parsed")
                    if (command == "0100") {
                        if (parsed.messages.isEmpty() || parsed.messages.any { it.ecu == null || it.bytes.size != 6 || it.bytes.take(2) != listOf(0x41, 0) })
                            throw ExperimentStop("Generic baseline was not a complete 0100 response")
                        return@withTimeout parsed.messages
                    }
                    val expected = listOf(0x62, 0xf1, command.takeLast(2).toInt(16))
                    var final: EcuMessage? = null
                    for (message in parsed.messages) {
                        if (message.ecu != "793") throw ExperimentStop("Unexpected responder at $command")
                        if (message.bytes == listOf(0x7f, 0x22, 0x78) && final == null) continue
                        if (message.bytes.size <= 3 || message.bytes.take(3) != expected || final != null)
                            throw ExperimentStop("Negative, duplicate, malformed or mismatched reply to $command")
                        final = message
                    }
                    if (final != null) return@withTimeout listOf(final)
                    // Pending only: keep the original deadline and await bytes, never resend.
                    if (parsed.messages.isEmpty()) throw ExperimentStop("Empty response to $command")
                    atPrompt = false
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList()
            }
        }

        var failure: String? = null
        try {
            withTimeout(45000L) {
                for (command in setup + listOf("22F187", "22F189")) {
                    exchange(command)
                    if (command == "ATD") defaultsAccepted = true
                }
                cleanupAttempted = true
                exchange("ATD")
                cleaned = true
            }
        } catch (e: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                publish(ExperimentOutcome.CANCELLED, "Caller cancelled or timed out; partial transcript retained. No cleanup was sent.")
                throw e
            }
            atPrompt = false
            failure = "Experiment deadline reached; no further requests."
        } catch (e: CancellationException) {
            publish(ExperimentOutcome.CANCELLED, "Cancelled; partial transcript retained. No cleanup was sent.")
            throw e
        } catch (e: ExperimentStop) {
            failure = e.message ?: "Stopped"
        } catch (e: TransportClosedException) {
            failure = "Transport closed; delivery uncertain."
            atPrompt = false
        }
        // Only a synchronized prompt permits a cleanup command. Never send while an ECU response may still be arriving.
        val remainingMillis = 45000L - started.elapsedNow().inWholeMilliseconds
        if (failure != null && defaultsAccepted && !cleanupAttempted && atPrompt && remainingMillis > 0) {
            try {
                withTimeout(remainingMillis) { exchange("ATD") }
                cleaned = true
            } catch (e: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) {
                    publish(ExperimentOutcome.CANCELLED, "Cancelled during cleanup; power-cycle required.")
                    throw e
                }
            } catch (e: CancellationException) {
                publish(ExperimentOutcome.CANCELLED, "Cancelled during cleanup; power-cycle required.")
                throw e
            } catch (_: ExperimentStop) {
                // Keep cleanup unconfirmed.
            } catch (_: TransportClosedException) {
                // Keep cleanup unconfirmed.
            }
        }
        return publish(if (failure == null) ExperimentOutcome.OBSERVED else ExperimentOutcome.STOPPED,
            failure ?: "Constructed identification replies observed under the declared hypothesis.")
    }

    private fun validateAdapter(command: String, text: String) {
        val lines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (lines.firstOrNull()?.replace(" ", "")?.uppercase() == command) lines.removeAt(0)
        val value = lines.singleOrNull() ?: throw ExperimentStop("Unexpected adapter response at $command")
        val valid = when (command) {
            "ATI" -> value.isNotBlank() && value != "?" && value.length <= 128 && value.all { it.code in 32..126 } &&
                !value.contains("ERROR", true) && value != "NO DATA"
            "ATRV" -> Regex("[0-9]{1,2}\\.[0-9]{1,2}V").matches(value) && value.dropLast(1).toDouble() in 12.0..15.0
            "ATDPN" -> value == "6"
            else -> value == "OK"
        }
        if (!valid) throw ExperimentStop("Adapter precondition failed at $command")
    }

    /** Deliberately invented data, unrelated to any observed Porsche part or counter. */
    fun syntheticReplies(): Map<String, List<ByteArray>> = commands.associateWith { FakeTransport.text("OK\r>") } + mapOf(
        "ATI" to FakeTransport.text("ELM327 simulated\r>"),
        "ATRV" to FakeTransport.text("12.6V\r>"),
        "ATDPN" to FakeTransport.text("6\r>"),
        "0100" to FakeTransport.text("7E8 06 41 00 00 00 00 00\r>"),
        "22F187" to FakeTransport.text("793 06 62 F1 87 44 45 4D\r>"),
        "22F189" to FakeTransport.text("793 04 62 F1 89 31\r>"),
    )

    private class ExperimentStop(message: String) : Exception(message)
}
