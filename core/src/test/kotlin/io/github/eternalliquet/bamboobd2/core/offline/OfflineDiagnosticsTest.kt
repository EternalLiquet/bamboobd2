package io.github.eternalliquet.bamboobd2.core.offline

import io.github.eternalliquet.bamboobd2.core.protocol.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OfflineDiagnosticsTest {
    private fun recording(response: String, protocol: DiagnosticProtocol = DiagnosticProtocol.UDS,
                          request: String = "22F187") = ManufacturerRecording(
        ReplayScript("synthetic", "Constructed example", "SYNTHETIC", "constructed", "Unit test, no vehicle", steps = listOf(
            ReplayStep(request, listOf(ReplayChunk(12, response))))), protocol, RecordedFraming.CAN_11BIT_HEADERS,
        expectedResponder = "793", vehicleAlias = "test-car", vehicleApplicability = "constructed", moduleIdentity = "test-module")

    @Test fun importedManufacturerBytesStayDataAndPreserveTiming() {
        val input = recording("793 05 62 F1 87 00 64\r>")
        val roundTrip = ManufacturerRecording.parse(Json.encodeToString(ManufacturerRecording.serializer(), input))
        assertEquals(input, roundTrip)
        assertEquals(12L, roundTrip.script.steps.single().chunks.single().atMillis)
        assertTrue(RequestPolicy.evaluate("22F187") is PolicyDecision.Rejected)
        assertThrows(IllegalArgumentException::class.java) {
            ReplayScript.parse(Json.encodeToString(ReplayScript.serializer(), input.script))
        }
        assertEquals(ExchangeStatus.COMPLETE, OfflineDiagnostics.analyze(roundTrip).exchanges.single().responses.single().status)
    }

    @Test fun explicitProtocolIsRequiredAndWrongEchoIsNotComplete() {
        val unknown = OfflineDiagnostics.analyze(recording("793 05 62 F1 87 00 64\r>", DiagnosticProtocol.UNKNOWN))
        assertEquals(ExchangeStatus.UNKNOWN, unknown.exchanges.single().responses.single().status)
        val unknownFraming = OfflineDiagnostics.analyze(recording("793 05 62 F1 87 00 64\r>").copy(framing = RecordedFraming.UNKNOWN))
        assertEquals(ExchangeStatus.UNKNOWN, unknownFraming.exchanges.single().responses.single().status)
        val mismatch = OfflineDiagnostics.analyze(recording("793 05 62 F1 89 00 64\r>"))
        assertEquals(ExchangeStatus.MISMATCH, mismatch.exchanges.single().responses.single().status)
        val kwp = OfflineDiagnostics.analyze(recording("793 04 61 10 00 64\r>", DiagnosticProtocol.KWP, "2110"))
        assertEquals(ExchangeStatus.COMPLETE, kwp.exchanges.single().responses.single().status)
    }

    @Test fun pendingNegativeAndIncompleteRemainDistinct() {
        val pendingThenComplete = OfflineDiagnostics.analyze(recording("793 03 7F 22 78\r793 05 62 F1 87 00 64\r>"))
        assertEquals(listOf(ExchangeStatus.PENDING, ExchangeStatus.COMPLETE), pendingThenComplete.exchanges.single().responses.map { it.status })
        assertEquals(ExchangeStatus.NEGATIVE, OfflineDiagnostics.analyze(recording("793 03 7F 22 31\r>")).exchanges.single().responses.single().status)
        assertEquals(ExchangeStatus.INCOMPLETE, OfflineDiagnostics.analyze(recording("793 05 62 F1 87 00 64\r")).exchanges.single().responses.single().status)
        assertEquals(ExchangeStatus.INCOMPLETE, OfflineDiagnostics.analyze(recording("793 10 0A 62 F1 87 01 02 03\r>")).exchanges.single().responses.single().status)
        assertEquals(ExchangeStatus.MALFORMED, OfflineDiagnostics.analyze(recording("793 05 62 XX 87 00 64\r>")).exchanges.single().responses.single().status)
    }

    @Test fun conflictingTerminalsAndWrongResponderCannotDecode() {
        val duplicate = OfflineDiagnostics.analyze(recording("793 05 62 F1 87 00 64\r793 05 62 F1 87 00 65\r>"))
        assertFalse(duplicate.exchanges.single().usable)
        val wrong = OfflineDiagnostics.analyze(recording("792 05 62 F1 87 00 64\r>"))
        assertEquals(ExchangeStatus.MISMATCH, wrong.exchanges.single().responses.single().status)
    }

    @Test fun badInputIsBoundedAndMalformedBytesArePreserved() {
        assertThrows(IllegalArgumentException::class.java) { ManufacturerRecording.parse(" ".repeat(ManufacturerRecording.MAX_JSON_CHARS + 1)) }
        val badTime = recording("garbage>").let { it.copy(script = it.script.copy(steps = listOf(ReplayStep("22F187", listOf(ReplayChunk(-1,"garbage>")))))) }
        assertThrows(IllegalArgumentException::class.java) { OfflineDiagnostics.analyze(badTime) }
        val bad = recording("garbage>")
        assertEquals("garbage>", OfflineDiagnostics.analyze(bad).recording.script.steps.single().chunks.single().text)
    }
}
