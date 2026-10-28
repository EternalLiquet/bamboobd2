package io.github.eternalliquet.bamboobd2.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2, F6, F13: every fixture decodes to its source-cited expected result. */
class GoldenFixtureTest {
    @Test
    fun everyFixtureDecodesToItsExpectedResult() {
        val fixtures = ProtocolFixtures.all()
        assertTrue("fixtures present", fixtures.size >= 20)
        val failures = fixtures.mapNotNull { f ->
            val actual = ProtocolFixtures.render(ProtocolFixtures.run(f))
            if (actual == f.expected) null else "${f.id}: expected ${f.expected} but was $actual"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun dtcLetterTableMatchesDataSheet() {
        // ELM327DSJ p34: first hex digit 0-F -> P0..P3, C0..C3, B0..B3, U0..U3.
        val expected = listOf("P0", "P1", "P2", "P3", "C0", "C1", "C2", "C3", "B0", "B1", "B2", "B3", "U0", "U1", "U2", "U3")
        (0..15).forEach { nibble -> assertEquals(expected[nibble] + "123", ObdDecoder.dtcText(nibble shl 4 or 0x1, 0x23)) }
        // p34 examples: D016 -> U1016, 1131 -> P1131.
        assertEquals("U1016", ObdDecoder.dtcText(0xD0, 0x16))
        assertEquals("P1131", ObdDecoder.dtcText(0x11, 0x31))
    }

    @Test
    fun pendingIsFlaggedOnlyForCode78() {
        val pending = ProtocolFixtures.run(ProtocolFixtures.all().single { it.id == "ds-p45-0105-pending" })
        assertTrue((pending as ObdResult.Negative).responsePending)
        val other = ProtocolFixtures.run(ProtocolFixtures.all().single { it.id == "rule-0105-negative" })
        assertTrue(!(other as ObdResult.Negative).responsePending)
    }
}
