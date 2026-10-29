package io.github.eternalliquet.bamboobd2.core.protocol

import io.github.eternalliquet.bamboobd2.core.model.Capability
import io.github.eternalliquet.bamboobd2.core.model.PageEvidence
import io.github.eternalliquet.bamboobd2.core.model.PageStatus
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.model.capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode01CatalogTest {
    @Test fun exactCatalogAndDecoderBoundaries() {
        assertEquals(Mode01Catalog.readings.map { "01${it.pid}" }.toSet(),
            VehicleRequest.entries.map { it.text }.filter { it.length == 4 && it.substring(2) !in Mode01Catalog.pageIds }.toSet())
        val cases = listOf(
            Triple("05", "00", -40.0), Triple("0E", "00", -64.0), Triple("0F", "FF", 215.0),
            Triple("0C", "FF FF", 16383.75), Triple("10", "01 2C", 3.0),
            Triple("42", "30 39", 12.345), Triple("5C", "00", -40.0),
        )
        for ((pid, bytes, value) in cases) {
            val request = VehicleRequest.entries.single { it.text == "01$pid" }
            val reply = "41 $pid $bytes".split(' ').map { it.toInt(16) }
            val result = ObdDecoder.decode(request, ParsedResponse.Messages(listOf(EcuMessage("7E8", reply))), ProtocolForm.CAN_11BIT)
            assertEquals(pid, value, (result as ObdResult.Reading).perEcu.single().value, 0.000001)
            for (bad in listOf(reply.dropLast(1), reply + 0, reply.toMutableList().also { it[0] = 0x42 }, reply.toMutableList().also { it[1] = 0x04 })) {
                assertTrue(pid, ObdDecoder.decode(request, ParsedResponse.Messages(listOf(EcuMessage("7E8", bad))), ProtocolForm.CAN_11BIT) !is ObdResult.Reading)
            }
        }
    }

    @Test fun responderChainsDoNotBorrowSupport() {
        val known = SupportedPids.Known(listOf("05", "21"), complete = false, pages = listOf(
            PageEvidence("7E8", "00", PageStatus.VALID, listOf("05", "20")),
            PageEvidence("7E9", "00", PageStatus.VALID, emptyList()),
            PageEvidence("7E8", "20", PageStatus.VALID, listOf("21", "40")),
            PageEvidence("7E8", "40", PageStatus.MISSING),
        ))
        assertEquals(Capability.SUPPORTED_AND_DECODABLE, known.capability("05", "7E8").capability)
        assertEquals(Capability.UNSUPPORTED, known.capability("05", "7E9").capability)
        assertEquals(Capability.SUPPORTED_NOT_DECODED, known.capability("21", "7E8").capability)
        assertEquals(Capability.UNKNOWN, known.capability("45", "7E8").capability)
        assertEquals(Capability.UNSUPPORTED, known.capability("45", "7E9").capability)
    }
}
