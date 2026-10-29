package io.github.eternalliquet.bamboobd2.core.protocol

import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F9: deny by default, exact allowlist, nothing rejected reaches a transport. */
class RequestPolicyTest {

    private val vehicleAllowlist = (Mode01Catalog.readings.map { "01${it.pid}" } + Mode01Catalog.pageIds.map { "01$it" } + listOf("03", "07")).toSet()

    // Owner-approved adapter configuration/information commands (2026-10-28).
    private val adapterAllowlist = setOf("ATE0", "ATL0", "ATH1", "ATDPN", "ATI", "AT@1", "ATRV", "ATSP0")
    private val allowlist = vehicleAllowlist + adapterAllowlist

    /** Records every write; used to show rejected requests never reach a transport. */
    private class SpyTransport : AdapterTransport {
        override val kind = TransportKind.FAKE
        val written = mutableListOf<String>()
        override suspend fun write(request: Request) { written += request.text }
        override suspend fun read(): ByteArray? = null
    }

    private fun submit(spy: SpyTransport, text: String) = runBlocking {
        when (val d = RequestPolicy.evaluate(text)) {
            is PolicyDecision.Accepted -> spy.write(d.request)
            is PolicyDecision.Rejected -> Unit
        }
    }

    @Test
    fun allowlistIsExactlyTheApprovedRequestsAndPinned() {
        assertEquals(allowlist, RequestPolicy.allowlist.toSet())
        assertEquals(vehicleAllowlist, VehicleRequest.entries.map { it.text }.toSet())
        assertEquals(adapterAllowlist, RequestPolicy.approvedAdapterCommands.toSet())
        // Any change to the allowlist must update this pinned hash in review.
        val digest = MessageDigest.getInstance("SHA-256").digest(RequestPolicy.allowlist.joinToString("\n").toByteArray())
        assertEquals(PINNED_ALLOWLIST_SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun explicitDangerousAndNearMissRequestsAreRejected() {
        val rejected = listOf(
            "04", "0400", "08", "09", "0902", "0A", "10", "11", "1101", "14", "19", "22", "22F190", "27", "2701", "2E",
            "2F", "31", "3101", "34", "35", "36", "3E", "02", "0200", "05", "06", "0101", "0121", "0141", "01FF",
            "", " ", "\r", "\t", "0 4", "01 05", "010c", "0105 ", " 0105", "0105\r", "01050", "0105\r04",
            "ATZ", "ATWS", "ATD", "ATSP6", "ATSP3", "ATSPA0", "ATSP00", "atsp0", "ATSP 0", "AT SP 0", "ATTP0", "ATTPA6", "ATSH7E0", "ATCRA7E8", "ATPP2CSV81", "ATSD00",
            "ATSI", "ATFI", "ATMA", "ATST32", "ATDP", "ATE1", "ATH0", "ATL1", "ATS0", "ATI0", "AT@2", "ATRV0",
            "atz", "ate0", "AT E0", "ATE0 ", " ATI", "ATI\r", "ATE0\rATZ", "AT@1\r04",
        )
        val spy = SpyTransport()
        for (text in rejected) {
            assertTrue("'$text' must be rejected", RequestPolicy.evaluate(text) is PolicyDecision.Rejected)
            submit(spy, text)
        }
        assertTrue(spy.written.isEmpty())
    }

    @Test
    fun randomStringsAreAcceptedIffExactlyAllowlisted() {
        val random = Random(9)
        val alphabet = "0123456789ABCDEFabcdefATSPZHILRVN@ \r\n\t\u0000"
        val spy = SpyTransport()
        val candidates = (1..20_000).map {
            if (random.nextInt(10) == 0) {
                allowlist.random(random) // make sure accepted inputs are exercised too
            } else {
                String(CharArray(random.nextInt(0, 7)) { alphabet.random(random) })
            }
        }
        for (text in candidates) {
            val accepted = RequestPolicy.evaluate(text) is PolicyDecision.Accepted
            assertEquals("'$text'", text in allowlist, accepted)
            submit(spy, text)
        }
        assertTrue(spy.written.all { it in allowlist })
        assertTrue(spy.written.isNotEmpty())
    }

    @Test
    fun approvedAdapterCommandsAreAcceptedAsAdapterRequests() {
        for (cmd in AdapterCommand.entries) {
            val d = RequestPolicy.evaluate(cmd.text) as PolicyDecision.Accepted
            assertEquals(Request.Kind.ADAPTER, d.request.kind)
            assertEquals(d.request, RequestPolicy.adapter(cmd))
        }
        assertEquals("ATE0\r", String(RequestPolicy.adapter(AdapterCommand.ECHO_OFF).encoded(), Charsets.US_ASCII))
    }

    @Test
    fun encodedRequestIsCanonicalTextAndCarriageReturn() {
        val r = RequestPolicy.vehicle(VehicleRequest.ENGINE_RPM)
        assertEquals("010C\r", String(r.encoded(), Charsets.US_ASCII))
    }

    @Test
    fun transportWriteAcceptsOnlyARequest() {
        val write = AdapterTransport::class.java.declaredMethods.single { it.name == "write" }
        assertEquals(Request::class.java, write.parameterTypes.first())
    }

    private companion object {
        const val PINNED_ALLOWLIST_SHA256 = "55653bc23ab8eec87bf6d3a01d0d2c8c65e31352a4e4024b1757f0b65c7ad210"
    }
}
