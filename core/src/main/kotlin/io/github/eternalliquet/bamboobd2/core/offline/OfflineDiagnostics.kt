package io.github.eternalliquet.bamboobd2.core.offline

import io.github.eternalliquet.bamboobd2.core.protocol.ElmResponseParser
import io.github.eternalliquet.bamboobd2.core.protocol.ParsedResponse
import io.github.eternalliquet.bamboobd2.core.protocol.ProtocolForm
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ResponseAssembler
import io.github.eternalliquet.bamboobd2.core.protocol.ResponseFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A caller-selected interpretation, never inferred from response bytes. */
@Serializable
enum class DiagnosticProtocol { UNKNOWN, UDS, KWP }
@Serializable
enum class RecordedFraming { UNKNOWN, CAN_11BIT_HEADERS, NON_CAN_HEADERS, PAYLOAD_LINES }

/** Imported requests are inert data. This type provides no transport or command conversion. */
@Serializable
data class ManufacturerRecording(
    val script: ReplayScript,
    val protocol: DiagnosticProtocol = DiagnosticProtocol.UNKNOWN,
    val framing: RecordedFraming = RecordedFraming.UNKNOWN,
    val expectedResponder: String? = null,
    /** Local pseudonym, not a VIN. Equality is a caller assertion, not authenticated identity. */
    val vehicleAlias: String? = null,
    val vehicleApplicability: String? = null,
    val moduleIdentity: String? = null,
) {
    fun validate() {
        require(script.id.isNotBlank() && script.source.isNotBlank()) { "Recording needs an id and source" }
        require(script.provenance in setOf("SYNTHETIC", "RAW_CAPTURE", "SANITIZED_CAPTURE")) { "Unknown recording provenance" }
        if (script.provenance == "SANITIZED_CAPTURE") require(
            script.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true && !script.evidenceLog.isNullOrBlank()
        ) { "Sanitized recording needs original SHA-256 and evidence-log reference" }
        require(script.steps.size <= 256) { "At most 256 recorded requests" }
        require(script.steps.sumOf { it.chunks.size.toLong() } <= 4096) { "At most 4096 recorded chunks" }
        require(script.steps.sumOf { s -> s.request.length.toLong() + s.chunks.sumOf { it.text.length.toLong() } } <= MAX_JSON_CHARS) {
            "Recording exceeds byte budget"
        }
        script.steps.forEach { step ->
            require(step.request.length in 1..128 && step.request.all { it.code in 0..255 }) { "Invalid recorded request size/encoding" }
            require(step.chunks.all { it.atMillis >= 0 && it.text.all { c -> c.code in 0..255 } }) { "Invalid chunk time or byte encoding" }
            require(step.chunks.zipWithNext().all { (a,b) -> a.atMillis <= b.atMillis }) { "Chunk times must retain acquisition order" }
            require(step.disconnectAfterMillis == null || step.disconnectAfterMillis >= 0) { "Invalid disconnect time" }
        }
        require(expectedResponder == null || Regex("(?:[0-7][0-9A-F]{2}|[0-9A-F]{2})").matches(expectedResponder)) {
            "Responder must use the declared framing's uppercase identifier"
        }
    }

    companion object {
        const val MAX_JSON_CHARS = 2_000_000
        private val json = Json { ignoreUnknownKeys = false }
        fun parse(text: String): ManufacturerRecording {
            require(text.length <= MAX_JSON_CHARS) { "Recording JSON exceeds size limit" }
            val result = try { json.decodeFromString(serializer(), text) }
            catch (_: SerializationException) { throw IllegalArgumentException("Invalid manufacturer recording JSON") }
            result.validate()
            return result
        }
        /** Uses only the existing data schema, deliberately never ReplayScript.parse or replay execution. */
        fun fromCapture(text: String, protocol: DiagnosticProtocol, framing: RecordedFraming): ManufacturerRecording {
            require(text.length <= MAX_JSON_CHARS) { "Capture JSON exceeds size limit" }
            val script = try { json.decodeFromString(ReplayScript.serializer(), text) }
            catch (_: SerializationException) { throw IllegalArgumentException("Invalid capture JSON") }
            return ManufacturerRecording(script, protocol, framing).also { it.validate() }
        }
    }
}

