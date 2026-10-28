package io.github.eternalliquet.bamboobd2.core.reader

import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterCommand
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterErrorKind
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterTransport
import io.github.eternalliquet.bamboobd2.core.protocol.ElmResponseParser
import io.github.eternalliquet.bamboobd2.core.protocol.ObdDecoder
import io.github.eternalliquet.bamboobd2.core.protocol.ObdResult
import io.github.eternalliquet.bamboobd2.core.protocol.ParsedResponse
import io.github.eternalliquet.bamboobd2.core.protocol.ProtocolForm
import io.github.eternalliquet.bamboobd2.core.protocol.ReadPlan
import io.github.eternalliquet.bamboobd2.core.protocol.Request
import io.github.eternalliquet.bamboobd2.core.protocol.RequestPolicy
import io.github.eternalliquet.bamboobd2.core.protocol.ResponseAssembler
import io.github.eternalliquet.bamboobd2.core.protocol.ResponseFormat
import io.github.eternalliquet.bamboobd2.core.protocol.TransportClosedException
import io.github.eternalliquet.bamboobd2.core.protocol.TransportKind
import io.github.eternalliquet.bamboobd2.core.protocol.VehicleRequest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Host-side deadlines. **Provisional and unverified**: these numbers were chosen
 * for offline replay and have not been measured against any adapter. Authorized
 * L1 testing must measure real timing before they mean anything.
 */
data class AdapterProfile(
    /** From writing an adapter command to its prompt. */
    val adapterCommandMillis: Long,
    /** From writing a vehicle request to its prompt. Protocol search can make the first one slow. */
    val vehicleRequestMillis: Long,
    /** Added once when a "7F xx 78" (response pending) line has been seen; J1979 allows 5 s (ELM327DSJ p45). */
    val pendingExtensionMillis: Long,
    /** After a missed deadline, how long to wait passively for the adapter's prompt. */
    val resyncMillis: Long,
    /** Before each write, the adapter must stay silent this long; anything received is stale. */
    val quietMillis: Long,
) {
    companion object {
        val PROVISIONAL = AdapterProfile(
            adapterCommandMillis = 2_000,
            vehicleRequestMillis = 10_000,
            pendingExtensionMillis = 5_000,
            resyncMillis = 5_000,
            quietMillis = 50,
        )
    }
}

/**
 * The real protocol path: ELM327-style adapter commands and OBD-II requests over
 * any [AdapterTransport]. Only the transport differs between replay and, later,
 * hardware. Provenance comes from the transport's declared kind.
 *
 * Rules (ELM327DSJ page numbers):
 * - Nothing is written until the adapter is idle at its prompt; any character
 *   sent while it works interrupts it (p9). Bytes that arrive while idle belong
 *   to no request and are discarded as stale.
 * - Each exchange has a deadline. A missed deadline is followed by a passive wait
 *   for the prompt; only if the prompt comes is the same request retried, once.
 *   No reset or other command is ever sent to recover.
 * - A disconnect stops the session; the reader never reconnects.
 *
 * Run it on a single-threaded dispatcher (the main thread, or a test scheduler).
 * On a multi-threaded one, a deadline firing just as [AdapterTransport.read]
 * returns could drop that chunk.
 */
