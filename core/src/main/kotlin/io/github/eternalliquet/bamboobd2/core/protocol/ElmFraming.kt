package io.github.eternalliquet.bamboobd2.core.protocol

/*
 * ELM327-style response framing. Rules and page numbers refer to the ELM327
 * data sheet, revision ELM327DSJ (see docs/PROTOCOL.md for the source record).
 */

/** How the adapter is formatting replies. Both values must be known; they are not guessed from the bytes. */
data class ResponseFormat(val headers: Boolean, val form: ProtocolForm)

/** Response shapes handled in milestone 1. 29-bit CAN and other forms are rejected as malformed. */
enum class ProtocolForm {
    /** ISO 15765-4 with 11-bit identifiers (headers show as e.g. "7E8"). */
    CAN_11BIT,

    /** J1850, ISO 9141-2 or ISO 14230-4 (headers show as three bytes, e.g. "48 6B 10"). */
    NON_CAN,
}

/** One assembled message and the responder it came from, when headers make that known. */
data class EcuMessage(val ecu: String?, val bytes: List<Int>)

/** Adapter status and error texts (ELM327DSJ pp87-89). */
enum class AdapterErrorKind {
    UNKNOWN_COMMAND, UNABLE_TO_CONNECT, CAN_ERROR, BUFFER_FULL, STOPPED, BUS_ERROR, BUS_BUSY,
    DATA_ERROR, FB_ERROR, RX_ERROR, LV_RESET, ACT_ALERT, LP_ALERT, INTERNAL_ERROR, BUS_INIT_ERROR,
}

sealed interface ParsedResponse {
    data class Messages(val messages: List<EcuMessage>) : ParsedResponse
    data object NoData : ParsedResponse
    data class AdapterError(val kind: AdapterErrorKind, val text: String) : ParsedResponse
    data class Malformed(val reason: String) : ParsedResponse
}

/**
 * Collects adapter output until the '>' prompt (ELM327DSJ p9). NUL bytes are
 * dropped, as the data sheet advises (p9). Text after a prompt is kept for the
 * next response.
 */
class ResponseAssembler {
    private val buffer = StringBuilder()

    /** Feeds one chunk; returns each response completed by a prompt in it (usually zero or one). */
    fun feed(chunk: ByteArray): List<String> {
        val done = mutableListOf<String>()
        for (b in chunk) {
            val c = (b.toInt() and 0xFF).toChar()
            when (c) {
                '\u0000' -> Unit
                '>' -> {
                    done += buffer.toString()
                    buffer.setLength(0)
                }
                else -> buffer.append(c)
            }
        }
        return done
    }

    /** Text received since the last prompt. */
    fun pending(): String = buffer.toString()
}

/** Turns one prompt-terminated response into messages or a typed status. */
object ElmResponseParser {

    private val statusTexts = mapOf(
        "?" to AdapterErrorKind.UNKNOWN_COMMAND,
        "UNABLE TO CONNECT" to AdapterErrorKind.UNABLE_TO_CONNECT,
        "CAN ERROR" to AdapterErrorKind.CAN_ERROR,
        "BUFFER FULL" to AdapterErrorKind.BUFFER_FULL,
        "STOPPED" to AdapterErrorKind.STOPPED,
        "BUS ERROR" to AdapterErrorKind.BUS_ERROR,
        "BUS BUSY" to AdapterErrorKind.BUS_BUSY,
        "DATA ERROR" to AdapterErrorKind.DATA_ERROR,
        "<DATA ERROR" to AdapterErrorKind.DATA_ERROR,
        "FB ERROR" to AdapterErrorKind.FB_ERROR,
        "<RX ERROR" to AdapterErrorKind.RX_ERROR,
        "LV RESET" to AdapterErrorKind.LV_RESET,
        "ACT ALERT" to AdapterErrorKind.ACT_ALERT,
        "!ACT ALERT" to AdapterErrorKind.ACT_ALERT,
        "LP ALERT" to AdapterErrorKind.LP_ALERT,
        "!LP ALERT" to AdapterErrorKind.LP_ALERT,
    )
    private val internalError = Regex("ERR[0-9A-F]{2}")
    private val hexBytes = Regex("(?:[0-9A-F]{2})+")
    private val lengthLine = Regex("[0-9A-F]{3}")
    private val numberedLine = Regex("([0-9A-F]):(.*)")

