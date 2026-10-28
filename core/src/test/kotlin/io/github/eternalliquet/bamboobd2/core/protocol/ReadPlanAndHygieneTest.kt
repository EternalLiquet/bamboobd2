package io.github.eternalliquet.bamboobd2.core.protocol

import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F8 read planning and F16 fixture hygiene. */
class ReadPlanAndHygieneTest {

    @Test
    fun unknownSupportRequestsNothingAndOnlySupportedPidsAreRequested() {
        assertTrue(ReadPlan.readingRequests(SupportedPids.Unknown).isEmpty())
        assertEquals(
            listOf(VehicleRequest.COOLANT_TEMP, VehicleRequest.ENGINE_RPM),
            ReadPlan.readingRequests(SupportedPids.Known(listOf("05", "0C", "1C"))),
        )
    }

    @Test
    fun endToEndOnlySupportedReadingsReachTheTransport() = runBlocking {
        // 0100 reply (ELM327DSJ p31) supports 04, 05, 0C, 0D; this variant clears 0D (bit for PID 13).
        val transport = FakeTransport(
            mapOf(
                "0100" to FakeTransport.text("41 00 BE 17 B8 10\r\r>"),
                "0104" to FakeTransport.text("41 04 80\r\r>"),
                "0105" to FakeTransport.text("41 05 7B\r\r>"),
                "010C" to FakeTransport.text("41 0C 1A F8\r\r>"),
            ),
        )
        val format = ResponseFormat(headers = false, form = ProtocolForm.NON_CAN)
        val supported = ReadPlan.supportedFrom(ObdQuery.run(transport, VehicleRequest.SUPPORTED_PIDS_01_20, format))
        ReadPlan.readingRequests(supported).forEach { ObdQuery.run(transport, it, format) }
        assertEquals(listOf("0100", "0104", "0105", "010C"), transport.writes.map { it.text })
    }

    @Test
    fun noDataOrMalformedSupportMapLeavesSupportUnknown() {
        assertEquals(SupportedPids.Unknown, ReadPlan.supportedFrom(ObdResult.NoData))
        assertEquals(SupportedPids.Unknown, ReadPlan.supportedFrom(ObdResult.Malformed("x")))
        assertEquals(SupportedPids.Unknown, ReadPlan.supportedFrom(ObdResult.Negative(null, 1, 0x12)))
    }

    private val vinShaped = Regex("(?<![A-Za-z0-9])[A-HJ-NPR-Za-hj-npr-z0-9]{17}(?![A-Za-z0-9])")

    @Test
    fun everyFixtureHasCompleteProvenanceMetadataAndNoIdentifiers() {
        val tiers = setOf("primary", "secondary", "constructed")
        for (file in ProtocolFixtures.files()) {
            val f = ProtocolFixtures.all().single { "${it.id}.json" == file.name }
            assertTrue("${f.id}: provenance", f.provenance in setOf("SYNTHETIC", "SANITIZED_CAPTURE"))
            if (f.provenance == "SANITIZED_CAPTURE") {
                assertTrue("${f.id}: capture needs SHA-256", f.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
                assertTrue("${f.id}: capture needs evidence log", !f.evidenceLog.isNullOrBlank())
            }
            assertTrue("${f.id}: source tier", f.sourceTier in tiers)
            assertTrue("${f.id}: source citation", f.source.length >= 10)
            assertTrue("${f.id}: request in allowlist", f.request in RequestPolicy.allowlist)
            assertTrue("${f.id}: form", f.form in ProtocolForm.entries.map { it.name })
            assertTrue("${f.id}: reply ends with prompt", f.reply.endsWith(">"))
            assertTrue("${f.id}: expected", f.expected.isNotBlank())
            assertTrue("${f.id}: no VIN-shaped token", !vinShaped.containsMatchIn(file.readText()))
            assertTrue("${f.id}: no mode 09 reply", !Regex("(^|\\r|\\s)49 ?02").containsMatchIn(f.reply))
        }
    }
}
