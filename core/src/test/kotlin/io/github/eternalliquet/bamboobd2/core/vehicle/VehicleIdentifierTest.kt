package io.github.eternalliquet.bamboobd2.core.vehicle

import io.github.eternalliquet.bamboobd2.core.manufacturer.CapabilityStatus
import io.github.eternalliquet.bamboobd2.core.manufacturer.MakeEvidence
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerRegistry
import io.github.eternalliquet.bamboobd2.core.manufacturer.porsche.Porsche
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayChunk
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayStep
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayTransport
import io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader
import io.github.eternalliquet.bamboobd2.core.reader.StopReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identification over the real reader and a replayed adapter, in virtual time. Every
 * VIN here is constructed for the test; none is from a vehicle.
 */
class VehicleIdentifierTest {

    @Test fun wholeAttemptTimeoutDuringVinRequiresConnectionTeardown() = runTest {
        val steps = discovery + support(0x40) + step("0902", 2_000L to frames("7E8", CAYENNE) + "\r>")
        val transport = ReplayTransport(ReplayScript("t", "t", "SYNTHETIC", "constructed", "VehicleIdentifierTest", steps = steps), testScheduler.timeSource)
        val result = VehicleIdentifier(ElmObdReader(transport, "synthetic"), budgetMillis = 600).identify()
        assertTrue("The budget expired while VIN was outstanding", transport.writes.last() == "0902")
        assertEquals(StopReason.NO_RESPONSE, (result as IdentificationResult.Stopped).reason)
        assertTrue(result.detail.contains("did not finish"))
    }

    @Test fun missingVinPromptStopsIdentificationInsteadOfReturningUsableEvidence() = runTest {
        val run = identify(discovery + support(0x40) + step("0902", 10L to frames("7E8", CAYENNE)))
        assertTrue("An unfinished VIN exchange must not become Completed", run.result is IdentificationResult.Stopped)
        assertEquals(StopReason.NO_RESPONSE, (run.result as IdentificationResult.Stopped).reason)
    }

    @Test fun unknownMarketCannotAssertYearOrExcludeManualProfile() {
        for (vin in listOf(CAYENNE, CAYENNE.replaceRange(6, 7, "2"), BRZ)) {
            assertTrue(Vin.parse(vin)!!.modelYear() is YearEvidence.Unknown)
        }
        val evidence = VehicleEvidence(null, SupportedPids.Unknown,
            VinRead.Read(Vin.parse(CAYENNE.replaceRange(6, 7, "2"))!!, listOf("7E8")))
        val context = ManufacturerRegistry.BUNDLED.resolve(evidence, Porsche.CAYENNE_92A.id)
        assertEquals(Porsche.CAYENNE_92A, context.profile)
    }

    @Test
    fun canVinGivesMakeButLeavesYearUnknownAndModelToTheUser() = runTest {
        val run = identify(discovery + support(0x40) + vinStep("7E8", CAYENNE))
        val evidence = (run.result as IdentificationResult.Completed).evidence
        val vin = (evidence.vin as VinRead.Read).vin
        assertEquals("WP1", vin.wmi)
        assertTrue(evidence.modelYear is YearEvidence.Unknown)
        assertEquals(listOf<String?>("7E8"), evidence.responders)
        assertTrue(evidence.protocol!!.startsWith("6: ISO 15765-4 CAN"))
        // The bounded sequence: setup, discovery, one support read, one VIN read; nothing else.
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN", "0900", "0902"), run.transport.writes)
        // The full VIN never appears in a string form a log could pick up.
        assertFalse(CAYENNE in vin.toString() || CAYENNE in evidence.toString())

        val context = ManufacturerRegistry.BUNDLED.resolve(evidence)
        assertEquals(Porsche.MAKE, (context.make as MakeEvidence.FromVin).entry.make)
        assertEquals(null, context.profile)
        assertEquals(Porsche.entry.profiles, context.selectableProfiles(ManufacturerRegistry.BUNDLED))
        assertEquals(CapabilityStatus.NEEDS_CONFIRMATION, context.capabilities.first().status)
    }

