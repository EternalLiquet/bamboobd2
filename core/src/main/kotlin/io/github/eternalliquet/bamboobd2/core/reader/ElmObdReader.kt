package io.github.eternalliquet.bamboobd2.core.reader

import io.github.eternalliquet.bamboobd2.core.live.RequestGate
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.PageEvidence
import io.github.eternalliquet.bamboobd2.core.model.PageStatus
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterCommand
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterErrorKind
import io.github.eternalliquet.bamboobd2.core.protocol.AdapterTransport
import io.github.eternalliquet.bamboobd2.core.protocol.EcuMessage
import io.github.eternalliquet.bamboobd2.core.protocol.ElmResponseParser
import io.github.eternalliquet.bamboobd2.core.protocol.ObdDecoder
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
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
import kotlin.time.TimeSource

/**
 * Host-side deadlines. **Provisional and unverified**: these numbers were chosen
 * for offline replay and have not been measured against any adapter. Authorized
 * L1 testing must measure real timing before they mean anything.
 */
data class AdapterProfile(
    /** From writing an adapter command to its prompt. */
    val adapterCommandMillis: Long,
    /** From writing a vehicle request to its prompt. */
    val vehicleRequestMillis: Long,
    /**
     * For the first vehicle request after `ATSP0`: the adapter searches the protocols
     * before answering, which can take several seconds per protocol tried.
     */
    val protocolSearchMillis: Long,
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
            protocolSearchMillis = 30_000,
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
    private val discoveryClockMillis: (() -> Long)? = null,
    private val requestGate: RequestGate? = null,
) : DiagnosticReader {

    override val provenance: Provenance = when (transport.kind) {
        TransportKind.FAKE -> Provenance.SIMULATED
        TransportKind.REPLAY -> Provenance.REPLAY
        TransportKind.LIVE -> Provenance.LIVE
    }

    private var assembler = ResponseAssembler()
    private val completed = ArrayDeque<String>()
    private val notes = mutableListOf<ReaderNote>()
    private var format: ResponseFormat? = null
    private var phase = "init"
    private var lastMalformedEcus: List<String?> = emptyList()
    private data class ResponderIssue(val status: PageStatus, val reason: String)
    private var lastResponderIssues: Map<String?, ResponderIssue> = emptyMap()
    private var discoveryStop: ReaderStoppedException? = null
    private var discoveryStart: TimeSource.Monotonic.ValueTimeMark? = null
    private var discoveryStartedMillis: Long? = null

    override fun takeNotes(): List<ReaderNote> = notes.toList().also { notes.clear() }

    /**
     * Echo off, linefeeds off, headers on, automatic protocol; each must answer OK.
     * `ATSP0` replaces any fixed protocol an earlier app stored, which otherwise makes
     * a car on another protocol fail with "BUS INIT: ...ERROR" (ELM327DSJ p26).
     */
    override suspend fun open() {
        discoveryStart = TimeSource.Monotonic.markNow()
        discoveryStartedMillis = discoveryClockMillis?.invoke()
        try {
        for (command in listOf(AdapterCommand.ECHO_OFF, AdapterCommand.LINEFEEDS_OFF, AdapterCommand.HEADERS_ON, AdapterCommand.AUTO_PROTOCOL)) {
            val lines = adapterLines(discoveryExchange(RequestPolicy.adapter(command), profile.adapterCommandMillis), command)
            if (lines != listOf("OK")) {
                throw ReaderStoppedException(StopReason.ADAPTER_REJECTED, "${command.text} answered '${lines.joinToString(" ")}'")
            }
        }
        } catch (e: DiscoveryDeadlineException) { throw ReaderStoppedException(StopReason.NO_RESPONSE, "discovery deadline during adapter setup") }
    }

    /**
     * Sends 0100, then asks which protocol the adapter is using (ATDPN) and parses
     * the 0100 reply in that protocol's form. ATDPN reports the adapter's setting
     * only; it does not prove an ECU answered.
     */
    override suspend fun readSupportedPids(): SupportedPids {
        phase = "supported PIDs"
        val first = VehicleRequest.SUPPORTED_PIDS_01_20
        val raw = try { discoveryExchange(RequestPolicy.vehicle(first), profile.protocolSearchMillis) }
            catch (e: DiscoveryDeadlineException) { throw ReaderStoppedException(StopReason.NO_RESPONSE, "discovery deadline at 0100") }
        val dpn = try { adapterLines(discoveryExchange(RequestPolicy.adapter(AdapterCommand.DESCRIBE_PROTOCOL_NUMBER), profile.adapterCommandMillis), AdapterCommand.DESCRIBE_PROTOCOL_NUMBER) }
            catch (e: DiscoveryDeadlineException) { throw ReaderStoppedException(StopReason.NO_RESPONSE, "discovery deadline at ATDPN") }
        dpn.singleOrNull()?.let { notes += ReaderNote(PROTOCOL_NOTE, describeProtocol(it)) }
        val form = when (dpn.singleOrNull()?.removePrefix("A")) {
            "1", "2", "3", "4", "5" -> ProtocolForm.NON_CAN
            "6", "8" -> ProtocolForm.CAN_11BIT
            else -> null
        } ?: throw ReaderStoppedException(StopReason.NO_PROTOCOL, "ATDPN '${dpn.joinToString(" ")}' after 0100 '${raw.lines().joinToString(" ")}'")
        format = ResponseFormat(headers = true, form = form)
        val pages = mutableListOf<PageEvidence>()
        var expected: Set<String?>? = null
        fun retainBeforeStop(pid: String, stop: ReaderStoppedException) {
            expected.orEmpty().forEach { pages += PageEvidence(it, pid, PageStatus.MISSING, reason = "discovery stopped") }
            notes += ReaderNote("supported_pids_incomplete", "discovery stopped at $pid")
            discoveryStop = stop
        }
        for ((index, pid) in Mode01Catalog.pageIds.withIndex()) {
            val request = VehicleRequest.entries.single { it.text == "01$pid" }
            phase = "support page $pid"
            val reply = if (index == 0) raw else try {
                discoveryExchange(RequestPolicy.vehicle(request), profile.vehicleRequestMillis)
            } catch (e: Exception) {
                if (e !is DiscoveryDeadlineException && e !is ReaderStoppedException) throw e
                retainBeforeStop(pid, if (e is ReaderStoppedException) e
                    else ReaderStoppedException(StopReason.NO_RESPONSE, "discovery deadline at $pid"))
                break
            }
            val (result, unresolved) = try { decode(request, reply) }
                catch (e: ReaderStoppedException) {
                    if (index == 0) throw e
                    retainBeforeStop(pid, e)
                    break
                }
            val valid = (result as? ObdResult.SupportedPids)?.perEcu.orEmpty()
            val validIds = valid.map { it.ecu }.toSet()
            // The first page establishes identities. Later pages are accepted only
            // for responders whose own preceding valid page advertises this page.
            valid.forEach { entry ->
                pages += if (expected == null || entry.ecu in expected.orEmpty())
                    PageEvidence(entry.ecu, pid, PageStatus.VALID,
                        entry.value.filter { it != pid.toInt(16) + 32 }.sorted().map { "%02X".format(it) },
                        continues = entry.ecu in (result as ObdResult.SupportedPids).continuationByEcu)
                else PageEvidence(entry.ecu, pid, PageStatus.MALFORMED, reason = "unsolicited page without own continuation")
            }
            val issues = lastResponderIssues + lastMalformedEcus.associateWith { ResponderIssue(PageStatus.MALFORMED, "malformed frame") }
            issues.filterKeys { it !in validIds && it !in expected.orEmpty() && it !in unresolved }.forEach { (id, issue) ->
                pages += PageEvidence(id, pid, issue.status, reason = issue.reason)
            }
            unresolved.filterNot { it in validIds }.forEach { id ->
                pages += PageEvidence(id, pid, PageStatus.PENDING, reason = "response pending, no final reply")
            }
            expected.orEmpty().filterNot { it in validIds || it in unresolved }.forEach { id ->
                val status = issues[id]?.status ?: when {
                    result == ObdResult.NoData -> PageStatus.NO_DATA
                    result is ObdResult.Malformed -> PageStatus.MALFORMED
                    else -> PageStatus.MISSING
                }
                pages += PageEvidence(id, pid, status, reason = issues[id]?.reason ?: describe(result))
            }
            if (index == 0 && valid.isEmpty()) {
                notes += ReaderNote(SUPPORTED_PIDS_UNKNOWN, describe(result))
                return SupportedPids.Unknown
            }
            if (index == Mode01Catalog.pageIds.lastIndex) break
            expected = pages.filter { it.pageBase == pid && it.status == PageStatus.VALID && it.continues }
                .map { it.ecu }.toSet()
            if (expected.isNullOrEmpty()) break
        }
        // A map marker is metadata, not an observed scalar PID.
        val union = pages.filter { it.status == PageStatus.VALID }.flatMap { it.supportedIds }.distinct().sorted()
        val complete = pages.none { it.status != PageStatus.VALID } &&
            pages.none { it.pageBase == "E0" && it.continues }
        if (!complete) notes += ReaderNote("supported_pids_incomplete", pages.filter { it.status != PageStatus.VALID }.joinToString { "${it.ecu ?: "responder"} ${it.pageBase}: ${it.status}" })
        return SupportedPids.Known(union, complete, pages)
    }

    private class DiscoveryDeadlineException : RuntimeException()

    private suspend fun discoveryExchange(request: Request, normalMillis: Long): String {
        val elapsed = if (discoveryClockMillis != null) discoveryClockMillis.invoke() - checkNotNull(discoveryStartedMillis)
            else discoveryStart?.elapsedNow()?.inWholeMilliseconds ?: 0
        val remaining = DISCOVERY_MILLIS - elapsed
        if (remaining <= 0) throw DiscoveryDeadlineException()
        return withTimeoutOrNull(remaining) { exchange(request, minOf(normalMillis, remaining)) }
            ?: throw DiscoveryDeadlineException()
    }

    override suspend fun readDtcs(kind: DtcKind): DtcRead {
        discoveryStop?.let { throw it }
        val request = if (kind == DtcKind.STORED) VehicleRequest.STORED_DTCS else VehicleRequest.PENDING_DTCS
        phase = "${kind.name.lowercase()} codes"
        val (result, unresolved) = query(request)
        return when {
            result is ObdResult.Dtcs && unresolved.isEmpty() -> DtcRead.Read(result.codes)
            // Codes from responders that answered are kept; the list is not complete.
            result is ObdResult.Dtcs -> DtcRead.Incomplete(result.codes, pendingReason(unresolved))
            else -> DtcRead.NotRead(describe(result))
        }
    }

    override suspend fun readPid(pid: String): List<ReadingResult> = readPidResults(pid, observations = false)

    override suspend fun readPidObservations(pid: String): List<ReadingResult> = readPidResults(pid, observations = true)

    private suspend fun readPidResults(pid: String, observations: Boolean): List<ReadingResult> {
        discoveryStop?.let { throw it }
        val request = VehicleRequest.entries.firstOrNull { it.text == "01$pid" }
            ?: return listOf(ReadingResult.Failed("PID $pid is not allowlisted"))
        phase = "PID $pid"
        val (result, unresolved) = query(request)
        if (observations) {
            // These typed per-responder issues survive even when the aggregate
            // decoder has no valid value or exposes only the first negative.
            val issues = linkedMapOf<String?, String>()
            lastResponderIssues.forEach { (ecu, issue) -> issues[ecu] = issue.reason }
            lastMalformedEcus.forEach { issues[it] = "malformed frame" }
            unresolved.forEach { issues[it] = "response pending, no final reply" }
            val reading = result as? ObdResult.Reading
            val values = reading?.perEcu.orEmpty().map {
                ReadingResult.Value(it.value, checkNotNull(reading).unit, it.ecu)
            }
            if (values.isNotEmpty() || issues.isNotEmpty())
                return values + issues.map { (ecu, reason) -> ReadingResult.Failed(reason, ecu) }
        }
        return when (result) {
            is ObdResult.Reading -> result.perEcu.map { ReadingResult.Value(it.value, result.unit, it.ecu) } +
                (result.failures + lastMalformedEcus.map { "${it ?: "responder"}: malformed frame" }).map { ReadingResult.Failed(it) } +
                unresolved.map { ReadingResult.Failed("${it ?: "responder"}: response pending, no final reply") }
            ObdResult.NoData -> listOf(ReadingResult.NoData)
            else -> listOf(ReadingResult.Failed(describe(result)))
        }
    }

    private suspend fun query(request: VehicleRequest): Pair<ObdResult, List<String?>> =
        decode(request, exchange(RequestPolicy.vehicle(request), profile.vehicleRequestMillis))

    /** The decoded final replies, and the responders whose pending reply was never resolved. */
    private fun decode(request: VehicleRequest, raw: String): Pair<ObdResult, List<String?>> {
        val format = checkNotNull(format) { "protocol not determined" }
        lastMalformedEcus = emptyList()
        lastResponderIssues = emptyMap()
        val initial = ElmResponseParser.parse(raw, RequestPolicy.vehicle(request), format)
        // Per-responder salvage belongs to Mode 01. DTC lists keep their
        // existing whole-response validation so damaged replies cannot look complete.
        val parsed = if (request.text.startsWith("01") && initial is ParsedResponse.Malformed)
            salvageMessages(raw, request, format) ?: initial else initial
        if (parsed is ParsedResponse.AdapterError && parsed.kind == AdapterErrorKind.LV_RESET) {
            // The adapter restarted and lost its settings; later replies would be misread.
            throw ReaderStoppedException(StopReason.ADAPTER_RESET, "LV RESET during $phase")
        }
        val (finals, unresolved) = splitPending(parsed, request)
        // Aggregate results may expose only one negative, or omit responder IDs
        // when every reply is malformed. Classify each ECU with the same decoder
        // so stored page statuses never depend on parsing diagnostic prose.
        if (request.text.startsWith("01") && finals is ParsedResponse.Messages) {
            lastResponderIssues = finals.messages.groupBy { it.ecu }.mapNotNull { (ecu, messages) ->
                val own = ObdDecoder.decode(request, ParsedResponse.Messages(messages), format.form)
                val status = when (own) {
                    is ObdResult.Negative -> if (own.responsePending) PageStatus.PENDING else PageStatus.NEGATIVE
                    is ObdResult.Malformed -> if (messages.size > 1) PageStatus.CONFLICT else PageStatus.MALFORMED
                    else -> null
                }
                status?.let { ecu to ResponderIssue(it, describe(own)) }
            }.toMap()
        }
        return ObdDecoder.decode(request, finals, format.form) to unresolved
    }

    /** A broken line with an identifiable ECU must not discard another ECU's single-frame reply. */
    private fun salvageMessages(raw: String, request: VehicleRequest, format: ResponseFormat): ParsedResponse.Messages? {
        val messages = mutableListOf<EcuMessage>()
        val bad = mutableListOf<String?>()
        for (line in raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (line == request.text || line == "SEARCHING...") continue
            when (val parsed = ElmResponseParser.parse(line, RequestPolicy.vehicle(request), format)) {
                is ParsedResponse.Messages -> messages += parsed.messages
                // NO DATA describes the whole exchange and contradicts payload
                // lines. It cannot be isolated as one damaged responder.
                ParsedResponse.NoData -> return null
                is ParsedResponse.Malformed -> {
                    val hex = compact(line)
                    bad += if (format.form == ProtocolForm.CAN_11BIT) hex.take(3).takeIf { it.matches(Regex("[0-9A-F]{3}")) }
                        else hex.drop(4).take(2).takeIf { it.matches(Regex("[0-9A-F]{2}")) }
                }
                else -> Unit
            }
        }
        lastMalformedEcus = bad
        // An intact line does not resolve a damaged duplicate from that ECU.
        return messages.filterNot { it.ecu in bad }.takeIf { it.isNotEmpty() }?.let { ParsedResponse.Messages(it) }
    }

    /**
     * Separates "7F <service> 78" (response pending, p45) messages from final
     * replies. A pending message is resolved only by a later final reply from
     * the same responder; another responder's reply, or one that came before it,
     * does not resolve it. A response with only pending messages is returned
     * unchanged, so it decodes as a negative result: no final reply came.
     */
    private fun splitPending(parsed: ParsedResponse, request: VehicleRequest): Pair<ParsedResponse, List<String?>> {
        if (parsed !is ParsedResponse.Messages) return parsed to emptyList()
        val service = request.text.substring(0, 2).toInt(16)
        val pending = LinkedHashSet<String?>()
        val finals = mutableListOf<EcuMessage>()
        for (m in parsed.messages) {
            if (m.bytes == listOf(0x7F, service, 0x78)) {
                pending += m.ecu
            } else {
                pending -= m.ecu
                finals += m
            }
        }
        // A Mode 01 value/map preceding an unresolved pending response is not a
        // final answer. DTCs keep their earlier codes with an incomplete list.
        val safeFinals = if (request.text.startsWith("01")) finals.filterNot { it.ecu in pending } else finals
        return if (safeFinals.isEmpty()) ParsedResponse.Messages(parsed.messages.filter { it.bytes == listOf(0x7F, service, 0x78) }) to pending.toList()
            else ParsedResponse.Messages(safeFinals) to pending.toList()
    }

    private fun pendingReason(unresolved: List<String?>): String =
        "response pending, no final reply from ${unresolved.joinToString { it ?: "responder" }}"

    /** One request and its prompt-terminated reply, retried at most once after a passive resync. */
    private suspend fun exchange(request: Request, deadlineMillis: Long): String {
        attempt(request, deadlineMillis, false)?.let { return it }
        // Sending anything now would interrupt the adapter (p9); wait for its prompt instead.
        if (awaitPrompt(profile.resyncMillis) == null) {
            throw ReaderStoppedException(StopReason.NO_RESPONSE, "no prompt after '${request.text}' ($phase)")
        }
        notes += ReaderNote("late_reply_discarded", request.text)
        notes += ReaderNote("retry", request.text)
        return attempt(request, deadlineMillis, true)
            ?: throw ReaderStoppedException(StopReason.NO_RESPONSE, "no reply to '${request.text}' after one retry ($phase)")
    }

    private suspend fun attempt(request: Request, deadlineMillis: Long, retry: Boolean): String? {
        if (requestGate == null) awaitIdle() else requestGate.beforeWrite(retry, ::awaitIdle)
        var written = false
        try {
            transport.write(request)
            written = true
            requestGate?.wrote(retry)
        } catch (e: TransportClosedException) {
            throw ReaderStoppedException(StopReason.DISCONNECTED, "before '${request.text}' ($phase)")
        } finally {
            if (!written) requestGate?.rejectedWrite()
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

    companion object {
        /** Session event kind holding the protocol `ATDPN` reported. */
        const val PROTOCOL_NOTE = "protocol"

        /** Session event kind: the supported-PID map could not be read; detail is the reason. */
        const val SUPPORTED_PIDS_UNKNOWN = "supported_pids_unknown"

        /** "A6" becomes "6: ISO 15765-4 CAN (11 bit ID, 500 kbaud), found by automatic search" (pp25-26). */
        fun describeProtocol(dpn: String): String {
            val auto = dpn.startsWith("A") && dpn.length > 1
            val number = if (auto) dpn.substring(1) else dpn
            if (number == "0") return if (auto) "0: automatic search found no protocol" else "0: automatic, not yet searched"
            val name = PROTOCOL_NAMES[number] ?: "unknown protocol"
            return "$number: $name" + if (auto) ", found by automatic search" else ", fixed setting"
        }

        private val PROTOCOL_NAMES = mapOf(
            "1" to "SAE J1850 PWM",
            "2" to "SAE J1850 VPW",
            "3" to "ISO 9141-2 (5 baud init)",
            "4" to "ISO 14230-4 KWP (5 baud init)",
            "5" to "ISO 14230-4 KWP (fast init)",
            "6" to "ISO 15765-4 CAN (11 bit ID, 500 kbaud)",
            "7" to "ISO 15765-4 CAN (29 bit ID, 500 kbaud)",
            "8" to "ISO 15765-4 CAN (11 bit ID, 250 kbaud)",
            "9" to "ISO 15765-4 CAN (29 bit ID, 250 kbaud)",
            "A" to "SAE J1939 CAN",
            "B" to "USER1 CAN",
            "C" to "USER2 CAN",
        )

        private val pendingLine = Regex("7F[0-9A-F]{2}78")
        private const val MAX_STALE_CHUNKS = 64
        private const val DISCOVERY_MILLIS = 180_000L
    }
}
