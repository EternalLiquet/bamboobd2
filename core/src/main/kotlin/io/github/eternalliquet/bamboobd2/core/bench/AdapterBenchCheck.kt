package io.github.eternalliquet.bamboobd2.core.bench

import io.github.eternalliquet.bamboobd2.core.protocol.AdapterCommand
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterTransport
import io.github.eternalliquet.bamboobd2.core.protocol.Request
import io.github.eternalliquet.bamboobd2.core.protocol.RequestPolicy
import io.github.eternalliquet.bamboobd2.core.protocol.ResponseAssembler
import io.github.eternalliquet.bamboobd2.core.protocol.TransportClosedException
import io.github.eternalliquet.bamboobd2.core.reader.AdapterProfile
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull

/** What one bench command produced. */
sealed interface BenchOutcome {
    /** Reply lines before the prompt, without an echo of the command. */
    data class Reply(val lines: List<String>) : BenchOutcome

    /** No prompt before the deadline; [partial] is whatever text arrived. */
    data class NoReply(val partial: String) : BenchOutcome

    /** Not sent, because the check stopped earlier. */
    data object NotRun : BenchOutcome
}

data class BenchStep(
    val command: String,
    val outcome: BenchOutcome,
    /** From the write to the prompt or deadline; null if not sent. */
    val elapsedMillis: Long? = null,
    /** How many separate reads delivered the reply. */
    val chunks: Int = 0,
)

enum class BenchStop { COMPLETE, NO_RESPONSE, DISCONNECTED, CANCELLED }

data class BenchResult(
    val steps: List<BenchStep>,
    val stop: BenchStop,
    val detail: String = "",
    /** Text received before the first command, if any (for example a start-up banner). */
    val unsolicited: String = "",
)

/**
 * The L1 bench path: adapter-local commands only, never a vehicle request.
 * It is separate from [io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader]
 * and the session controller, so it cannot start a vehicle-read session.
 *
 * Each command is sent once and waited for up to the profile's adapter deadline.
 * A missed deadline stops the check without sending anything else, because any
 * character interrupts a busy adapter (ELM327DSJ p9). A disconnect stops it too.
 * Results gathered so far are kept in every case. Nothing is retried or reconnected.
 */
class AdapterBenchCheck(
    private val transport: AdapterTransport,
    private val profile: AdapterProfile = AdapterProfile.PROVISIONAL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val done = mutableListOf<BenchStep>()
    private var unsolicited = ""

    /** The result so far, as if the check stopped now for [stop]. Use after cancelling [run]. */
    fun partial(stop: BenchStop = BenchStop.CANCELLED, detail: String = ""): BenchResult =
        BenchResult(done + COMMANDS.drop(done.size).map { BenchStep(it.text, BenchOutcome.NotRun) }, stop, detail, unsolicited)

    suspend fun run(): BenchResult {
        try {
            unsolicited = drainStartup()
        } catch (e: TransportClosedException) {
            return partial(BenchStop.DISCONNECTED, "before the first command")
        }
        // A fresh buffer, so start-up text can never prefix the first reply.
        val assembler = ResponseAssembler()
        for (command in COMMANDS) {
            val started = timeSource.markNow()
            var chunks = 0
            val reply = try {
                transport.write(guard(RequestPolicy.adapter(command)))
                withTimeoutOrNull(profile.adapterCommandMillis) {
                    var completed = emptyList<String>()
                    while (completed.isEmpty()) {
                        completed = assembler.feed(transport.read() ?: throw TransportClosedException("stream ended"))
                        chunks++
                    }
                    completed.first()
                }
            } catch (e: TransportClosedException) {
                done += BenchStep(command.text, BenchOutcome.NoReply(assembler.pending().trim()), started.elapsedNow().inWholeMilliseconds, chunks)
                return partial(BenchStop.DISCONNECTED, "during ${command.text}")
            }
            val elapsed = started.elapsedNow().inWholeMilliseconds
            if (reply == null) {
                done += BenchStep(command.text, BenchOutcome.NoReply(assembler.pending().trim()), elapsed, chunks)
                return partial(BenchStop.NO_RESPONSE, "no prompt after ${command.text}")
            }
            done += BenchStep(command.text, BenchOutcome.Reply(replyLines(reply, command)), elapsed, chunks)
        }
        return partial(BenchStop.COMPLETE)
    }

    /** Text the adapter sends before any command is kept as an observation, not a reply. */
    private suspend fun drainStartup(): String {
        val text = StringBuilder()
        repeat(MAX_STARTUP_CHUNKS) {
            val chunk = withTimeoutOrNull(profile.quietMillis) {
                transport.read() ?: throw TransportClosedException("stream ended")
            } ?: return text.toString().trim()
            text.append(chunk.toString(Charsets.US_ASCII))
        }
        return text.toString().trim()
    }

    private fun replyLines(text: String, command: AdapterCommand): List<String> {
        val lines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val echo = lines.firstOrNull()?.uppercase()?.filterNot { it == ' ' } == command.text
        return if (echo) lines.drop(1) else lines
    }

    companion object {
        /** Identification and supply voltage only, from the owner-approved list (docs/PROTOCOL.md). */
        val COMMANDS: List<AdapterCommand> = listOf(AdapterCommand.VERSION_ID, AdapterCommand.DEVICE_DESCRIPTION, AdapterCommand.INPUT_VOLTAGE)

        private const val MAX_STARTUP_CHUNKS = 64

        /** Every write passes here: the bench path rejects all vehicle requests, allowlisted or not. */
        fun guard(request: Request): Request {
            require(request.kind == Request.Kind.ADAPTER) { "bench check never sends vehicle request '${request.text}'" }
            return request
        }
    }
}

/** Plain-text summary for the L1 test handoff. Holds no device identifiers. */
object BenchReport {
    fun text(result: BenchResult, appVersion: String, platform: String): String = buildString {
        appendLine("BambooBD2 $appVersion bench check (adapter-only), Bluetooth Classic")
        appendLine("Phone: $platform")
        appendLine("Result: ${result.stop.name.lowercase()}" + if (result.detail.isNotEmpty()) " (${result.detail})" else "")
        if (result.unsolicited.isNotEmpty()) appendLine("Before first command: ${result.unsolicited.oneLine()}")
        for (step in result.steps) {
            val timing = step.elapsedMillis?.let { " [${it} ms, ${step.chunks} chunk(s)]" } ?: ""
            val outcome = when (val o = step.outcome) {
                is BenchOutcome.Reply -> o.lines.joinToString(" | ").ifEmpty { "(empty reply)" }
                is BenchOutcome.NoReply -> "no reply" + if (o.partial.isNotEmpty()) ", partial: ${o.partial.oneLine()}" else ""
                BenchOutcome.NotRun -> "not run"
            }
            appendLine("${step.command}: $outcome$timing")
        }
    }.trimEnd()

    private fun String.oneLine() = replace('\r', ' ').replace('\n', ' ').trim()
}