enum class ExchangeStatus { COMPLETE, INCOMPLETE, MALFORMED, NEGATIVE, PENDING, MISMATCH, UNKNOWN, NO_DATA, ADAPTER }
data class RecordedResponse(
    val status: ExchangeStatus,
    val responder: String?,
    val bytes: List<Int>,
    val completedAtMillis: Long?,
    val explanation: String,
)
data class ExchangeAnalysis(val stepIndex: Int, val request: String, val responses: List<RecordedResponse>) {
    /** Exactly one matched positive terminal response and no contradictory evidence. */
    val usable: Boolean get() = responses.count { it.status == ExchangeStatus.COMPLETE } == 1 &&
        responses.all { it.status in setOf(ExchangeStatus.COMPLETE, ExchangeStatus.PENDING) } &&
        responses.lastOrNull()?.status == ExchangeStatus.COMPLETE &&
        responses.map { it.responder }.distinct().size == 1
}
data class AnalysisReport(val recording: ManufacturerRecording, val exchanges: List<ExchangeAnalysis>) {
    /** Private local report. Raw requests/replies and identifying metadata are deliberately omitted. */
    fun render(): String = buildString {
        appendLine("Offline diagnostic evidence (${recording.script.provenance})")
        appendLine("Selected protocol: ${recording.protocol}; this selection is not protocol verification.")
        appendLine("Transport framing: ${recording.framing}. Ordering is recorded order; chunk times are relative to each request.")
        appendLine("Correlation is by recorded request group plus selected protocol echo, not proof against delayed identical replies.")
        exchanges.forEach { exchange ->
            appendLine("Request #${exchange.stepIndex + 1}: ${if (exchange.usable) "one correlated positive reply" else "not usable for field decoding"}")
            exchange.responses.forEach { response ->
                appendLine("  ${response.status}; responder ${response.responder ?: "unknown"}; " +
                    "completed ${response.completedAtMillis?.let { "${it}ms" } ?: "unknown"}; ${response.explanation}")
            }
        }
        appendLine("No maintenance field definitions supplied by this analyzer. No reset or vehicle compatibility verified.")
        appendLine("Raw byte data remains in the local recording; this report does not automatically sanitize that file.")
    }
}

object OfflineDiagnostics {
    fun analyze(recording: ManufacturerRecording): AnalysisReport {
        recording.validate()
        val format = when (recording.framing) {
            RecordedFraming.UNKNOWN -> null
            RecordedFraming.CAN_11BIT_HEADERS -> ResponseFormat(true, ProtocolForm.CAN_11BIT)
            RecordedFraming.NON_CAN_HEADERS -> ResponseFormat(true, ProtocolForm.NON_CAN)
            RecordedFraming.PAYLOAD_LINES -> ResponseFormat(false, ProtocolForm.CAN_11BIT)
        }
        val exchanges = recording.script.steps.mapIndexed { index, step ->
            val assembler = ResponseAssembler()
            val responses = mutableListOf<RecordedResponse>()
            val request = hexBytes(step.request)
            step.chunks.forEach { chunk ->
                assembler.feed(chunk.text.toByteArray(Charsets.ISO_8859_1)).forEach { text ->
                    if (step.request.trim().startsWith("AT", true)) {
                        responses += RecordedResponse(ExchangeStatus.ADAPTER, null, emptyList(), chunk.atMillis,
                            "Adapter text retained in recording; not ECU identity or service evidence")
                    } else if (request == null || request.isEmpty()) {
                        responses += RecordedResponse(ExchangeStatus.MALFORMED, null, emptyList(), chunk.atMillis, "Recorded request is not diagnostic hex bytes")
                    } else if (format == null) {
                        responses += RecordedResponse(ExchangeStatus.UNKNOWN, null, emptyList(), chunk.atMillis,
                            "Framing was not selected; raw bytes retained without guessing transport")
                    } else {
                        when (val parsed = ElmResponseParser.parseRecorded(text, step.request, format)) {
                            is ParsedResponse.Messages -> parsed.messages.forEach { message ->
                                responses += classify(recording, request, message.ecu, message.bytes, chunk.atMillis)
                            }
                            ParsedResponse.NoData -> responses += RecordedResponse(ExchangeStatus.NO_DATA, null, emptyList(), chunk.atMillis, "No data; cause unknown")
                            is ParsedResponse.AdapterError -> responses += RecordedResponse(ExchangeStatus.MALFORMED, null, emptyList(), chunk.atMillis, "Adapter status ${parsed.kind}; no successful ECU read")
                            is ParsedResponse.Malformed -> responses += RecordedResponse(
                                if (parsed.reason in setOf("multi-frame reply truncated", "multi-frame reply shorter than its byte count")) ExchangeStatus.INCOMPLETE else ExchangeStatus.MALFORMED,
                                null, emptyList(), chunk.atMillis, parsed.reason)
                        }
                    }
                }
            }
            if (assembler.pending().isNotBlank() || responses.isEmpty()) responses += RecordedResponse(
                ExchangeStatus.INCOMPLETE, null, emptyList(), null, "No complete prompt-terminated response; partial raw bytes retained")
            if (!recording.script.connected && step.chunks.isNotEmpty()) responses += RecordedResponse(
                ExchangeStatus.MALFORMED, null, emptyList(), null, "Response bytes conflict with initially disconnected recording")
            if (step.disconnectAfterMillis != null && step.chunks.any { it.atMillis >= step.disconnectAfterMillis }) responses +=
                RecordedResponse(ExchangeStatus.MALFORMED, null, emptyList(), null, "Recorded chunks conflict with recorded disconnect time")
            ExchangeAnalysis(index, step.request, responses.toList())
        }
        return AnalysisReport(recording, exchanges)
    }

