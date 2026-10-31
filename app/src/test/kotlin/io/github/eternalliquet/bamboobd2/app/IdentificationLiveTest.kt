package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.eternalliquet.bamboobd2.app.bluetooth.*
import io.github.eternalliquet.bamboobd2.core.experiment.ExperimentOutcome
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** Actual Android connection wiring over a constructed socket, never a car. */
@RunWith(RobolectricTestRunner::class)
class IdentificationLiveTest {
    @Before fun clearSafetyState() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("identification-safety", 0).edit().clear().commit()
    }
    @After fun restoreSafetyState() = clearSafetyState()
    private class Socket(private val prompt: Boolean = true, private val blockConnect: Boolean = false, private val initial: String = ">", private val multiFrame: Boolean = false) : RfcommSocket {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val release = CountDownLatch(if (blockConnect) 1 else 0)
        private val pipe = PipedOutputStream()
        override val input: InputStream = PipedInputStream(pipe, 8192)
        private val bytes = ByteArrayOutputStream()
        private var responder = "793"
        override fun connect() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            if (prompt && closed.count > 0) { pipe.write(initial.toByteArray()); pipe.flush() }
        }
        override val output: OutputStream = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                synchronized(bytes) { bytes.write(b) }
                if (b != 13) { line.append(b.toChar()); return }
                val command = line.toString(); line.setLength(0)
                if (command == "ATCRA77E") responder = "77E"
                val reply = when {
                    command == "ATI" -> "ELM327 constructed\r>"
                    command == "AT@1" -> "OBDII to RS232 Interpreter\r>"
                    command == "ATRV" -> "12.6V\r>"
                    command.startsWith("AT") -> "OK\r>"
                    command == "30020A0000000000" -> "$responder 21 54 31 32 33 34 35 36\r$responder 22 37 00 00 00 00 00 00\r>"
                    command == "0322F18700000000" && multiFrame -> "$responder 10 0E 62 F1 87 54 45 53\r>"
                    command == "0322F18700000000" -> "$responder 04 62 F1 87 41 00 00 00\r>"
                    else -> error("Unexpected command: $command")
                }
                pipe.write(reply.toByteArray()); pipe.flush()
            }
        }
        override fun close() { closed.countDown(); release.countDown(); runCatching { pipe.close() }; runCatching { input.close() } }
        fun writes() = synchronized(bytes) { bytes.toString("US-ASCII").split('\r').filter { it.isNotEmpty() } }
    }
    private class Platform(val socket: Socket) : BluetoothPlatform {
        var opens = 0
        override val connectPermission: String? = null
        override fun availability() = BluetoothAvailability.Ready(listOf(device))
        override fun openSocket(address: String): RfcommSocket { opens++; return socket }
    }
    private fun vm(platform: Platform) = AppViewModel(ApplicationProvider.getApplicationContext<Application>(),
        FileSessionStore(Files.createTempDirectory("experiment-test").toFile()), Dispatchers.IO, platform)
    private fun until(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < end) { "condition not reached" }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }
    @Test fun realConnectionWiringRunsOnlyFixedReadsAndSavesPrivateReport() {
        val p = Platform(Socket()); val vm = vm(p)
        assertTrue(vm.startExperiment(device, reviewed = true))
        until { !vm.experiment.value.busy }
        val result = vm.experiment.value.result!!
        assertEquals(ExperimentOutcome.OBSERVED, result.outcome)
        assertEquals("RAW_CAPTURE", result.recording.provenance)
        assertEquals(listOf("0322F18700000000", "0322F18700000000"), p.socket.writes().filter { !it.startsWith("AT") })
        assertTrue(p.socket.writes().contains("ATSH714"))
        assertEquals(1, p.opens)
        assertTrue(p.socket.closed.await(1, TimeUnit.SECONDS))
        assertTrue(vm.experiment.value.saved)
        assertFalse(vm.experimentReport().contains(device.address))
        assertTrue(vm.experimentReport().contains("RAW_CAPTURE"))
    }
    @Test fun realWiringCollectsBothIdentitiesWithAtMostFourCanWrites() {
        val p = Platform(Socket(prompt = false, multiFrame = true)); val model = vm(p)
        assertTrue(model.startExperiment(device, reviewed = true))
        until { !model.experiment.value.busy }
        assertEquals(ExperimentOutcome.OBSERVED, model.experiment.value.result!!.outcome)
        assertEquals(listOf("0322F18700000000", "30020A0000000000", "0322F18700000000", "30020A0000000000"),
            p.socket.writes().filter { !it.startsWith("AT") })
        assertTrue(model.experimentReport().contains("TEST1234567"))
        assertTrue(model.experimentReuseRequired.value)
        assertTrue(p.socket.closed.await(1, TimeUnit.SECONDS))
    }
    @Test fun noInitialPromptStillCompletesFixedReads() {
        val p = Platform(Socket(prompt = false)); val vm = vm(p)
        assertTrue(vm.startExperiment(device, reviewed = true))
        until { !vm.experiment.value.busy }
        assertEquals(ExperimentOutcome.OBSERVED, vm.experiment.value.result!!.outcome)
        assertEquals("ATPC", p.socket.writes().first())
        assertEquals(2, p.socket.writes().count { it == "0322F18700000000" })
        assertTrue(p.socket.closed.await(1, TimeUnit.SECONDS))
        assertEquals(1, p.opens)
    }
    @Test fun explicitReviewAndExclusiveOwnershipAreRequiredAndBackgroundClosesConnect() {
        val p = Platform(Socket(blockConnect = true)); val vm = vm(p)
        assertFalse(vm.startExperiment(device, reviewed = false))
        assertEquals(0, p.opens)
        assertTrue(vm.startExperiment(device, reviewed = true))
        until { p.socket.entered.count == 0L }
        vm.startBench(device); vm.startLive(device)
        assertFalse(vm.startRecording(device))
        assertFalse(vm.startExperiment(device, reviewed = true))
        assertEquals(1, p.opens)
        vm.onAppBackgrounded()
        until { !vm.experiment.value.busy }
        assertTrue(p.socket.closed.await(1, TimeUnit.SECONDS))
        assertTrue(p.socket.writes().isEmpty())
        assertEquals(1, p.opens)
    }
    @Test fun unsettledStartupWithoutWritesDoesNotBlockOlderFunctions() {
        val p = Platform(Socket(initial = "unfinished")); val model = vm(p)
        assertTrue(model.startExperiment(device, reviewed = true))
        until { !model.experiment.value.busy }
        assertEquals(ExperimentOutcome.STOPPED, model.experiment.value.result!!.outcome)
        assertTrue(p.socket.writes().isEmpty())
        assertFalse(model.experimentReuseRequired.value)
        assertFalse(vm(p).experimentReuseRequired.value)
        val normal = Platform(Socket(prompt = false))
        val next = vm(normal)
        next.startBench(device)
        until { normal.socket.closed.count == 0L }
        assertEquals(listOf("ATI", "AT@1", "ATRV"), normal.socket.writes())
    }
    @Test fun exportKeepsReviewedSnapshotAndNeverSuppliesAnEmptyFallback() {
        val model = vm(Platform(Socket()))
        val reviewed = "{\"reviewed\":true}"
        assertTrue(model.prepareExperimentExport(reviewed))
        assertEquals(reviewed, model.takeExperimentExport())
        assertNull(model.takeExperimentExport())
        assertFalse(model.prepareExperimentExport(""))
        assertNull(model.takeExperimentExport())
        // A recreated process has no reviewed snapshot and must not write an empty report.
        assertNull(vm(Platform(Socket())).takeExperimentExport())
    }
    @Test fun reuseGuardSurvivesViewModelRecreationUntilOwnerConfirmsPowerCycle() {
        val p = Platform(Socket(prompt = false)); val first = vm(p)
        assertTrue(first.startExperiment(device, reviewed = true))
        until { !first.experiment.value.busy }
        val next = vm(p)
        assertTrue(next.experimentReuseRequired.value)
        next.startBench(device); next.startLive(device)
        assertFalse(next.startRecording(device))
        assertFalse(next.startExperiment(device, reviewed = true))
        assertEquals(1, p.opens)
        next.confirmExperimentAdapterPowerCycle()
        assertFalse(next.experimentReuseRequired.value)
        assertFalse(vm(p).experimentReuseRequired.value)
    }
    @Test fun existingBenchOwnerPreventsExperimentConnection() {
        val p = Platform(Socket(blockConnect = true)); val vm = vm(p)
        vm.startBench(device)
        until { p.socket.entered.count == 0L }
        assertFalse(vm.startExperiment(device, reviewed = true))
        assertEquals(1, p.opens)
        vm.cancelBench()
        until { p.socket.closed.count == 0L }
    }
    companion object { private val device = BondedDevice("Constructed adapter", "00:11:22:33:44:55") }
}
