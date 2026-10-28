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
    data class SupportedPids(val perEcu: List<EcuValue<Set<Int>>>) : ObdResult {
        /** PIDs reported by at least one responder. */
        val union: Set<Int> get() = perEcu.flatMap { it.value }.toSet()
    }

    data class Reading(val unit: String, val perEcu: List<EcuValue<Double>>) : ObdResult

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
        // A negative response from any responder is reported as such.
        for (m in messages) {
            if (m.bytes.firstOrNull() == 0x7F) {
                if (m.bytes.size < 3) return ObdResult.Malformed("negative response too short")
                if (m.bytes[1] != service) return ObdResult.Malformed("negative response for another service")
                return ObdResult.Negative(m.ecu, m.bytes[1], m.bytes[2])
            }
        }
        // Wrong service never yields a value.
        if (messages.any { it.bytes.firstOrNull() != service + 0x40 }) {
            return ObdResult.Malformed("reply is for a different service")
        }
        return when (request) {
            VehicleRequest.STORED_DTCS -> decodeDtcs(messages, DtcKind.STORED, form)
            VehicleRequest.PENDING_DTCS -> decodeDtcs(messages, DtcKind.PENDING, form)
            else -> decodeMode01(request, messages)
        }
    }

    private fun decodeMode01(request: VehicleRequest, messages: List<EcuMessage>): ObdResult {
        val pid = request.text.substring(2, 4).toInt(16)
        if (messages.any { it.bytes.getOrNull(1) != pid }) return ObdResult.Malformed("reply is for a different PID")
        val size = when (request) {
            VehicleRequest.SUPPORTED_PIDS_01_20 -> 4
            VehicleRequest.ENGINE_RPM -> 2
            else -> 1
        }
        if (messages.any { it.bytes.size != 2 + size }) return ObdResult.Malformed("wrong data length for PID ${request.text}")
        return when (request) {
            VehicleRequest.SUPPORTED_PIDS_01_20 -> ObdResult.SupportedPids(
                messages.map { m -> EcuValue(m.ecu, supportedFromBitmap(m.bytes.subList(2, 6))) },
            )
            else -> {
                val (unit, formula) = formulas.getValue(request)
                ObdResult.Reading(unit, messages.map { m -> EcuValue(m.ecu, formula(m.bytes.subList(2, m.bytes.size))) })
            }
        }
    }

    /**
     * Formulas. Coolant (A - 40, °C) and RPM ((256A + B) / 4) follow the worked
     * examples in ELM327DSJ p31-32. Engine load (100A / 255, %) and vehicle speed
     * (A, km/h) follow manufacturer implementation documentation (Emtron), accepted
     * by the owner for this milestone; SAE J1979 was not inspected. See docs/PROTOCOL.md.
     */
    private val formulas: Map<VehicleRequest, Pair<String, (List<Int>) -> Double>> = mapOf(
        VehicleRequest.ENGINE_LOAD to ("%" to { d -> d[0] * 100.0 / 255.0 }),
        VehicleRequest.COOLANT_TEMP to ("°C" to { d -> d[0] - 40.0 }),
        VehicleRequest.ENGINE_RPM to ("rpm" to { d -> (d[0] * 256 + d[1]) / 4.0 }),
        VehicleRequest.VEHICLE_SPEED to ("km/h" to { d -> d[0].toDouble() }),
    )

    /** PID n (1-32) is supported when bit (7 - ((n-1) mod 8)) of byte (n-1)/8 is set, MSB first (p31). */
    private fun supportedFromBitmap(bytes: List<Int>): Set<Int> =
        (1..0x20).filter { n -> (bytes[(n - 1) / 8] shr (7 - (n - 1) % 8)) and 1 == 1 }.toSet()

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