    private fun classify(recording: ManufacturerRecording, request: List<Int>, responder: String?, bytes: List<Int>, at: Long): RecordedResponse {
        fun result(status: ExchangeStatus, reason: String) = RecordedResponse(status, responder, bytes, at, reason)
        if (recording.expectedResponder != null && responder != recording.expectedResponder)
            return result(ExchangeStatus.MISMATCH, "Responder does not match the selected route")
        if (recording.protocol == DiagnosticProtocol.UNKNOWN)
            return result(ExchangeStatus.UNKNOWN, "Complete framing; diagnostic meaning unknown because no protocol was selected")
        if (bytes.firstOrNull() == 0x7F) {
            if (bytes.size != 3) return result(ExchangeStatus.MALFORMED, "Negative-response layout must be exactly three bytes")
            if (bytes[1] != request[0]) return result(ExchangeStatus.MISMATCH, "Negative response echoes a different request service")
            return if (bytes[2] == 0x78) result(ExchangeStatus.PENDING, "Response pending; not successful completion")
            else result(ExchangeStatus.NEGATIVE, "Negative response code ${bytes[2].toString(16).uppercase().padStart(2, '0')}")
        }
        val echoed = when (recording.protocol) {
            DiagnosticProtocol.UDS -> if (request[0] == 0x22 && request.size == 3) 2 else null
            DiagnosticProtocol.KWP -> when {
                request[0] == 0x22 && request.size == 3 -> 2
                request[0] in setOf(0x21, 0x1A) && request.size == 2 -> 1
                else -> null
            }
            DiagnosticProtocol.UNKNOWN -> null
        } ?: return result(ExchangeStatus.UNKNOWN, "Selected protocol service/layout is not modeled; raw reply retained")
        if (bytes.size < echoed + 2) return result(ExchangeStatus.MALFORMED, "Positive response lacks its echoed identifier or data")
        if (bytes[0] != request[0] + 0x40 || bytes.subList(1, 1 + echoed) != request.drop(1))
            return result(ExchangeStatus.MISMATCH, "Positive service or echoed identifier differs from request")
        return result(ExchangeStatus.COMPLETE, "Positive reply matches selected protocol layout; field meaning and module applicability remain unverified")
    }
}

internal fun hexBytes(text: String): List<Int>? {
    val compact = text.filterNot { it == ' ' || it == '\t' }
    return if (compact.matches(Regex("(?:[a-fA-F0-9]{2})+"))) compact.chunked(2).map { it.toInt(16) } else null
}
