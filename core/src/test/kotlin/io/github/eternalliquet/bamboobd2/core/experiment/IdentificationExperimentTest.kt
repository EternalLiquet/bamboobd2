package io.github.eternalliquet.bamboobd2.core.experiment

import io.github.eternalliquet.bamboobd2.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentificationExperimentTest {
    @Test fun twoFixedRawReadsAndNoOtherVehicleTraffic() = runTest {
        val t = ExperimentScript()
        val r = IdentificationExperiment.run(t, testScheduler.timeSource)
        assertEquals(ExperimentOutcome.OBSERVED, r.outcome)
        assertEquals(listOf("0322F18700000000", "0322F18700000000"), t.writes.filter { !it.startsWith("AT") })
        assertEquals(listOf("ATPC", "ATD", "ATM0", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATD0",
            "ATR1", "ATAT0", "ATST7D", "ATTP6", "ATCAF0", "ATCFC0", "ATCEA", "ATAL",
            "ATSH729", "ATCRA793", "ATBI", "0322F18700000000", "ATPC", "ATCAF0", "ATCFC0", "ATCEA", "ATAL",
            "ATSH717", "ATCRA781", "ATBI", "0322F18700000000"), t.writes)
        assertFalse(t.writes.any { it.startsWith("ATFC") || it == "ATCFC1" })
        assertEquals(1000L, testScheduler.currentTime)
        assertEquals("SYNTHETIC", r.recording.provenance)
        assertTrue(r.explanation.contains("unknown", true))
        assertTrue(RequestPolicy.evaluate("0322F18700000000") is PolicyDecision.Rejected)
    }
    @Test fun firstFrameIsIncompleteAndNoFlowControlFollows() = runTest {
        val t = ExperimentScript("793 10 0A 62 F1 87 41 42 43\r>")
        val r = IdentificationExperiment.run(t, testScheduler.timeSource)
        assertEquals(ExperimentOutcome.OBSERVED, r.outcome)
        assertTrue(r.explanation.contains("incomplete", true))
        assertTrue(r.explanation.contains("10"))
        assertEquals(2, t.writes.count { !it.startsWith("AT") })
    }
    @Test fun noDataAndSupportedNegativesAndPendingPromptPermitB() = runTest {
        for (reply in listOf("NO DATA\r>", "793 03 7F 22 11 00 00 00 00\r>", "793 03 7F 22 31 00 00 00 00\r>", "793 03 7F 22 78 00 00 00 00\r>")) {
            val t = ExperimentScript(reply)
            assertEquals(ExperimentOutcome.OBSERVED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
            assertEquals(2, t.writes.count { !it.startsWith("AT") })
        }
    }
    @Test fun malformedMismatchedDenialAndConsecutiveFrameAbortBeforeB() = runTest {
        for (reply in listOf("793 04 62 F1 89 41 00 00 00\r>", "792 04 62 F1 87 41 00 00 00\r>", "793 03 7F 22 33 00 00 00 00\r>", "793 03 7F 22 22 00 00 00 00\r>", "793 21 41 42 43 44 45 46 47\r>", "793 10 07 62 F1 87 41 42 43\r>", "SEARCHING...\r>", "CAN ERROR\r>", "793 09 62 F1 87 41 00 00 00\r>")) {
            val t = ExperimentScript(reply)
            assertEquals(reply, ExperimentOutcome.STOPPED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
            assertFalse(t.writes.contains("ATSH717"))
            assertEquals("0322F18700000000", t.writes.last())
        }
    }
    @Test fun splitLinesAndEchoAreHandled() = runTest {
        val t = ExperimentScript(split = true, echo = true)
        assertEquals(ExperimentOutcome.OBSERVED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
    }
    @Test fun unsupportedSetupStopsWithoutCleanup() = runTest {
        val t = ExperimentScript(setupReply = "?\r>")
        assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
        assertEquals(listOf("ATPC"), t.writes)
    }
    @Test fun partialWriteIsNeverRetried() = runTest {
        val t = ExperimentScript(failWrite = true)
        val r = IdentificationExperiment.run(t, testScheduler.timeSource)
        assertEquals(ExperimentOutcome.STOPPED, r.outcome)
        assertEquals(listOf("ATPC"), t.writes)
        assertEquals(listOf("ATPC"), r.recording.steps.map { it.request })
    }
    @Test fun emptyChunksAndCapsAbort() = runTest {
        for (reply in listOf("", "x".repeat(65537), "\r".repeat(257))) {
            val t = ExperimentScript(setupReply = reply)
            assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
            assertEquals(listOf("ATPC"), t.writes)
        }
    }
    @Test fun missingPromptStopsAtTwoSecondsAndPendingCannotRestartFiveSeconds() = runTest {
        for ((reply, deadline) in listOf("793 04 62 F1 87 41 00 00 00\r" to 2000L, "793 03 7F 22 78 00 00 00 00\r" to 5000L)) {
            val start = testScheduler.currentTime
            val t = ExperimentScript(reply, stallAfter = true)
            assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
            assertEquals(deadline, testScheduler.currentTime - start)
            assertFalse(t.writes.contains("ATSH717"))
        }
    }
    @Test fun repeatedPendingDoesNotRestartDeadlineAndFinalCanComplete() = runTest {
        val start = testScheduler.currentTime
        val repeating = ExperimentScript("793 03 7F 22 78 00 00 00 00\r", repeatPending = true)
        val result = IdentificationExperiment.run(repeating, testScheduler.timeSource)
        assertEquals(ExperimentOutcome.STOPPED, result.outcome)
        assertTrue(result.explanation.contains("Pending/incomplete"))
        assertEquals(5000L, testScheduler.currentTime - start)
        assertEquals(1, repeating.writes.count { !it.startsWith("AT") })
        val final = ExperimentScript("793 03 7F 22 78 00 00 00 00\r793 04 62 F1 87 41 00 00 00\r>")
        assertEquals(ExperimentOutcome.OBSERVED, IdentificationExperiment.run(final, testScheduler.timeSource).outcome)
    }
    @Test fun cancellationInSettlingGapNeverConfiguresB() = runTest {
        val t = ExperimentScript()
        var result: ExperimentResult? = null
        val job = launch { IdentificationExperiment.run(t, testScheduler.timeSource) { result = it } }
        testScheduler.runCurrent()
        assertEquals(1, t.writes.count { !it.startsWith("AT") })
        job.cancelAndJoin()
        assertEquals(ExperimentOutcome.CANCELLED, result!!.outcome)
        assertFalse(t.writes.contains("ATSH717"))
    }
    @Test fun totalDeadlineIncludesSetupAndSettling() = runTest {
        val t = ExperimentScript(latencyMs = 1999L)
        assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(t, testScheduler.timeSource).outcome)
        assertEquals(60000L, testScheduler.currentTime)
    }
    @Test fun liveAndReplayRefusedBeforeAnyWrite() = runTest {
        for (t in listOf(ExperimentLiveSentinel(), ExperimentReplaySentinel())) {
            try { IdentificationExperiment.run(t); fail("must reject") } catch (_: IllegalArgumentException) { }
            assertEquals(0, if (t is ExperimentLiveSentinel) t.writes else (t as ExperimentReplaySentinel).writes)
        }
    }
    @Test fun cancellationPublishesPartialWithoutMoreWrites() = runTest {
        var last: ExperimentResult? = null
        val t = ExperimentStall()
        val job = launch { IdentificationExperiment.run(t, testScheduler.timeSource) { last = it } }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(ExperimentOutcome.CANCELLED, last!!.outcome)
        assertEquals(listOf("ATPC"), last!!.recording.steps.map { it.request })
        assertEquals(1, t.writes)
    }
    @Test fun callerTimeoutIsCancelledAndSetupDeadlineIsBounded() = runTest {
        var last: ExperimentResult? = null
        withTimeoutOrNull(10) { IdentificationExperiment.run(ExperimentStall(), testScheduler.timeSource) { last = it } }
        assertEquals(ExperimentOutcome.CANCELLED, last!!.outcome)
        val start = testScheduler.currentTime
        assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(ExperimentStall(), testScheduler.timeSource).outcome)
        assertEquals(2000L, testScheduler.currentTime - start)
    }
}

private class ExperimentScript(
    private val replyA: String = "793 04 62 F1 87 41 00 00 00\r>",
    private val setupReply: String = "OK\r>",
    private val split: Boolean = false,
    private val echo: Boolean = false,
    private val failWrite: Boolean = false,
    private val stallAfter: Boolean = false,
    private val repeatPending: Boolean = false,
    private val latencyMs: Long = 0,
) : AdapterTransport {
    override val kind = TransportKind.FAKE
    val writes = mutableListOf<String>()
    private val queue = ArrayDeque<ByteArray>()
    private var reads = 0
    override suspend fun write(request: Request) {
        writes += request.text
        if (failWrite) throw TransportClosedException("uncertain write")
        val body = when {
            request.text == "ATI" -> "ELM327 simulated\r>"
            request.kind == Request.Kind.ADAPTER -> setupReply
            reads++ == 0 -> replyA
            else -> "781 04 62 F1 87 42 00 00 00\r>"
        }
        val text = (if (echo) request.text + "\r\n" else "") + body
        if (split && text.isNotEmpty()) text.forEach { queue.add(byteArrayOf(it.code.toByte())) }
        else queue.add(text.toByteArray())
    }
    override suspend fun read(): ByteArray? {
        delay(latencyMs)
        queue.removeFirstOrNull()?.let { return it }
        if (repeatPending) { delay(1000L); return "793 03 7F 22 78 00 00 00 00\r".toByteArray() }
        return if (stallAfter) awaitCancellation() else null
    }
}
private class ExperimentLiveSentinel : AdapterTransport {
    override val kind = TransportKind.LIVE
    var writes = 0
    override suspend fun write(request: Request) { writes++ }
    override suspend fun read(): ByteArray? = error("Must not read")
}
private class ExperimentStall : AdapterTransport {
    override val kind = TransportKind.FAKE
    var writes = 0
    override suspend fun write(request: Request) { writes++; awaitCancellation() }
    override suspend fun read(): ByteArray? = awaitCancellation()
}

private class ExperimentReplaySentinel : AdapterTransport {
    override val kind = TransportKind.REPLAY
    var writes = 0
    override suspend fun write(request: Request) { writes++ }
    override suspend fun read(): ByteArray? = error("Must not read")
}