    @Test
    fun noVinRequestUnlessAResponderAdvertisesIt() = runTest {
        val run = identify(discovery + support(0x00))
        val evidence = (run.result as IdentificationResult.Completed).evidence
        assertEquals(VinRead.NotAvailable("no responder advertises the VIN"), evidence.vin)
        assertFalse("0902" in run.transport.writes)
        // Generic discovery evidence stays usable.
        assertTrue(evidence.supportedPids is SupportedPids.Known)
    }

    @Test
    fun noDataOrNegativeSupportLeavesTheVinUnread() = runTest {
        for (reply in listOf("NO DATA\r\r>", "7E8 03 7F 09 11\r\r>")) {
            val run = identify(discovery + step("0900", 10L to reply))
            val vin = ((run.result as IdentificationResult.Completed).evidence.vin as VinRead.NotAvailable)
            assertTrue(vin.reason, vin.reason.startsWith("VIN support unknown"))
            assertFalse("0902" in run.transport.writes)
        }
    }

    @Test
    fun disagreeingRespondersAreNotMergedIntoOneVehicle() = runTest {
        val twoEcus = listOf(
            step("0100", 20L to "7E8 06 41 00 BE 1F B8 10\r7E9 06 41 00 80 00 00 00\r\r>"),
            step("ATDPN", 10L to "A6\r\r>"),
        )
        val run = identify(init + twoEcus + step("0900", 10L to "7E8 06 49 00 40 00 00 00\r7E9 06 49 00 40 00 00 00\r\r>") +
            step("0902", 10L to frames("7E8", CAYENNE) + frames("7E9", BRZ) + "\r>"))
        val evidence = (run.result as IdentificationResult.Completed).evidence
        assertEquals(VinRead.Conflict(listOf("7E8", "7E9")), evidence.vin)
        assertTrue(evidence.modelYear is YearEvidence.Unknown)
        val context = ManufacturerRegistry.BUNDLED.resolve(evidence)
        assertTrue(context.make is MakeEvidence.Unknown)
        assertEquals(null, context.service)
    }

    @Test fun aPendingReplyAfterTheVinDoesNotEstablishIdentity() = runTest {
        val pending = "7E8 03 7F 09 78\r"
        val final = frames("7E8", CAYENNE)
        for ((reply, complete) in listOf(pending + final to true, final + pending to false)) {
            val run = identify(discovery + support(0x40) + step("0902", 10L to "$reply\r>"))
            val evidence = (run.result as IdentificationResult.Completed).evidence
            if (complete) assertTrue(evidence.vin is VinRead.Read)
            else {
                assertEquals(VinRead.NotAvailable("response pending, no final reply"), evidence.vin)
                assertTrue(ManufacturerRegistry.BUNDLED.resolve(evidence).make is MakeEvidence.Unknown)
            }
        }
    }

    @Test
    fun truncatedOrInvalidVinRepliesAreNotEvidence() = runTest {
        val full = frames("7E8", CAYENNE)
        val truncated = full.substringBeforeLast("7E8 22")
        val badChars = frames("7E8", CAYENNE.replaceRange(12, 13, "O"))
        for (reply in listOf(truncated, badChars)) {
            val run = identify(discovery + support(0x40) + step("0902", 10L to "$reply\r>"))
            assertTrue((run.result as IdentificationResult.Completed).evidence.vin is VinRead.NotAvailable)
        }
    }

    @Test
    fun legacyProtocolsAreNotAskedForTheVin() = runTest {
        val run = identify(init + listOf(
            step("0100", 20L to "48 6B 10 41 00 BE 1F B8 10 AA\r\r>"),
            step("ATDPN", 10L to "A3\r\r>"),
        ))
        val evidence = (run.result as IdentificationResult.Completed).evidence
        assertEquals("VIN support unknown: ${VehicleInfoDecoder.CAN_ONLY}", (evidence.vin as VinRead.NotAvailable).reason)
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP0", "0100", "ATDPN"), run.transport.writes)
    }

