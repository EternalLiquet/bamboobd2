package io.github.eternalliquet.bamboobd2.core.protocol

import org.junit.Assert.*
import org.junit.Test

class RecordedFramingTest {
    @Test fun manufacturerDataUsesSameFramingWithoutBecomingExecutable() {
        val parsed = ElmResponseParser.parseRecorded("22F187\r793 05 62 F1 87 41 42\r", "22F187", ResponseFormat(true, ProtocolForm.CAN_11BIT))
        assertEquals(ParsedResponse.Messages(listOf(EcuMessage("793", listOf(0x62, 0xf1, 0x87, 0x41, 0x42)))), parsed)
        assertTrue(RequestPolicy.evaluate("22F187") is PolicyDecision.Rejected)
    }
    @Test fun recordedWhitespaceIsRetainedButEchoComparisonAcceptsByteSpacing() {
        val parsed = ElmResponseParser.parseRecorded("22 F1 87\r793 04 62 F1 87 41\r", "22 F1\t87", ResponseFormat(true, ProtocolForm.CAN_11BIT))
        assertTrue(parsed is ParsedResponse.Messages)
        assertTrue(RequestPolicy.evaluate("22 F1 87") is PolicyDecision.Rejected)
    }

}
