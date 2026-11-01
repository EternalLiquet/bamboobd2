package io.github.eternalliquet.bamboobd2.core.diagnostics

/** Conservative export redaction, including raw frames embedded in reader errors. */
object DiagnosticRedactor {
    const val VIN = "[REDACTED:VIN]"
    const val BLUETOOTH_ADDRESS = "[REDACTED:bluetooth-address]"
    data class Result(val text: String, val redactions: List<String>)
    private val address = Regex("(?i)(?<![0-9A-F])(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}(?![0-9A-F])")
    private val plainVin = Regex("(?<![A-Z0-9])(?=[A-Z0-9]*[G-HJ-NPR-Z])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])")
    private val asciiVin = Regex("[A-HJ-NPR-Z0-9]{17}")
    private val asciiFragment = Regex("[A-HJ-NPR-Z0-9]+")
    private val hex = Regex("[0-9A-Fa-f]+")
    private val spacedFrame = Regex("(?i)(?<![A-Z0-9])(?:[0-9A-F]{3}[ \\t]+)?[0-9A-F]{2}(?:[ \\t]+[0-9A-F]{2}(?![0-9A-F])){1,7}")
    // Even a PCI plus one ASCII byte can be a late VIN fragment in an error detail.
    private val compactFrame = Regex("(?i)(?<![A-Z0-9])[0-9A-F]{4,}(?![A-Z0-9])")
    private val pieces = Regex("[^\\r\\n>]+|[\\r\\n>]")
    private val redactedPiece = Regex("(?:[0-9A-F]{3} )?\\[REDACTED:VIN(?: response)? data, [0-9]+ (?:bytes|characters)\\]")
    private val withheldPiece = Regex("(?:[0-9A-F]{3} )?(?:10 0E 62 F1 87 )?\\[WITHHELD:incomplete payload(?:, [0-9]+ characters)?\\]")
    private data class Frame(val id: String, val bytes: List<Int>) {
        val payload: List<Int> get() = when (bytes.firstOrNull()?.shr(4)) {
            0, 2 -> bytes.drop(1)
            1 -> bytes.drop(2)
            else -> bytes
        }
        val vinStart get() = payload.take(2) == listOf(0x49, 0x02)
        val partNumberStart get() = bytes.size == 8 && bytes.take(5) == listOf(0x10, 0x0E, 0x62, 0xF1, 0x87)
        val asciiContinuation get() = bytes.firstOrNull()?.shr(4) == 2 &&
            asciiFragment.containsMatchIn(String(payload.map(Int::toChar).toCharArray()))
        val negative get() = (bytes.firstOrNull() == 3 && bytes.getOrNull(1) == 0x7F && bytes.size <= 4) ||
            (bytes.firstOrNull() == 0x7F && bytes.size <= 3)
    }
    private fun frame(text: String): Frame? {
        val compact = text.filterNot { it.isWhitespace() }
        if (!hex.matches(compact)) return null
        val header = if (compact.length % 2 == 1 && compact.length >= 5) 3 else 0
        val body = compact.drop(header)
        if (body.length % 2 != 0) return null
        return Frame(compact.take(header).uppercase(), body.chunked(2).map { it.toInt(16) })
    }
    private fun containsVin(frames: List<Frame>): Boolean = listOf(
        frames.flatMap { it.bytes }, frames.flatMap { it.payload },
    ).any { bytes -> asciiVin.containsMatchIn(String(CharArray(bytes.size) { bytes[it].toChar() })) }
    private fun hidden(frame: Frame) = (if (frame.id.isEmpty()) "" else frame.id + " ") +
        "[REDACTED:VIN data, " + frame.bytes.size + " bytes]"

    /** Also handles flattened raw frames in exceptions. */
    fun text(input: String): Result = text(input, emptySet())

