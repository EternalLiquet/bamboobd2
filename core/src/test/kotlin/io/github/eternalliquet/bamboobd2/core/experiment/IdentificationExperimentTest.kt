package io.github.eternalliquet.bamboobd2.core.experiment

import io.github.eternalliquet.bamboobd2.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IdentificationExperimentTest {
    @Test fun fixedDemonstrationPreservesCommandsAndDoesNotEstablishClusterIdentity() = runTest {
        val transport = FakeTransport(IdentificationExperiment.syntheticReplies())
        val result = IdentificationExperiment.run(transport)
        assertEquals(ExperimentOutcome.OBSERVED, result.outcome)
        assertEquals(IdentificationExperiment.commands, transport.writes.map { it.text })
        assertEquals("SYNTHETIC", result.recording.provenance)
        assertTrue(result.cleanupConfirmed)
        assertTrue(result.explanation.contains("unknown", true))
        assertTrue(RequestPolicy.evaluate("22F187") is PolicyDecision.Rejected)
    }

    @Test fun noDataPreventsSecondReadAndAttemptsOnlyCleanup() = runTest {
        val replies = IdentificationExperiment.syntheticReplies() + ("22F187" to FakeTransport.text("NO DATA\r>"))
        val transport = FakeTransport(replies)
        val result = IdentificationExperiment.run(transport)
        assertEquals(ExperimentOutcome.STOPPED, result.outcome)
        assertFalse(transport.writes.any { it.text == "22F189" })
        assertEquals("ATD", transport.writes.last().text)
    }

    @Test fun wrongIdentifierOrResponderCannotAdvance() = runTest {
        for (reply in listOf("793 04 62 F1 89 41\r>", "792 04 62 F1 87 41\r>", "793 03 7F 22 31\r>", "793 03 62 F1 87\r>")) {
            val transport = FakeTransport(IdentificationExperiment.syntheticReplies() + ("22F187" to FakeTransport.text(reply)))
            assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(transport).outcome)
            assertFalse(transport.writes.any { it.text == "22F189" })
        }
    }

    @Test fun pendingDoesNotCauseRetransmissionAndNeedsFinalResponse() = runTest {
        val transport = FakeTransport(IdentificationExperiment.syntheticReplies() + ("22F187" to FakeTransport.text("793 03 7F 22 78\r793 04 62 F1 87 41\r>")))
        assertEquals(ExperimentOutcome.OBSERVED, IdentificationExperiment.run(transport).outcome)
        assertEquals(1, transport.writes.count { it.text == "22F187" })
    }

    @Test fun unsupportedSetupNeverSendsVehicleRequest() = runTest {
        val transport = FakeTransport(IdentificationExperiment.syntheticReplies() + ("ATD" to FakeTransport.text("?\r>")))
        assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(transport).outcome)
        assertFalse(transport.writes.any { it.kind == Request.Kind.VEHICLE })
        assertEquals(listOf("ATD"), transport.writes.map { it.text })
    }
    @Test fun unrequestedSearchOrBusInitializationStopsManufacturerSequence() = runTest {
        for (prefix in listOf("SEARCHING...", "BUS INIT...")) {
            val transport = FakeTransport(IdentificationExperiment.syntheticReplies() +
                ("22F187" to FakeTransport.text("$prefix\r793 04 62 F1 87 41\r>")))
            assertEquals(ExperimentOutcome.STOPPED, IdentificationExperiment.run(transport).outcome)
            assertFalse(transport.writes.any { it.text == "22F189" })
        }
    }

    @Test fun emptyChunksCannotCreateUnboundedTranscriptOrBusyLoop() = runTest {
        val transport = FakeTransport(IdentificationExperiment.syntheticReplies() + ("ATD" to listOf(byteArrayOf())))
        val result = IdentificationExperiment.run(transport)
        assertEquals(ExperimentOutcome.STOPPED, result.outcome)
        assertTrue(result.explanation.contains("empty chunk", true))
        assertEquals(0, result.recording.steps.single().chunks.size)
    }

    @Test fun liveTransportIsRejectedBeforeAnyWrite() = runTest {
        val transport = ExperimentLiveSentinel()
        try {
            IdentificationExperiment.run(transport)
            fail("LIVE must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, transport.writes)
    }

    @Test fun cancellationRetainsPartialCaptureWithoutCleanup() = runTest {
        var last: ExperimentResult? = null
        val transport = ExperimentStall()
        val job = launch {
            IdentificationExperiment.run(transport, testScheduler.timeSource) { last = it }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(ExperimentOutcome.CANCELLED, last!!.outcome)
        assertEquals(listOf("ATD"), last!!.recording.steps.map { it.request })
        assertFalse(last!!.cleanupConfirmed)
        assertEquals(1, transport.writes)
    }

    @Test fun callerTimeoutPublishesCancelledAndPreservesNoCleanupBoundary() = runTest {
        var last: ExperimentResult? = null
        withTimeoutOrNull(10) {
            IdentificationExperiment.run(ExperimentStall(), testScheduler.timeSource) { last = it }
        }
        assertEquals(ExperimentOutcome.CANCELLED, last!!.outcome)
        assertFalse(last!!.cleanupConfirmed)
    }

    @Test fun stalledSetupHasBoundedDeadline() = runTest {
        val transport = ExperimentStall()
        val result = IdentificationExperiment.run(transport, testScheduler.timeSource)
        assertEquals(ExperimentOutcome.STOPPED, result.outcome)
        assertFalse(result.cleanupConfirmed)
        assertEquals(1, transport.writes)
        assertEquals(2000L, testScheduler.currentTime)
    }

}


/** Test sentinel only: cannot connect to any device. */
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
