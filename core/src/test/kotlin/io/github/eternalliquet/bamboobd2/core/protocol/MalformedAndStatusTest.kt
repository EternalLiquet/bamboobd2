package io.github.eternalliquet.bamboobd2.core.protocol

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** F4 malformed input and mutations; F5 adapter status texts. */
class MalformedAndStatusTest {

    private fun run(request: VehicleRequest, reply: String, form: ProtocolForm = ProtocolForm.NON_CAN, headers: Boolean = false) =
        ProtocolFixtures.run(request, ResponseFormat(headers, form), listOf(ProtocolFixtures.ascii(reply)))

    @Test
    fun explicitMalformedRepliesAreTypedMalformed() {
        val cases = listOf(
            Triple(VehicleRequest.COOLANT_TEMP, "41 05 7\r\r>", ProtocolForm.NON_CAN), // odd hex length
            Triple(VehicleRequest.COOLANT_TEMP, "41 05 7G\r\r>", ProtocolForm.NON_CAN), // non-hex
            Triple(VehicleRequest.ENGINE_RPM, "41 0C 1A\r\r>", ProtocolForm.NON_CAN), // truncated value
            Triple(VehicleRequest.COOLANT_TEMP, "41 05 7B 00\r\r>", ProtocolForm.NON_CAN), // extra bytes
            Triple(VehicleRequest.COOLANT_TEMP, "42 05 7B\r\r>", ProtocolForm.NON_CAN), // wrong service echo
            Triple(VehicleRequest.COOLANT_TEMP, "41 06 7B\r\r>", ProtocolForm.NON_CAN), // wrong PID echo
            Triple(VehicleRequest.STORED_DTCS, "43 03 01 33\r\r>", ProtocolForm.CAN_11BIT), // DTC count mismatch
            Triple(VehicleRequest.STORED_DTCS, "43 01 01 33 01 71\r\r>", ProtocolForm.CAN_11BIT), // data after counted DTCs
            Triple(VehicleRequest.STORED_DTCS, "43 01 33 00\r\r>", ProtocolForm.NON_CAN), // odd DTC byte count
            Triple(VehicleRequest.COOLANT_TEMP, "\r\r>", ProtocolForm.NON_CAN), // empty
            Triple(VehicleRequest.COOLANT_TEMP, "NO DATA\r41 05 7B\r\r>", ProtocolForm.NON_CAN), // NO DATA mixed with data
            Triple(VehicleRequest.STORED_DTCS, "00A\r0: 43 04 01 33 01 71\r\r>", ProtocolForm.CAN_11BIT), // multi-frame short
            Triple(VehicleRequest.STORED_DTCS, "00A\r1: 43 04 01 33 01 71\r0: 04 20\r\r>", ProtocolForm.CAN_11BIT), // out of order
            Triple(VehicleRequest.COOLANT_TEMP, "7F 01\r\r>", ProtocolForm.NON_CAN), // negative too short
            Triple(VehicleRequest.COOLANT_TEMP, "7F 03 12\r\r>", ProtocolForm.NON_CAN), // negative for another service
        )
        for ((req, reply, form) in cases) {
            assertEquals(reply, "malformed", ProtocolFixtures.render(run(req, reply, form)))
        }
    }

    @Test
    fun canFramesWithHeadersRejectBadSequences() {
        val bad = listOf(
            "7E8 21 04 20 C1 23 00 00 00\r\r>", // consecutive frame without a first frame
            "7E8 10 0A 43 04 01 33 01 71\r7E8 22 04 20 C1 23 00 00 00\r\r>", // wrong sequence number
            "7E8 10 0A 43 04 01 33 01 71\r\r>", // truncated multi-frame
            "7E8 07 43 01 01 33\r\r>", // single-frame length beyond the frame
            "7E8 30 00 00\r\r>", // flow-control frame type is not a reply
        )
        for (reply in bad) {
            assertEquals(reply, "malformed", ProtocolFixtures.render(run(VehicleRequest.STORED_DTCS, reply, ProtocolForm.CAN_11BIT, headers = true)))
        }
    }

    @Test
    fun canIdentifierAndFrameBoundsAreEnforced() {
        // Regressions from the review of 48f67a4: both used to decode as values.
        val coolant = run(VehicleRequest.COOLANT_TEMP, "FFF 03 41 05 7B\r\r>", ProtocolForm.CAN_11BIT, headers = true)
        assertEquals("FFF exceeds the 11-bit maximum 7FF", "malformed", ProtocolFixtures.render(coolant))
        val nineBytes = run(VehicleRequest.STORED_DTCS, "7E8 08 43 03 01 33 01 71 04 20\r\r>", ProtocolForm.CAN_11BIT, headers = true)
        assertEquals("nine-byte frame with single-frame length 8", "malformed", ProtocolFixtures.render(nineBytes))
        val others = listOf(
            "7E8 10 06 43 02 01 33 01 71\r\r>", // first frame announcing a length that fits a single frame
            "7E8 10 0A 43 04 01 33\r7E8 21 01 71 04 20 C1 23 00\r\r>", // first frame shorter than eight bytes
            "7E8 10 0A 43 04 01 33 01 71\r7E8 21 04 20 C1 23 00 00 00 00\r\r>", // nine-byte consecutive frame
        )
        for (reply in others) {
            assertEquals(reply, "malformed", ProtocolFixtures.render(run(VehicleRequest.STORED_DTCS, reply, ProtocolForm.CAN_11BIT, headers = true)))
        }
        // Valid bounds still decode: highest 11-bit ID, and non-zero padding after the payload.
        assertEquals("reading:°C:7FF=83.0", ProtocolFixtures.render(run(VehicleRequest.COOLANT_TEMP, "7FF 03 41 05 7B\r\r>", ProtocolForm.CAN_11BIT, headers = true)))
        assertEquals("reading:°C:7E8=83.0", ProtocolFixtures.render(run(VehicleRequest.COOLANT_TEMP, "7E8 03 41 05 7B AA AA AA AA\r\r>", ProtocolForm.CAN_11BIT, headers = true)))
    }