    fun parse(text: String, request: Request, format: ResponseFormat): ParsedResponse {
        val lines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        // Echo is on by default (AT E1); the first line may repeat the command.
        if (lines.isNotEmpty() && compact(lines.first()) == request.text.uppercase()) lines.removeAt(0)

        val data = mutableListOf<String>()
        var noData = false
        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper == "SEARCHING..." -> Unit
                upper.startsWith("BUS INIT") ->
                    if ("ERROR" in upper) return ParsedResponse.AdapterError(AdapterErrorKind.BUS_INIT_ERROR, line)
                upper == "NO DATA" -> noData = true
                upper in statusTexts -> return ParsedResponse.AdapterError(statusTexts.getValue(upper), line)
                internalError.matches(upper) -> return ParsedResponse.AdapterError(AdapterErrorKind.INTERNAL_ERROR, line)
                else -> data += compact(upper)
            }
        }
        if (noData) {
            return if (data.isEmpty()) ParsedResponse.NoData else ParsedResponse.Malformed("NO DATA mixed with data lines")
        }
        if (data.isEmpty()) return ParsedResponse.Malformed("empty response")
        return if (format.headers) {
            when (format.form) {
                ProtocolForm.CAN_11BIT -> parseCanWithHeaders(data)
                ProtocolForm.NON_CAN -> parseNonCanWithHeaders(data)
            }
        } else {
            parseWithoutHeaders(data)
        }
    }

    private fun compact(line: String): String = line.filterNot { it == ' ' || it == '\t' }

    private fun bytesOf(hex: String): List<Int>? =
        if (hexBytes.matches(hex)) hex.chunked(2).map { it.toInt(16) } else null

    /**
     * Headers off (AT H0, the default). Each line is one message, except CAN
     * multi-frame replies, which start with a three-digit byte count followed by
     * numbered lines "0:", "1:", ... (pp42-45). Responders are not identifiable.
     */
    private fun parseWithoutHeaders(lines: List<String>): ParsedResponse {
        val messages = mutableListOf<EcuMessage>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (lengthLine.matches(line)) {
                val length = line.toInt(16)
                val bytes = mutableListOf<Int>()
                var expected = 0
                i++
                while (i < lines.size && numberedLine.matches(lines[i])) {
                    val m = numberedLine.matchEntire(lines[i])!!
                    if (m.groupValues[1].toInt(16) != expected) return ParsedResponse.Malformed("multi-frame lines out of order")
                    bytes += bytesOf(m.groupValues[2]) ?: return ParsedResponse.Malformed("non-hex data in multi-frame line")
                    expected = (expected + 1) % 16
                    i++
                }
                if (bytes.size < length) return ParsedResponse.Malformed("multi-frame reply shorter than its byte count")
                messages += EcuMessage(null, bytes.take(length))
            } else {
                if (numberedLine.matches(line)) return ParsedResponse.Malformed("numbered line without a byte count")
                val bytes = bytesOf(line) ?: return ParsedResponse.Malformed("non-hex data line")
                messages += EcuMessage(null, bytes)
                i++
            }
        }
        return ParsedResponse.Messages(messages)
    }

    /**
     * Headers on, 11-bit CAN: each line is "<3-digit ID> <PCI> <data> [padding]"
     * with ISO 15765-2 framing visible (p41, p43): single frame PCI 0n, first frame
     * 1n nn, consecutive frames 2n. Frames are grouped per responder ID.
     */
    private fun parseCanWithHeaders(lines: List<String>): ParsedResponse {
        class Partial(val length: Int, val bytes: MutableList<Int>, var nextSeq: Int)
        val order = mutableListOf<String>()
        val partial = mutableMapOf<String, Partial>()
        val messages = mutableListOf<EcuMessage>()
        for (line in lines) {
            if (line.length < 5) return ParsedResponse.Malformed("CAN line too short")
            val id = line.substring(0, 3)
            if (!lengthLine.matches(id)) return ParsedResponse.Malformed("bad CAN identifier")
            val frame = bytesOf(line.substring(3)) ?: return ParsedResponse.Malformed("non-hex CAN frame")
            val pci = frame[0]
            when (pci shr 4) {
                0x0 -> {
                    val len = pci and 0x0F
                    if (len == 0 || frame.size < 1 + len) return ParsedResponse.Malformed("single frame length mismatch")
                    if (id in partial) return ParsedResponse.Malformed("single frame inside a multi-frame reply")
                    messages += EcuMessage(id, frame.subList(1, 1 + len))
                    if (id !in order) order += id
                }
                0x1 -> {
                    if (frame.size < 2) return ParsedResponse.Malformed("first frame too short")
                    if (id in partial) return ParsedResponse.Malformed("second first frame before completion")
                    val len = ((pci and 0x0F) shl 8) or frame[1]
                    partial[id] = Partial(len, frame.drop(2).toMutableList(), 1)
                    if (id !in order) order += id
                }
                0x2 -> {
                    val p = partial[id] ?: return ParsedResponse.Malformed("consecutive frame without first frame")
                    if ((pci and 0x0F) != p.nextSeq) return ParsedResponse.Malformed("consecutive frames out of order")
                    p.bytes += frame.drop(1)
                    p.nextSeq = (p.nextSeq + 1) % 16
                    if (p.bytes.size >= p.length) {
                        messages += EcuMessage(id, p.bytes.take(p.length))
                        partial.remove(id)
                    }
                }
                else -> return ParsedResponse.Malformed("unsupported CAN frame type")
            }
        }
        if (partial.isNotEmpty()) return ParsedResponse.Malformed("multi-frame reply truncated")
        return ParsedResponse.Messages(messages)
    }

    /**
     * Headers on, non-CAN: three header bytes, the data, then one check byte
     * (p42, e.g. "48 6B 10 41 00 BE 3E B8 11 FA"). The third header byte is the
     * responder's address. The check byte is dropped, not verified.
     */
    private fun parseNonCanWithHeaders(lines: List<String>): ParsedResponse {
        val messages = lines.map { line ->
            val bytes = bytesOf(line) ?: return ParsedResponse.Malformed("non-hex data line")
            if (bytes.size < 5) return ParsedResponse.Malformed("non-CAN frame too short")
            EcuMessage("%02X".format(bytes[2]), bytes.subList(3, bytes.size - 1))
        }
        return ParsedResponse.Messages(messages)
    }
}