    private fun text(input: String, allowedPartNumbers: Set<String>): Result {
        val kinds = mutableListOf<String>()
        var out = address.replace(input) { kinds += "bluetooth-address"; BLUETOOTH_ADDRESS }
        out = plainVin.replace(out) { kinds += "vin"; VIN }
        val matches = (spacedFrame.findAll(out) + compactFrame.findAll(out)).sortedBy { it.range.first }
            .fold(mutableListOf<MatchResult>()) { acc, m ->
                if (acc.lastOrNull()?.range?.last?.let { it >= m.range.first } != true) acc += m
                acc
            }
        val frames = matches.map { frame(it.value) }
        val sensitive = frames.filterNotNull().groupBy { it.id }.filterValues { group ->
            group.any { it.vinStart } || containsVin(group)
        }.keys
        for (i in matches.indices.reversed()) {
            val f = frames[i] ?: continue
            if (!f.negative && (f.id in sensitive || (f.asciiContinuation && f.id !in allowedPartNumbers))) {
                out = out.replaceRange(matches[i].range, hidden(f)); kinds += "vin"
            }
        }
        return Result(out, kinds)
    }

    /** Completed reply. Delimiters and safe negative responses remain as received. */
    fun reply(request: String?, input: String): Result = reply(request, input, emptySet())

    /** Only the immediately following flow-control exchange may continue a known F187 reply. */
    class Context {
        private var partNumbers = emptySet<String>()
        fun clear() { partNumbers = emptySet() }
        fun reply(request: String?, input: String): Result {
            val allowed = if (request?.startsWith("30") == true) partNumbers else emptySet()
            val result = DiagnosticRedactor.reply(request, input, allowed)
            partNumbers = pieces.findAll(input).mapNotNull { frame(it.value) }
                .filter { it.partNumberStart }.map { it.id }.toSet()
            return result
        }
    }

    private fun reply(request: String?, input: String, allowedPartNumbers: Set<String>): Result {
        val parts = pieces.findAll(input).map { it.value }.toList()
        val frames = parts.map(::frame)
        val groups = frames.filterNotNull().groupBy { it.id }
        val sensitive = groups.filterValues { group -> group.any { it.vinStart } || containsVin(group) }.keys
        val partNumbers = (groups.filterValues { group -> group.any { it.partNumberStart } }.keys + allowedPartNumbers) - sensitive
        val kinds = mutableListOf<String>()
        val out = parts.mapIndexed { index, part ->
            val f = frames[index]
            when {
                part in listOf("\r", "\n", ">", VIN) || redactedPiece.matches(part) || withheldPiece.matches(part) || safeStatus(part, request) -> part
                f?.negative == true -> part
                request?.startsWith("09") == true && request != "0900" -> {
                    kinds += "vin"; f?.let(::hidden) ?: ("[REDACTED:VIN response data, " + part.length + " characters]")
                }
                f != null && (f.id in sensitive || (f.id !in partNumbers && f.asciiContinuation)) -> {
                    kinds += "vin"; hidden(f)
                }
                else -> part
            }
        }.joinToString("")
        val rest = text(out, partNumbers)
        return Result(rest.text, kinds + rest.redactions)
    }

    /** Crash checkpoints withhold ambiguous prefixes before raw content is classified. */
    fun partialReply(request: String?, input: String): Result {
        val protected = reply(request, input)
        val out = pieces.replace(protected.text) { match ->
            val part = match.value
            val f = frame(part)
            when {
                part in listOf("\r", "\n", ">", VIN) || redactedPiece.matches(part) || withheldPiece.matches(part) || safeStatus(part, request) -> part
                f?.negative == true -> part
                f != null && f.bytes.firstOrNull() in 1..7 && f.bytes.size <= f.bytes.first() + 1 &&
                    f.bytes.getOrNull(1) in listOf(0x41, 0x43, 0x47) -> part
                f?.partNumberStart == true -> f.id + " 10 0E 62 F1 87 [WITHHELD:incomplete payload]"
                else -> (f?.id?.takeIf { it.isNotEmpty() }?.plus(" ") ?: "") + "[WITHHELD:incomplete payload, " + part.length + " characters]"
            }
        }
        return Result(out, protected.redactions)
    }
    private fun safeStatus(part: String, request: String?) = part.isBlank() || part.trim() == request ||
        part.trim().uppercase() in setOf("OK", "NO DATA", "STOPPED", "SEARCHING...", "?", "BUS INIT: ERROR", "CAN ERROR", "UNABLE TO CONNECT", "LV RESET")
}
