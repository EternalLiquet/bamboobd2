package io.github.eternalliquet.bamboobd2.app.live

import io.github.eternalliquet.bamboobd2.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RecordingSeriesTest {
    private fun sample(n: Long, t: Long, ecu: String = "7E8", outcome: LiveSampleOutcome = LiveSampleOutcome.VALUE,
        value: Double? = n.toDouble(), unit: String = "°C", at: Long = 1000) =
        LiveSample(n, at, t, "05", ecu, unit, outcome, if (outcome == LiveSampleOutcome.VALUE) value else null)

    @Test fun separateRespondersGapsAndFiniteExtrema() {
        val samples = listOf(sample(1, 0, value = -4.0), sample(2, 0, "7E9", value = 9.0),
            sample(3, 1000, outcome = LiveSampleOutcome.MISSING), sample(4, 2000, value = -2.0),
            sample(5, 8001, value = 3.0), sample(6, 8001, "7E9", outcome = LiveSampleOutcome.FAILURE),
            sample(7, 9000, value = 20.0, unit = "F"))
        val projected = recordingSeries(samples, listOf(LiveEvent(3000, LiveEventKind.PAUSE), LiveEvent(4000, LiveEventKind.RESUME)), 10000, null)
        assertEquals(3, projected.size)
        val c = projected.first { it.key.ecu == "7E8" && it.key.unit == "°C" }
        assertEquals(-4.0, c.minimum)
        assertEquals(3.0, c.maximum)
        assertEquals(listOf(1, 1, 1), c.segments.map { it.size })
        assertEquals(2, projected.first { it.key.ecu == "7E9" }.history.size)
        assertEquals(1, projected.first { it.key.unit == "F" }.segments.single().size)
    }

    @Test fun windowAndAllMissingHaveNoInventedPoints() {
        val samples = listOf(sample(1, 0, outcome = LiveSampleOutcome.MISSING), sample(2, 1000, value = -3.0), sample(3, 61000, value = 4.0))
        val recent = recordingSeries(samples, emptyList(), 61000, 60000).single()
        assertEquals(listOf(2L, 3L), recent.history.map { it.sequence })
        assertEquals(listOf(1, 1), recent.segments.map { it.size })
        assertEquals(-3.0, recent.minimum)
        val missing = recordingSeries(samples.take(1), emptyList(), 1000, null).single()
        assertTrue(missing.segments.isEmpty())
        assertNull(missing.minimum)
    }
}
