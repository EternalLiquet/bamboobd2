package io.github.eternalliquet.bamboobd2.core.bench

import io.github.eternalliquet.bamboobd2.core.protocol.PolicyDecision
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayChunk
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayScript
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayStep
import io.github.eternalliquet.bamboobd2.core.protocol.ReplayTransport
import io.github.eternalliquet.bamboobd2.core.protocol.Request
import io.github.eternalliquet.bamboobd2.core.protocol.RequestPolicy
import io.github.eternalliquet.bamboobd2.core.protocol.VehicleRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The adapter-only bench path, over synthetic replay in virtual time. No hardware. */
class AdapterBenchCheckTest {

    private fun step(request: String, vararg chunks: Pair<Long, String>, disconnectAfter: Long? = null) =
        ReplayStep(request, chunks.map { ReplayChunk(it.first, it.second) }, disconnectAfter)

    private fun script(steps: List<ReplayStep>, connected: Boolean = true) =
        ReplayScript("bench", "bench", "SYNTHETIC", "constructed", "built in AdapterBenchCheckTest", connected = connected, steps = steps)

    // Echo is on by default (ELM327DSJ p17), so the first reply repeats the command.
    private val ati = step("ATI", 10L to "ATI\r", 30L to "ELM327 v1.5\r\r>")
    private val at1 = step("AT@1", 10L to "OBDII to RS232 Interpreter\r\r>")
    private val atrv = step("ATRV", 10L to "12.6V\r\r>")

    private fun TestScope.transport(steps: List<ReplayStep>, connected: Boolean = true) =
        ReplayTransport(script(steps, connected), testScheduler.timeSource)

    @Test
    fun sendsOnlyTheThreeAdapterCommandsAndKeepsTheirReplies() = runTest {
        val t = transport(listOf(ati, at1, atrv))
        val r = AdapterBenchCheck(t, timeSource = testScheduler.timeSource).run()
        assertEquals(BenchStop.COMPLETE, r.stop)
        assertEquals(listOf("ATI", "AT@1", "ATRV"), t.writes)
        assertEquals(BenchOutcome.Reply(listOf("ELM327 v1.5")), r.steps[0].outcome)
        assertEquals(2, r.steps[0].chunks)
        assertEquals(30L, r.steps[0].elapsedMillis)
        assertEquals(BenchOutcome.Reply(listOf("12.6V")), r.steps[2].outcome)

        val report = BenchReport.text(r, "0.1.0-poc", "Android 15 (API 35)")
        assertTrue(report, "Phone: Android 15 (API 35)" in report)
        assertTrue(report, "ATI: ELM327 v1.5 [30 ms, 2 chunk(s)]" in report)
        assertTrue(report, "Result: complete" in report)
    }

    @Test
    fun guardRejectsEveryVehicleRequestAndAcceptsAdapterCommands() {
        // Every allowlisted vehicle request, built through the policy as real callers would.
        for (vehicle in VehicleRequest.entries) {
            val request = RequestPolicy.vehicle(vehicle)
            try {
                AdapterBenchCheck.guard(request)
                fail("bench guard accepted ${vehicle.text}")
            } catch (e: IllegalArgumentException) {
                assertTrue(vehicle.text in e.message.orEmpty())
            }
        }
        // And anything else the policy accepts as a vehicle request.
        val accepted = RequestPolicy.allowlist.map { it to RequestPolicy.evaluate(it) }
            .mapNotNull { (_, d) -> (d as? PolicyDecision.Accepted)?.request }
        assertTrue(accepted.any { it.kind == Request.Kind.VEHICLE })
        for (request in accepted) {
            val allowed = runCatching { AdapterBenchCheck.guard(request) }.isSuccess
            assertEquals(request.text, request.kind == Request.Kind.ADAPTER, allowed)
        }
        // The bench commands themselves are adapter commands from the approved list.
        assertTrue(AdapterBenchCheck.COMMANDS.all { it.text in RequestPolicy.approvedAdapterCommands })
    }

    @Test
    fun aMissedDeadlineStopsWithoutSendingMore() = runTest {
        val t = transport(listOf(ati, step("AT@1", 10L to "OBDII"), atrv))
        val r = AdapterBenchCheck(t, timeSource = testScheduler.timeSource).run()
        assertEquals(BenchStop.NO_RESPONSE, r.stop)
        assertEquals(listOf("ATI", "AT@1"), t.writes)
        assertEquals(BenchOutcome.Reply(listOf("ELM327 v1.5")), r.steps[0].outcome)
        assertEquals(BenchOutcome.NoReply("OBDII"), r.steps[1].outcome)
        assertEquals(BenchOutcome.NotRun, r.steps[2].outcome)
    }

    @Test
    fun disconnectKeepsWhatWasReadAndNeverReconnects() = runTest {
        val mid = transport(listOf(ati, step("AT@1", 10L to "OBDII to", disconnectAfter = 20)))
        val r = AdapterBenchCheck(mid, timeSource = testScheduler.timeSource).run()
        assertEquals(BenchStop.DISCONNECTED, r.stop)
        assertEquals(BenchOutcome.Reply(listOf("ELM327 v1.5")), r.steps[0].outcome)
        assertEquals(BenchOutcome.NoReply("OBDII to"), r.steps[1].outcome)
        assertEquals(BenchOutcome.NotRun, r.steps[2].outcome)
        assertEquals(listOf("ATI", "AT@1"), mid.writes)

        val before = AdapterBenchCheck(transport(listOf(ati), connected = false), timeSource = testScheduler.timeSource).run()
        assertEquals(BenchStop.DISCONNECTED, before.stop)
        assertTrue(before.steps.all { it.outcome == BenchOutcome.NotRun })
    }

    @Test
    fun cancellationLeavesThePartialResultReadable() = runTest {
        val t = transport(listOf(ati, step("AT@1"), atrv))
        val check = AdapterBenchCheck(t, timeSource = testScheduler.timeSource)
        val job = async { check.run() }
        advanceTimeBy(500)
        runCurrent()
        job.cancel()
        runCurrent()
        val r = check.partial()
        assertEquals(BenchStop.CANCELLED, r.stop)
        assertEquals(BenchOutcome.Reply(listOf("ELM327 v1.5")), r.steps[0].outcome)
        assertEquals(BenchOutcome.NotRun, r.steps[1].outcome)
        assertFalse("ATRV" in t.writes)
    }
}