    @Test fun unfinishedLegacyDiscoveryAlsoRequiresTeardown() = runTest {
        val run = identify(init + listOf(
            step("0100", 20L to "48 6B 10 41 00 80 00 00 01 AA\r\r>"),
            step("ATDPN", 10L to "A3\r\r>"),
            step("0120", 10L to "48 6B 10 41 20 00 00 00 00 AA\r"),
        ))
        assertTrue("A partial support map must not hide an unfinished exchange", run.result is IdentificationResult.Stopped)
        assertEquals(StopReason.NO_RESPONSE, (run.result as IdentificationResult.Stopped).reason)
        assertFalse(run.transport.writes.any { it.startsWith("09") })
    }

    @Test
    fun noAnsweringVehicleOrDisconnectStopsWithoutEvidence() = runTest {
        val silent = identify(init + listOf(
            step("0100", 20L to "SEARCHING...\rUNABLE TO CONNECT\r\r>"),
            step("ATDPN", 10L to "A0\r\r>"),
        ))
        assertEquals(StopReason.NO_PROTOCOL, (silent.result as IdentificationResult.Stopped).reason)

        val dropped = identify(discovery + support(0x40) + step("0902", disconnectAfter = 5L))
        assertEquals(StopReason.DISCONNECTED, (dropped.result as IdentificationResult.Stopped).reason)
    }

    @Test
    fun yearRuleAndCheckDigit() {
        assertTrue(Vin.parse(BRZ)!!.modelYear() is YearEvidence.Unknown)
        assertTrue(Vin.parse("1HGCM82633A000001")!!.modelYear() is YearEvidence.Unknown)
        // Public worked example of the 49 CFR 565.15 check digit.
        assertTrue(Vin.parse("1M8GDM9AXKP042788")!!.checkDigitMatches)
        assertFalse(Vin.parse("1M8GDM9A1KP042788")!!.checkDigitMatches)
        assertEquals(null, Vin.parse("WP1AA2A2XELA0000"))
        assertEquals(null, Vin.parse("WP1AA2A2XELA0000I"))
        assertEquals("WP1" + "•".repeat(14), Vin.parse(CAYENNE)!!.masked())
    }

    private class Run(val result: IdentificationResult, val transport: ReplayTransport)

    private suspend fun TestScope.identify(steps: List<ReplayStep>): Run {
        val transport = ReplayTransport(ReplayScript("t", "t", "SYNTHETIC", "constructed", "VehicleIdentifierTest", steps = steps), testScheduler.timeSource)
        val reader = ElmObdReader(transport, "synthetic", discoveryClockMillis = { testScheduler.currentTime })
        return Run(VehicleIdentifier(reader).identify(), transport)
    }

    private fun step(request: String, vararg chunks: Pair<Long, String>, disconnectAfter: Long? = null) =
        ReplayStep(request, chunks.map { ReplayChunk(it.first, it.second) }, disconnectAfter)

    /** "49 02 01" + 17 ASCII bytes as ISO-TP frames with headers on, shaped like ELM327DSJ's multi-frame example. */
    private fun frames(id: String, vin: String): String {
        val payload = listOf(0x49, 0x02, 0x01) + vin.map { it.code }
        val hex = { bytes: List<Int> -> bytes.joinToString(" ") { "%02X".format(it) } }
        return "$id 10 14 ${hex(payload.take(6))}\r$id 21 ${hex(payload.subList(6, 13))}\r$id 22 ${hex(payload.subList(13, 20))}\r"
    }

    private fun vinStep(id: String, vin: String) = listOf(step("0902", 10L to frames(id, vin) + "\r>"))

    private fun support(firstByte: Int) = listOf(step("0900", 10L to "7E8 06 49 00 %02X 00 00 00\r\r>".format(firstByte)))

    private val init = listOf(
        step("ATE0", 10L to "ATE0\rOK\r\r>"),
        step("ATL0", 10L to "OK\r\r>"),
        step("ATH1", 10L to "OK\r\r>"),
        step("ATSP0", 10L to "OK\r\r>"),
    )
    private val discovery = init + listOf(
        step("0100", 20L to "SEARCHING...\r7E8 06 41 00 BE 1F B8 10\r\r>"),
        step("ATDPN", 10L to "A6\r\r>"),
    )

    private companion object {
        const val CAYENNE = "WP1AA2A2XELA00001"
        const val BRZ = "JF1ZCAC1XD1600001"
    }
}
