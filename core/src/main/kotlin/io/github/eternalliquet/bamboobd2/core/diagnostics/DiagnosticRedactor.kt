package io.github.eternalliquet.bamboobd2.core.diagnostics

/**
 * Hides VINs and Bluetooth addresses in diagnostic text before it is stored. Every
 * replacement is marked with a `[REDACTED:...]` token and counted, so a reader can see
 * that something was removed. It does not make a log anonymous: adapter names, module
 * part numbers, timestamps and other replies stay.
 */
object DiagnosticRedactor {
    const val VIN = "[REDACTED:VIN]"
    const val BLUETOOTH_ADDRESS = "[REDACTED:bluetooth-address]"

    /** The redacted text and the kinds of redaction applied (one entry per replacement). */
    data class Result(val text: String, val redactions: List<String>)

    private val address = Regex("(?i)(?<![0-9A-F])(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}(?![0-9A-F])")
    // 17 VIN characters with at least one letter a hex string cannot contain, so
    // compact hex such as "7E8064100BE1FB810" is not mistaken for a VIN.
    private val vinLike = Regex("(?<![A-Z0-9])(?=[A-Z0-9]*[G-HJ-NPR-Z])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])")
    private val vinInAscii = Regex("(?=[A-Z0-9]*[G-HJ-NPR-Z])[A-HJ-NPR-Z0-9]{17}")
    private val hexByte = Regex("[0-9A-Fa-f]{2}")
    private val frameLine = Regex("^\\s*((?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]:)\\s*)?((?:[0-9A-Fa-f]{2}\\s*)+)$")

    /** Free text: errors, explanations, adapter output. */
    fun text(input: String): Result {
        val kinds = mutableListOf<String>()
        var out = address.replace(input) { kinds += "bluetooth-address"; BLUETOOTH_ADDRESS }
        out = vinLike.replace(out) { kinds += "vin"; VIN }
        return Result(out, kinds)
    }

    /**
     * One request's complete reply as the adapter sent it. VIN bytes encoded as hex
     * frames are found by decoding each responder's payload; the reply to the VIN
     * request itself (`0902`) always has its data lines hidden. Responder IDs, frame
     * order, byte counts, short replies (such as `7F 09 31`) and adapter text such as
     * `NO DATA` stay visible.
     */
    fun reply(request: String?, input: String): Result {
        val kinds = mutableListOf<String>()
        val lines = input.split('\r')
        val frames = lines.map { line -> frameLine.matchEntire(line)?.let { it.groupValues[1].trim() to hexByte.findAll(it.groupValues[2]).map { b -> b.value.toInt(16) }.toList() } }
        val vinResponders = mutableSetOf<String>()
        if (request != null && request.startsWith("09") && request != "0900") {
            frames.filterNotNull().filter { it.second.size > 4 }.forEach { vinResponders += it.first }
        }
        frames.filterNotNull().groupBy({ it.first }, { it.second }).forEach { (id, payloads) ->
            if (encodesVin(payloads)) vinResponders += id
        }
        val out = lines.mapIndexed { i, line ->
            val frame = frames[i] ?: return@mapIndexed line
            if (frame.first !in vinResponders || frame.second.size <= 4) return@mapIndexed line
            kinds += "vin"
            (if (frame.first.isEmpty()) "" else frame.first + " ") + "[REDACTED:VIN data, ${frame.second.size} bytes]"
        }.joinToString("\r")
        val rest = text(out)
        return Result(rest.text, kinds + rest.redactions)
    }

    /** True when the responder's bytes, with or without ISO-TP framing bytes removed, contain a VIN. */
    private fun encodesVin(payloads: List<List<Int>>): Boolean {
        val raw = payloads.flatten()
        val unframed = payloads.flatMap { frame ->
            when (frame.firstOrNull()?.shr(4)) {
                0x0, 0x2 -> frame.drop(1)
                0x1 -> frame.drop(2)
                else -> frame
            }
        }
        return listOf(raw, unframed).any { bytes ->
            vinInAscii.containsMatchIn(String(CharArray(bytes.size) { bytes[it].toChar() }))
        }
    }
}