    @Test
    fun wrongServiceOrPidByteNeverYieldsAValue() {
        // Replace the service and PID bytes with every other value; only the right pair decodes.
        for (service in 0..0xFF) for (pid in listOf(0x05, 0x0C, 0x0D, 0x04, 0x00, 0x06, 0xFF)) {
            val reply = "%02X %02X 7B\r\r>".format(service, pid)
            val result = run(VehicleRequest.COOLANT_TEMP, reply)
            val isValue = result is ObdResult.Reading || result is ObdResult.SupportedPids || result is ObdResult.Dtcs
            assertEquals(reply, service == 0x41 && pid == 0x05, isValue)
        }
    }

    @Test
    fun tenThousandSeededMutationsNeverThrowAndStayTyped() {
        val random = Random(4)
        val fixtures = ProtocolFixtures.all()
        val alphabet = "0123456789ABCDEFXZ: \r\n>?.<!"
        val start = System.nanoTime()
        repeat(10_000) {
            val f = fixtures.random(random)
            val text = StringBuilder(f.reply)
            repeat(random.nextInt(1, 4)) {
                val i = random.nextInt(0, text.length + 1)
                when (random.nextInt(3)) {
                    0 -> text.insert(i, alphabet.random(random))
                    1 -> if (text.isNotEmpty() && i < text.length) text.deleteCharAt(i)
                    else -> if (i < text.length) text.setCharAt(i, alphabet.random(random))
                }
            }
            val result = try {
                ProtocolFixtures.run(f.vehicleRequest, f.format, listOf(ProtocolFixtures.ascii(text.toString())))
            } catch (e: Exception) {
                fail("${f.id} mutated to '${text.toString().replace("\r", "\\r")}' threw $e")
                return
            }
            // A value or negative result still needs the matching service (and PID) in the reply.
            val compact = text.toString().uppercase().filter { it in "0123456789ABCDEF" }
            val service = f.request.substring(0, 2).toInt(16)
            val header = "%02X".format(service + 0x40) + if (f.request.length == 4) f.request.substring(2) else ""
            when (result) {
                is ObdResult.Reading, is ObdResult.SupportedPids, is ObdResult.Dtcs ->
                    assertTrue("${f.id}: value without '$header' in mutated reply", header in compact)
                is ObdResult.Negative -> assertEquals(service, result.service)
                else -> Unit
            }
        }
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue("10,000 mutations took $seconds s", seconds < 10.0)
    }

    @Test
    fun adapterStatusTextsMapToTypedResults() {
        // ELM327DSJ pp87-89. NO DATA is its own result, never "unsupported".
        val expected = mapOf(
            "NO DATA" to "nodata",
            "?" to "adapter:UNKNOWN_COMMAND",
            "UNABLE TO CONNECT" to "adapter:UNABLE_TO_CONNECT",
            "CAN ERROR" to "adapter:CAN_ERROR",
            "BUFFER FULL" to "adapter:BUFFER_FULL",
            "STOPPED" to "adapter:STOPPED",
            "BUS ERROR" to "adapter:BUS_ERROR",
            "BUS BUSY" to "adapter:BUS_BUSY",
            "DATA ERROR" to "adapter:DATA_ERROR",
            "<DATA ERROR" to "adapter:DATA_ERROR",
            "FB ERROR" to "adapter:FB_ERROR",
            "<RX ERROR" to "adapter:RX_ERROR",
            "LV RESET" to "adapter:LV_RESET",
            "ERR94" to "adapter:INTERNAL_ERROR",
            "BUS INIT: ...ERROR" to "adapter:BUS_INIT_ERROR",
        )
        for ((text, result) in expected) {
            assertEquals(text, result, ProtocolFixtures.render(run(VehicleRequest.COOLANT_TEMP, "$text\r\r>")))
        }
        // A stray SEARCHING... line before the data is skipped.
        assertEquals("reading:°C:-=83.0", ProtocolFixtures.render(run(VehicleRequest.COOLANT_TEMP, "SEARCHING...\r41 05 7B\r\r>")))
        // An error after data lines is still reported as the error, not as partial data.
        assertEquals("adapter:BUFFER_FULL", ProtocolFixtures.render(run(VehicleRequest.STORED_DTCS, "43 01 33 00 00 00 00\rBUFFER FULL\r\r>")))
    }
}