class ElmObdReader(
    private val transport: AdapterTransport,
    override val sourceLabel: String,
    private val profile: AdapterProfile = AdapterProfile.PROVISIONAL,
) : DiagnosticReader {

    override val provenance: Provenance = when (transport.kind) {
        TransportKind.FAKE -> Provenance.SIMULATED
        TransportKind.REPLAY -> Provenance.REPLAY
    }

    private var assembler = ResponseAssembler()
    private val completed = ArrayDeque<String>()
    private val notes = mutableListOf<ReaderNote>()
    private var format: ResponseFormat? = null
    private var phase = "init"

    override fun takeNotes(): List<ReaderNote> = notes.toList().also { notes.clear() }

    /** Echo off, linefeeds off, headers on; each must answer OK. */
    override suspend fun open() {
        for (command in listOf(AdapterCommand.ECHO_OFF, AdapterCommand.LINEFEEDS_OFF, AdapterCommand.HEADERS_ON)) {
            val lines = adapterLines(exchange(RequestPolicy.adapter(command), profile.adapterCommandMillis), command)
            if (lines != listOf("OK")) {
                throw ReaderStoppedException(StopReason.ADAPTER_REJECTED, "${command.text} answered '${lines.joinToString(" ")}'")
            }
        }
    }

    /**
     * Sends 0100, then asks which protocol the adapter is using (ATDPN) and parses
     * the 0100 reply in that protocol's form. ATDPN reports the adapter's setting
     * only; it does not prove an ECU answered.
     */
    override suspend fun readSupportedPids(): SupportedPids {
        phase = "supported PIDs"
        val request = RequestPolicy.vehicle(VehicleRequest.SUPPORTED_PIDS_01_20)
        val raw = exchange(request, profile.vehicleRequestMillis)
        val dpn = adapterLines(exchange(RequestPolicy.adapter(AdapterCommand.DESCRIBE_PROTOCOL_NUMBER), profile.adapterCommandMillis), AdapterCommand.DESCRIBE_PROTOCOL_NUMBER)
        val number = dpn.singleOrNull()?.removePrefix("A")
        val form = when (number) {
            "1", "2", "3", "4", "5" -> ProtocolForm.NON_CAN
            "6", "8" -> ProtocolForm.CAN_11BIT
            else -> null
        }
        if (form == null) {
            val status = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            throw ReaderStoppedException(StopReason.NO_PROTOCOL, "ATDPN '${dpn.joinToString(" ")}' after 0100 '$status'")
        }
        format = ResponseFormat(headers = true, form = form)
        val result = decode(VehicleRequest.SUPPORTED_PIDS_01_20, raw)
        val supported = ReadPlan.supportedFrom(result)
        if (supported == SupportedPids.Unknown) notes += ReaderNote("supported_pids_unknown", describe(result))
        return supported
    }

    override suspend fun readDtcs(kind: DtcKind): DtcRead {
        val request = if (kind == DtcKind.STORED) VehicleRequest.STORED_DTCS else VehicleRequest.PENDING_DTCS
        phase = "${kind.name.lowercase()} codes"
        return when (val result = query(request)) {
            is ObdResult.Dtcs -> DtcRead.Read(result.codes)
            else -> DtcRead.NotRead(describe(result))
        }
    }

    override suspend fun readPid(pid: String): List<ReadingResult> {
        val request = VehicleRequest.entries.firstOrNull { it.text == "01$pid" }
            ?: return listOf(ReadingResult.Failed("PID $pid is not allowlisted"))
        phase = "PID $pid"
        return when (val result = query(request)) {
            is ObdResult.Reading -> result.perEcu.map { ReadingResult.Value(it.value, result.unit, it.ecu) }
            ObdResult.NoData -> listOf(ReadingResult.NoData)
            else -> listOf(ReadingResult.Failed(describe(result)))
        }
    }

    private suspend fun query(request: VehicleRequest): ObdResult =
        decode(request, exchange(RequestPolicy.vehicle(request), profile.vehicleRequestMillis))

    private fun decode(request: VehicleRequest, raw: String): ObdResult {
        val format = checkNotNull(format) { "protocol not determined" }
        val parsed = ElmResponseParser.parse(raw, RequestPolicy.vehicle(request), format)
        if (parsed is ParsedResponse.AdapterError && parsed.kind == AdapterErrorKind.LV_RESET) {
            // The adapter restarted and lost its settings; later replies would be misread.
            throw ReaderStoppedException(StopReason.ADAPTER_RESET, "LV RESET during $phase")
        }
        return ObdDecoder.decode(request, withoutPending(parsed, request), format.form)
    }

    /**
     * Drops "7F <service> 78" (response pending, p45) messages when a final reply
     * follows in the same response. A response with only pending messages stays
     * a negative result: the final reply never came.
     */
    private fun withoutPending(parsed: ParsedResponse, request: VehicleRequest): ParsedResponse {
        if (parsed !is ParsedResponse.Messages) return parsed
        val service = request.text.substring(0, 2).toInt(16)
        val rest = parsed.messages.filterNot { it.bytes == listOf(0x7F, service, 0x78) }
        return if (rest.isEmpty() || rest.size == parsed.messages.size) parsed else ParsedResponse.Messages(rest)
    }

    /** One request and its prompt-terminated reply, retried at most once after a passive resync. */
    private suspend fun exchange(request: Request, deadlineMillis: Long): String {
        awaitIdle()
        attempt(request, deadlineMillis)?.let { return it }
        // Sending anything now would interrupt the adapter (p9); wait for its prompt instead.
        if (awaitPrompt(profile.resyncMillis) == null) {
            throw ReaderStoppedException(StopReason.NO_RESPONSE, "no prompt after '${request.text}' ($phase)")
        }
        notes += ReaderNote("late_reply_discarded", request.text)
        notes += ReaderNote("retry", request.text)
        awaitIdle()
        return attempt(request, deadlineMillis)
            ?: throw ReaderStoppedException(StopReason.NO_RESPONSE, "no reply to '${request.text}' after one retry ($phase)")
    }

    private suspend fun attempt(request: Request, deadlineMillis: Long): String? {
        try {
            transport.write(request)
        } catch (e: TransportClosedException) {
            throw ReaderStoppedException(StopReason.DISCONNECTED, "before '${request.text}' ($phase)")
        }
        awaitPrompt(deadlineMillis)?.let { return it }
        if (!pendingLine.containsMatchIn(compact(assembler.pending()))) return null
        // Response pending: extend the deadline once, still bounded.
        notes += ReaderNote("response_pending_wait", request.text)
        return awaitPrompt(profile.pendingExtensionMillis)
    }

    /** The next completed response, or null if none completes within [millis]. */
    private suspend fun awaitPrompt(millis: Long): String? {
        completed.removeFirstOrNull()?.let { return it }
        return withTimeoutOrNull(millis) {
            while (completed.isEmpty()) completed += assembler.feed(readChunk())
            completed.removeFirst()
        }
    }

    /**
     * Before a write: the adapter must be at its prompt and quiet. Completed
     * responses nobody asked for are dropped; a partial one is waited out first,
     * so its tail cannot be mistaken for the next reply.
     */
    private suspend fun awaitIdle() {
        var stale = completed.isNotEmpty()
        completed.clear()
        var quietChunks = 0
        while (true) {
            if (assembler.pending().isNotBlank()) {
                awaitPrompt(profile.resyncMillis)
                    ?: throw ReaderStoppedException(StopReason.NO_RESPONSE, "adapter busy before $phase")
                stale = true
                completed.clear()
            }
            val chunk = withTimeoutOrNull(profile.quietMillis) { readChunk() } ?: break
            completed += assembler.feed(chunk)
            if (completed.isNotEmpty() || assembler.pending().isNotBlank()) stale = true
            completed.clear()
            if (++quietChunks > MAX_STALE_CHUNKS) throw ReaderStoppedException(StopReason.NO_RESPONSE, "adapter never went quiet before $phase")
        }
        if (stale) notes += ReaderNote("stale_reply_discarded", phase)
        assembler = ResponseAssembler()
    }

    private suspend fun readChunk(): ByteArray =
        transport.read() ?: throw ReaderStoppedException(StopReason.DISCONNECTED, "during $phase")

    /** Adapter-command reply lines, without an echo of the command (echo is on until ATE0 takes effect). */
    private fun adapterLines(text: String, command: AdapterCommand): List<String> {
        val lines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.firstOrNull()?.let(::compact) == command.text) lines.drop(1) else lines
    }

    private fun describe(result: ObdResult): String = when (result) {
        is ObdResult.AdapterError -> "adapter reported ${result.text}"
        is ObdResult.Malformed -> "malformed reply: ${result.reason}"
        is ObdResult.Negative ->
            if (result.responsePending) "response pending, no final reply" else "negative response %02X".format(result.code)
        ObdResult.NoData -> "NO DATA"
        is ObdResult.Incomplete -> "incomplete reply"
        is ObdResult.SupportedPids, is ObdResult.Reading, is ObdResult.Dtcs -> "unexpected reply type"
    }

    private fun compact(text: String): String = text.uppercase().filterNot { it == ' ' || it == '\t' }

    private companion object {
        val pendingLine = Regex("7F[0-9A-F]{2}78")
        const val MAX_STALE_CHUNKS = 64
    }
}
