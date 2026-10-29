package io.github.eternalliquet.bamboobd2.core.protocol

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind

/** A decoded value and the responder it came from (null when headers are off). */
data class EcuValue<T>(val ecu: String?, val value: T)

/**
 * Typed result of one allowlisted request. Nothing here is a value unless the
 * reply matched the requested service and PID exactly.
 */
sealed interface ObdResult {
    /** Mode 01 PID 00: the PIDs 01-20 each responder reports as supported. */
    data class SupportedPids(val perEcu: List<EcuValue<Set<Int>>>, val failures: List<String> = emptyList()) : ObdResult {
        /** PIDs reported by at least one responder. */
        val union: Set<Int> get() = perEcu.flatMap { it.value }.toSet()
    }

    data class Reading(val unit: String, val perEcu: List<EcuValue<Double>>, val failures: List<String> = emptyList()) : ObdResult

    /** Trouble codes, deduplicated by (ecu, code). An empty list means none reported by the responders. */
    data class Dtcs(val codes: List<Dtc>) : ObdResult

    /** The adapter waited and saw no reply (NO DATA). This is not "unsupported". */
    data object NoData : ObdResult

    data class AdapterError(val kind: AdapterErrorKind, val text: String) : ObdResult

    /**
     * Negative response "7F <service> <code>". Code 0x78 means "response
     * pending" (ELM327DSJ p45); the wait it implies is handled by the session.
     */
    data class Negative(val ecu: String?, val service: Int, val code: Int) : ObdResult {
        val responsePending: Boolean get() = code == 0x78
    }

    data class Malformed(val reason: String) : ObdResult

    /** The byte stream ended before the prompt arrived. */
    data class Incomplete(val partial: String) : ObdResult
}

/** Decodes parsed replies to the seven allowlisted requests. */
object ObdDecoder {

    fun decode(request: VehicleRequest, parsed: ParsedResponse, form: ProtocolForm): ObdResult = when (parsed) {
        ParsedResponse.NoData -> ObdResult.NoData
        is ParsedResponse.AdapterError -> ObdResult.AdapterError(parsed.kind, parsed.text)
        is ParsedResponse.Malformed -> ObdResult.Malformed(parsed.reason)
        is ParsedResponse.Messages -> decodeMessages(request, parsed.messages, form)
    }

    private fun decodeMessages(request: VehicleRequest, messages: List<EcuMessage>, form: ProtocolForm): ObdResult {
        val service = request.text.substring(0, 2).toInt(16)
        val negatives = messages.filter { it.bytes.firstOrNull() == 0x7F }
        val positives = messages - negatives.toSet()
        if (positives.isEmpty() && negatives.isNotEmpty()) {
            val first = negatives.first()
            if (first.bytes.size != 3 || first.bytes[1] != service) return ObdResult.Malformed("invalid negative response")
            return ObdResult.Negative(first.ecu, first.bytes[1], first.bytes[2])
        }
        if (negatives.isNotEmpty() && request.text.length == 4) {
            val result = decodeMode01(request, positives)
            val failures = negatives.map { "${it.ecu ?: "responder"}: negative or malformed response" }
            return when (result) {
                is ObdResult.SupportedPids -> result.copy(failures = result.failures + failures)
                is ObdResult.Reading -> result.copy(failures = result.failures + failures)
                else -> result
            }
        }
        return when (request) {
            VehicleRequest.STORED_DTCS -> {
                if (messages.any { it.bytes.firstOrNull() != service + 0x40 }) ObdResult.Malformed("reply is for a different service")
                else decodeDtcs(messages, DtcKind.STORED, form)
            }
            VehicleRequest.PENDING_DTCS -> {
                if (messages.any { it.bytes.firstOrNull() != service + 0x40 }) ObdResult.Malformed("reply is for a different service")
                else decodeDtcs(messages, DtcKind.PENDING, form)
            }
            else -> decodeMode01(request, messages)
        }
    }

    private fun decodeMode01(request: VehicleRequest, messages: List<EcuMessage>): ObdResult {
        val pid = request.text.substring(2, 4).toInt(16)
        val page = request.text.substring(2) in Mode01Catalog.pageIds
        val reading = Mode01Catalog.lookup(request.text.substring(2))
        val size = if (page) 4 else reading?.bytes ?: return ObdResult.Malformed("PID is not a decoder or page")
        val failures = mutableListOf<String>()
        val valid = messages.filter { m ->
            val reason = when {
                m.bytes.firstOrNull() != 0x41 -> "reply is for a different service"
                m.bytes.getOrNull(1) != pid -> "reply is for a different PID"
                m.bytes.size != 2 + size -> "wrong data length for PID ${request.text}"
                else -> null
            }
            if (reason != null) failures += "${m.ecu ?: "responder"}: $reason"
            reason == null
        }
        val duplicates = valid.groupBy { it.ecu }.filterValues { it.size > 1 }.keys
        val unique = valid.filterNot { it.ecu in duplicates }
        failures += duplicates.map { "${it ?: "responder"}: duplicate or conflicting reply" }
        if (unique.isEmpty()) return ObdResult.Malformed(failures.joinToString("; ").substringAfter(": "))
        return if (page) ObdResult.SupportedPids(
            unique.map { m -> EcuValue(m.ecu, supportedFromBitmap(m.bytes.subList(2, 6), pid)) },
            failures,
        ) else ObdResult.Reading(reading!!.unit,
            unique.map { m -> EcuValue(m.ecu, reading.decode(m.bytes.drop(2))) }, failures)
    }

    /** PID base+1 through base+32; the last bit is the next page's continuation marker. */
    private fun supportedFromBitmap(bytes: List<Int>, base: Int): Set<Int> =
        (1..0x20).filter { n -> (bytes[(n - 1) / 8] shr (7 - (n - 1) % 8)) and 1 == 1 }
            .map { base + it }.toSet()

    /**
     * Mode 03/07. CAN replies carry a DTC count in the second byte; other
     * protocols do not (p34). Each code is two bytes; "00 00" is padding.
     */
    private fun decodeDtcs(messages: List<EcuMessage>, kind: DtcKind, form: ProtocolForm): ObdResult {
        val codes = mutableListOf<Dtc>()
        for (m in messages) {
            val pairs = when (form) {
                ProtocolForm.CAN_11BIT -> {
                    val count = m.bytes.getOrNull(1) ?: return ObdResult.Malformed("DTC reply without count byte")
                    val rest = m.bytes.drop(2)
                    if (rest.size < count * 2) return ObdResult.Malformed("DTC count mismatch")
                    if (rest.drop(count * 2).any { it != 0 }) return ObdResult.Malformed("unexpected bytes after DTCs")
                    rest.take(count * 2)
                }
                ProtocolForm.NON_CAN -> {
                    val rest = m.bytes.drop(1)
                    if (rest.size % 2 != 0) return ObdResult.Malformed("odd number of DTC bytes")
                    rest
                }
            }
            pairs.chunked(2).forEach { (hi, lo) ->
                if (hi != 0 || lo != 0) codes += Dtc(dtcText(hi, lo), kind, m.ecu)
            }
        }
        return ObdResult.Dtcs(codes.distinctBy { it.ecu to it.code })
    }

    /** First hex digit 0-F maps to P0-P3, C0-C3, B0-B3, U0-U3 (p34 table). */
    fun dtcText(hi: Int, lo: Int): String {
        val letter = "PCBU"[hi shr 6]
        val digit = (hi shr 4) and 0x3
        return "$letter$digit" + "%01X%02X".format(hi and 0x0F, lo)
    }
}
