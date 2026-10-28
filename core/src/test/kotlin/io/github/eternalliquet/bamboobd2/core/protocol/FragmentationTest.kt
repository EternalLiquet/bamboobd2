package io.github.eternalliquet.bamboobd2.core.protocol

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * F1: chunking and formatting variants never change the decoded result. Each
 * fixture reply is split at every byte offset and in 200 seeded random ways,
 * across CR/CRLF, echo on/off and spaces on/off.
 */
class FragmentationTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(5)

    private val statusLine = Regex("[A-Z?<!. ]*[A-Z?][A-Z?<!. ]*")

    private fun variants(f: ProtocolFixture): List<String> {
        val base = f.reply
        val noSpaces = base.split('\r').joinToString("\r") { line ->
            if (statusLine.matches(line) && line.any { it.isLetter() && it !in 'A'..'F' }) line else line.replace(" ", "")
        }
        val echo = if (base.startsWith(f.request + "\r")) base else f.request + "\r" + base
        return listOf(base, base.replace("\r", "\r\n"), echo, noSpaces, echo.replace("\r", "\r\n"))
    }

    private fun decodeChunks(f: ProtocolFixture, chunks: List<ByteArray>) =
        ProtocolFixtures.render(ProtocolFixtures.run(f.vehicleRequest, f.format, chunks))

    @Test
    fun everySplitAndSeededChunkingGivesTheSameResult() {
        val random = Random(9)
        for (f in ProtocolFixtures.all()) {
            for (text in variants(f)) {
                val bytes = ProtocolFixtures.ascii(text)
                val whole = decodeChunks(f, listOf(bytes))
                assertEquals("${f.id} variant", f.expected, whole)
                for (cut in 1 until bytes.size) {
                    val chunks = listOf(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size))
                    assertEquals("${f.id} split at $cut", whole, decodeChunks(f, chunks))
                }
                repeat(200) {
                    val chunks = mutableListOf<ByteArray>()
                    var i = 0
                    while (i < bytes.size) {
                        // 1-byte, BLE-sized (20) and other chunk sizes; the prompt often lands alone.
                        val size = listOf(1, 20, random.nextInt(1, 9)).random(random).coerceAtMost(bytes.size - i)
                        chunks += bytes.copyOfRange(i, i + size)
                        i += size
                    }
                    assertEquals("${f.id} random chunking", whole, decodeChunks(f, chunks))
                }
            }
        }
    }

    @Test
    fun nulBytesAreIgnoredAndTextAfterThePromptIsNotPartOfTheReply() {
        val f = ProtocolFixtures.all().single { it.id == "ds-p31-010C-rpm" }
        val withNul = byteArrayOf(0) + ProtocolFixtures.ascii("41 0C 1A") + byteArrayOf(0) + ProtocolFixtures.ascii(" F8\r\r>41 0C 00 00\r\r>")
        assertEquals(f.expected, decodeChunks(f, listOf(withNul)))
    }

    @Test
    fun streamEndingBeforeThePromptIsIncomplete() {
        val f = ProtocolFixtures.all().single { it.id == "ds-p31-010C-rpm" }
        assertEquals("incomplete", decodeChunks(f, listOf(ProtocolFixtures.ascii("41 0C 1A F8\r"))))
    }
}
