package io.github.eternalliquet.bamboobd2.app

import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothAvailability
import io.github.eternalliquet.bamboobd2.app.bluetooth.BluetoothPlatform
import io.github.eternalliquet.bamboobd2.app.bluetooth.BondedDevice
import io.github.eternalliquet.bamboobd2.app.bluetooth.RfcommSocket
import io.github.eternalliquet.bamboobd2.app.connection.ConnectionState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowLooper

/** The paired scanner every test uses. The address is constructed. */
internal val TEST_SCANNER = BondedDevice("VEEPEAK", "00:11:22:33:44:55")

/**
 * An ELM327-style socket that answers each CR-terminated command from [replies];
 * anything else gets no answer. [hold] commands wait for [release] before answering.
 * Constructed data only, never from a vehicle.
 */
internal class ScriptedElmSocket(
    private val replies: Map<String, String>,
    private val connectError: IOException? = null,
    gatedConnect: Boolean = false,
    private val hold: Set<String> = emptySet(),
) : RfcommSocket {
    val connectEntered = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val release = CountDownLatch(if (gatedConnect || hold.isNotEmpty()) 1 else 0)
    private val gated = gatedConnect
    private val written = ByteArrayOutputStream()
    private val toApp = QueueStream()
    override val input: InputStream = toApp

    override fun connect() {
        connectEntered.countDown()
        if (gated && !release.await(30, TimeUnit.SECONDS)) throw IOException("test never released connect")
        connectError?.let { throw it }
    }

    override val output: OutputStream = object : OutputStream() {
        private val line = StringBuilder()
        override fun write(b: Int) {
            synchronized(written) { written.write(b) }
            if (b != '\r'.code) { line.append(b.toChar()); return }
            val command = line.toString()
            line.setLength(0)
            val reply = replies[command] ?: return
            if (command in hold) Thread {
                if (release.await(30, TimeUnit.SECONDS)) toApp.push(reply)
            }.start() else toApp.push(reply)
        }
    }

    override fun close() { release.countDown(); toApp.end(); closed.countDown() }

    /** The scanner drops the link (e.g. unplugged). */
    fun disconnect() = toApp.end()

    fun writes(): String = synchronized(written) { written.toString(Charsets.US_ASCII.name()) }
    val isClosed get() = closed.count == 0L
}

/**
 * Bytes from the fake scanner to the app. Unlike PipedInputStream, a reply written by a
 * short-lived thread does not make later reads fail with "write end dead".
 */
private class QueueStream : InputStream() {
    private val chunks = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var position = 0
    @Volatile private var ended = false

    fun push(text: String) { if (!ended) chunks.put(text.toByteArray(Charsets.US_ASCII)) }
    fun end() { ended = true; chunks.put(ByteArray(0)) }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (position >= current.size) {
            val next = chunks.take()
            if (next.isEmpty()) { chunks.put(next); return -1 }
            current = next; position = 0
        }
        val n = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, n)
        position += n
        return n
    }

    override fun close() = end()
}

/** Lists [TEST_SCANNER] as paired and hands out the next socket for each connection. */
internal class ScannerPlatform(
    private val availability: BluetoothAvailability = BluetoothAvailability.Ready(listOf(TEST_SCANNER)),
    private val next: () -> RfcommSocket,
) : BluetoothPlatform {
    @Volatile var opens = 0
    override val connectPermission: String? = null
    override fun availability() = availability
    override fun openSocket(address: String): RfcommSocket { opens++; return next() }
}

/** Constructed replies, shaped like the ELM327 data sheet's CAN examples. */
internal object TestCars {
    val setup = mapOf("ATE0" to "ATE0\rOK\r\r>", "ATL0" to "OK\r\r>", "ATH1" to "OK\r\r>", "ATSP0" to "OK\r\r>", "ATDPN" to "A6\r\r>")

    /** Synthetic VINs with real manufacturer codes and model-year codes only. */
    const val CAYENNE_VIN = "WP1AA2A2XELA00001"
    const val BRZ_VIN = "JF1ZCAC1XD1600001"

    /** "49 02 01" + 17 ASCII characters as ISO-TP frames with headers on. */
    fun vinFrames(vin: String, id: String = "7E8"): String {
        val payload = listOf(0x49, 0x02, 0x01) + vin.map { it.code }
        val hex = { bytes: List<Int> -> bytes.joinToString(" ") { "%02X".format(it) } }
        return "$id 10 14 ${hex(payload.take(6))}\r$id 21 ${hex(payload.subList(6, 13))}\r$id 22 ${hex(payload.subList(13, 20))}\r\r>"
    }

    private fun car(vin: String) = setup + mapOf(
        "0100" to "SEARCHING...\r7E8 06 41 00 98 3B 80 10\r\r>",
        "0900" to "7E8 06 49 00 54 40 00 00\r\r>",
        "0902" to vinFrames(vin),
        "03" to "7E8 02 43 00\r\r>",
        "07" to "7E8 02 47 00\r\r>",
    )

    val cayenne = car(CAYENNE_VIN)
    val brz = car(BRZ_VIN)

    /** Answers generic discovery but not Mode 09: the car stays unidentified. */
    val unidentified = setup + mapOf(
        "0100" to "SEARCHING...\r7E8 06 41 00 98 3B 80 10\r\r>",
        "0900" to "NO DATA\r\r>",
        "03" to "7E8 04 43 01 01 33\r\r>",
        "07" to "7E8 02 47 00\r\r>",
    )

    const val IDENTIFY_WITH_VIN = "ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r0900\r0902\r"
    const val IDENTIFY_NO_VIN = "ATE0\rATL0\rATH1\rATSP0\r0100\rATDPN\r0900\r"
}

internal fun idleUntil(seconds: Long, description: String = "condition", condition: () -> Boolean) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (!condition()) {
        check(System.nanoTime() < end) { "$description not met in ${seconds}s" }
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        Thread.sleep(5)
    }
}

/** Saves [TEST_SCANNER] and waits until identification finished on the new connection. */
internal fun AppViewModel.connectForTest(): ConnectionState.Ready {
    check(chooseScanner(TEST_SCANNER)) { "connection did not start: ${connection.value}" }
    idleUntil(30, "identification") { connection.value is ConnectionState.Ready || connection.value is ConnectionState.Disconnected }
    return connection.value as? ConnectionState.Ready ?: error("not connected: ${connection.value}")
}

/**
 * AndroidX's static AndroidViewModelFactory keeps the first Robolectric test's Application,
 * so the activity's ViewModel reads one preferences file for the whole run. A test that
 * saves the scanner through that ViewModel must forget it afterwards, or the next test's
 * activity would connect at startup.
 */
internal fun forgetActivityScanner(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>) {
    runCatching { androidx.lifecycle.ViewModelProvider(rule.activity)[AppViewModel::class.java].forgetScanner() }
}
