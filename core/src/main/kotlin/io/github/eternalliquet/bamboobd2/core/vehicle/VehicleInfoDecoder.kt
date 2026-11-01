package io.github.eternalliquet.bamboobd2.core.vehicle

import io.github.eternalliquet.bamboobd2.core.protocol.EcuMessage
import io.github.eternalliquet.bamboobd2.core.protocol.ParsedResponse
import io.github.eternalliquet.bamboobd2.core.protocol.ProtocolForm

/**
 * Decodes the two Mode 09 replies used for identification (SAE J1979 / ISO 15031-5,
 * Service 09). Layouts, as ISO 15765-4 CAN carries them after ISO-TP reassembly:
 *
 * - `0900` → `49 00 b1 b2 b3 b4`: a bitmap of PIDs 01–20; PID 02 is bit 6 of `b1`.
 * - `0902` → `49 02 01` followed by 17 ASCII characters (one data item, 20 bytes).
 *
 * Only 11-bit CAN is decoded. Legacy protocols send the VIN as five numbered
 * four-byte messages; that layout is not implemented, so those cars stay unidentified
 * rather than risk a misread. A reply that does not match exactly is not evidence.
 */
object VehicleInfoDecoder {

    /** Which responders advertise the VIN PID. */
    sealed interface Support {
        data class Known(val vinResponders: List<String?>) : Support
        data class NotAvailable(val reason: String) : Support
    }

    const val CAN_ONLY = "automatic VIN reading supports 11-bit CAN replies only"

    fun support(parsed: ParsedResponse, form: ProtocolForm): Support {
        if (form != ProtocolForm.CAN_11BIT) return Support.NotAvailable(CAN_ONLY)
        val messages = when (val m = finals(parsed, 0x00)) {
            is Finals.Messages -> m.messages
            is Finals.None -> return Support.NotAvailable(m.reason)
        }
        val valid = messages.filter { it.bytes.size == 6 && it.bytes[0] == 0x49 && it.bytes[1] == 0x00 }
        if (valid.isEmpty()) return Support.NotAvailable("no valid Mode 09 support reply")
        return Support.Known(valid.filter { (it.bytes[2] shr 6) and 1 == 1 }.map { it.ecu })
    }

    fun vin(parsed: ParsedResponse, form: ProtocolForm): VinRead {
        if (form != ProtocolForm.CAN_11BIT) return VinRead.NotAvailable(CAN_ONLY)
        val messages = when (val m = finals(parsed, 0x02)) {
            is Finals.Messages -> m.messages
            is Finals.None -> return VinRead.NotAvailable(m.reason)
        }
        val read = messages.mapNotNull { m ->
            if (m.bytes.size != 3 + Vin.LENGTH || m.bytes[0] != 0x49 || m.bytes[1] != 0x02 || m.bytes[2] != 0x01) return@mapNotNull null
            val text = String(CharArray(Vin.LENGTH) { (m.bytes[3 + it] and 0xFF).toChar() })
            Vin.parse(text)?.let { m.ecu to it }
        }
        if (read.isEmpty()) return VinRead.NotAvailable("VIN reply was not a valid 17-character VIN")
        val distinct = read.map { it.second }.distinct()
        return if (distinct.size == 1) VinRead.Read(distinct.single(), read.map { it.first })
            else VinRead.Conflict(read.map { it.first }.distinct())
    }

    private sealed interface Finals {
        data class Messages(val messages: List<EcuMessage>) : Finals
        data class None(val reason: String) : Finals
    }

    /**
     * Final messages only. A "7F 09 78" (response pending) is dropped when the same
     * responder later answered; a responder that sent the same PID twice is excluded.
     */
    private fun finals(parsed: ParsedResponse, pid: Int): Finals = when (parsed) {
        ParsedResponse.NoData -> Finals.None("no reply (NO DATA)")
        is ParsedResponse.AdapterError -> Finals.None("adapter reported ${parsed.text}")
        is ParsedResponse.Malformed -> Finals.None("malformed reply: ${parsed.reason}")
        is ParsedResponse.Messages -> {
            val pending = listOf(0x7F, 0x09, 0x78)
            val byEcu = parsed.messages.groupBy { it.ecu }
            val finals = byEcu.mapNotNull { (_, list) ->
                val answers = list.filter { it.bytes != pending }
                answers.singleOrNull()?.takeIf { it.bytes.getOrNull(0) != 0x7F && it.bytes.getOrNull(1) == pid }
            }
            when {
                finals.isNotEmpty() -> Finals.Messages(finals)
                parsed.messages.all { it.bytes == pending } -> Finals.None("response pending, no final reply")
                parsed.messages.any { it.bytes.firstOrNull() == 0x7F } -> Finals.None("negative response")
                else -> Finals.None("unexpected reply")
            }
        }
    }
}
