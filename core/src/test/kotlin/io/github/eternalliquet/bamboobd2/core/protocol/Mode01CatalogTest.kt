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
            VehicleRequest.entries.map { it.text }.filter { it.length == 4 && it.startsWith("01") && it.substring(2) !in Mode01Catalog.pageIds }.toSet())
        assertEquals(27, Mode01Catalog.readings.size)
        assertEquals(27, Mode01Catalog.readings.map { it.labelKey }.distinct().size)
        val cases = listOf(
            Triple("05", "00", -40.0), Triple("0E", "00", -64.0), Triple("0F", "FF", 215.0),
            Triple("0C", "FF FF", 16383.75), Triple("10", "01 2C", 3.0),
            Triple("42", "30 39", 12.345), Triple("5C", "00", -40.0),
            Triple("06", "00", -100.0), Triple("07", "3C", -53.125),
            Triple("08", "80", 0.0), Triple("09", "FF", 99.21875),
            Triple("04", "FF", 100.0), Triple("0B", "64", 100.0),
            Triple("0D", "40", 64.0), Triple("0E", "80", 0.0),
            Triple("11", "80", 128 * 100.0 / 255), Triple("1F", "02 58", 600.0),
            Triple("2F", "80", 128 * 100.0 / 255), Triple("33", "64", 100.0),
            Triple("45", "FF", 100.0), Triple("46", "00", -40.0),
            Triple("47", "00", 0.0), Triple("48", "80", 128 * 100.0 / 255),
            Triple("49", "FF", 100.0), Triple("4A", "00", 0.0),
            Triple("4B", "80", 128 * 100.0 / 255), Triple("4C", "FF", 100.0),
            Triple("5A", "80", 128 * 100.0 / 255),
        )
        for ((pid, bytes, value) in cases) {
            val request = VehicleRequest.entries.single { it.text == "01$pid" }
            val reply = "41 $pid $bytes".split(' ').map { it.toInt(16) }
            val result = ObdDecoder.decode(request, ParsedResponse.Messages(listOf(EcuMessage("7E8", reply))), ProtocolForm.CAN_11BIT)
            assertEquals(pid, value, (result as ObdResult.Reading).perEcu.single().value, 0.000001)
            for (bad in listOf(reply.dropLast(1), reply + 0, reply.toMutableList().also { it[0] = 0x42 }, reply.toMutableList().also { it[1] = if (pid == "04") 0x05 else 0x04 })) {
                assertTrue(pid, ObdDecoder.decode(request, ParsedResponse.Messages(listOf(EcuMessage("7E8", bad))), ProtocolForm.CAN_11BIT) !is ObdResult.Reading)
            }
        }
    }

    @Test fun responderChainsDoNotBorrowSupport() {
        val known = SupportedPids.Known(listOf("05", "21"), complete = false, pages = listOf(
            PageEvidence("7E8", "00", PageStatus.VALID, listOf("05"), continues = true),
            PageEvidence("7E9", "00", PageStatus.VALID, emptyList()),
            PageEvidence("7E8", "20", PageStatus.VALID, listOf("21"), continues = true),
            PageEvidence("7E8", "40", PageStatus.MISSING),
        ))
        assertEquals(Capability.SUPPORTED_AND_DECODABLE, known.capability("05", "7E8").capability)
        assertEquals(Capability.UNSUPPORTED, known.capability("05", "7E9").capability)
        assertEquals(Capability.SUPPORTED_NOT_DECODED, known.capability("21", "7E8").capability)
        assertEquals(Capability.UNKNOWN, known.capability("45", "7E8").capability)
        assertEquals(Capability.UNSUPPORTED, known.capability("45", "7E9").capability)
    }

    @Test fun oneBadResponderDoesNotEraseValidReadingOrMap() {
        val reply = ParsedResponse.Messages(listOf(
            EcuMessage("7E8", listOf(0x41, 0x05, 0x7B)),
            EcuMessage("7E9", listOf(0x41, 0x05, 0x7B, 0x00)),
        ))
        val reading = ObdDecoder.decode(VehicleRequest.COOLANT_TEMP, reply, ProtocolForm.CAN_11BIT) as ObdResult.Reading
        assertEquals(listOf(EcuValue("7E8", 83.0)), reading.perEcu)
        assertTrue(reading.failures.single().contains("7E9"))
        val page = ObdDecoder.decode(VehicleRequest.SUPPORTED_PIDS_01_20,
            ParsedResponse.Messages(listOf(
                EcuMessage("7E8", listOf(0x41, 0x00, 0, 0, 0, 1)),
                EcuMessage("7E9", listOf(0x41, 0x20, 0, 0, 0, 1)),
            )), ProtocolForm.CAN_11BIT) as ObdResult.SupportedPids
        assertEquals(setOf(0x20), page.union)
        assertTrue(page.failures.single().contains("7E9"))
        val conflict = ObdDecoder.decode(VehicleRequest.COOLANT_TEMP,
            ParsedResponse.Messages(listOf(
                EcuMessage("7E8", listOf(0x41, 0x05, 0x7B)),
                EcuMessage("7E8", listOf(0x7F, 0x01, 0x11)),
                EcuMessage("7E9", listOf(0x41, 0x05, 0x7C)),
            )), ProtocolForm.CAN_11BIT) as ObdResult.Reading
        assertEquals(listOf(EcuValue("7E9", 84.0)), conflict.perEcu)
        assertTrue(conflict.failures.single().contains("conflicting"))
    }

    @Test fun overflowContinuationIsMetadataOnly() {
        val page = ObdDecoder.decode(VehicleRequest.SUPPORT_PAGE_E0,
            ParsedResponse.Messages(listOf(EcuMessage("7E8", listOf(0x41, 0xE0, 0, 0, 0, 1)))),
            ProtocolForm.CAN_11BIT) as ObdResult.SupportedPids
        assertTrue(page.union.isEmpty())
        assertEquals(setOf("7E8"), page.continuationByEcu)
    }
}
