package io.github.eternalliquet.bamboobd2.core.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterHypothesesTest {
    private val kmOil = FieldSpec("synthetic-oil-distance", 16, CounterUnit.KILOMETERS, ServiceCategory.OIL)
    private val dayOil = FieldSpec("synthetic-oil-days", 16, CounterUnit.DAYS, ServiceCategory.OIL)

    private fun observation(bytes: List<Int>, position: Long, field: FieldSpec = kmOil) =
        CounterObservation(field, bytes, position)

    private fun candidate(
        id: String,
        meaning: CounterMeaning = CounterMeaning.REMAINING,
        order: CounterOrder = CounterOrder.BIG_ENDIAN,
        signed: Boolean = false,
        scale: Int = 1,
        interval: DeclaredInterval? = null,
        allOnes: AllOnesMeaning = AllOnesMeaning.UNSPECIFIED,
    ) = CounterHypothesis(id, order, signed, scale, meaning, interval, allOnes)

    private fun report(observations: List<CounterObservation>, candidates: List<CounterHypothesis>,
                       applicability: ApplicabilityNote = ApplicabilityNote.UNKNOWN) =
        evaluateCounterHypotheses(observations, candidates, applicability)

    private fun countdownCandidates() = listOf(
        candidate("remaining"),
        candidate("elapsed", CounterMeaning.ELAPSED, interval = DeclaredInterval(200, CounterUnit.KILOMETERS, ServiceCategory.OIL)),
        candidate("due", CounterMeaning.DUE_AT),
        candidate("last", CounterMeaning.LAST_SERVICE_PLUS_INTERVAL,
            interval = DeclaredInterval(200, CounterUnit.KILOMETERS, ServiceCategory.OIL)),
    )

    @Test fun singleObservationKeepsEndianAndSignAlternatives() {
        val result = report(listOf(observation(listOf(1, 2), 1000)), listOf(
            candidate("be-u"), candidate("be-s", signed = true),
            candidate("le-u", order = CounterOrder.LITTLE_ENDIAN),
            candidate("le-s", order = CounterOrder.LITTLE_ENDIAN, signed = true),
        ))
        assertEquals(listOf("be-u", "be-s", "le-u", "le-s"), result.fits.map { it.hypothesisId })
        assertEquals(listOf(listOf(258L), listOf(258L), listOf(513L), listOf(513L)), result.fits.map { it.remaining })
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun declaredScaleIsNotChosenByPlausibility() {
        val result = report(listOf(observation(listOf(0, 2), 1000)), listOf(
            candidate("one"), candidate("hundred", scale = 100)))
        assertEquals(listOf(listOf(2L), listOf(200L)), result.fits.map { it.remaining })
        assertTrue(result.rejections.isEmpty())
    }

    @Test fun distanceChangeSeparatesCountdownFromElapsedAndAnchors() {
        val result = report(listOf(observation(listOf(0, 100), 1000), observation(listOf(0, 90), 1010)), countdownCandidates())
        assertEquals(listOf(CounterFit("remaining", listOf(100L, 90L))), result.fits)
        assertEquals(listOf("elapsed", "due", "last"), result.rejections.map { it.hypothesisId })
    }

    @Test fun elapsedCounterNeedsItsDeclaredInterval() {
        val result = report(listOf(observation(listOf(0, 100), 1000), observation(listOf(0, 110), 1010)), listOf(
            candidate("with", CounterMeaning.ELAPSED, interval = DeclaredInterval(200, CounterUnit.KILOMETERS, ServiceCategory.OIL)),
            candidate("without", CounterMeaning.ELAPSED),
        ))
        assertEquals(listOf(CounterFit("with", listOf(100L, 90L))), result.fits)
        assertEquals(listOf("without"), result.rejections.map { it.hypothesisId })
        assertTrue(result.rejections.single().reason.contains("interval", ignoreCase = true))
    }

    @Test fun constantAnchorsDoNotDistinguishDueAtFromLastService() {
        val observations = listOf(observation(listOf(4, 76), 1000), observation(listOf(4, 76), 1010))
        val result = report(observations, listOf(candidate("due", CounterMeaning.DUE_AT),
            candidate("last", CounterMeaning.LAST_SERVICE_PLUS_INTERVAL,
                interval = DeclaredInterval(200, CounterUnit.KILOMETERS, ServiceCategory.OIL))))
        assertEquals(listOf(CounterFit("due", listOf(100L, 90L)),
            CounterFit("last", listOf(300L, 290L))), result.fits)
    }

    @Test fun equivalentSyntheticHistoriesNeedFieldMeaningEvidence() {
        val last = report(listOf(observation(listOf(162, 28), 48300), observation(listOf(162, 28), 48350)),
            listOf(candidate("last", CounterMeaning.LAST_SERVICE_PLUS_INTERVAL,
                interval = DeclaredInterval(15000, CounterUnit.KILOMETERS, ServiceCategory.OIL))))
        val due = report(listOf(observation(listOf(220, 180), 48300), observation(listOf(220, 180), 48350)),
            listOf(candidate("due", CounterMeaning.DUE_AT)))
        assertEquals(listOf(8200L, 8150L), last.fits.single().remaining)
        assertEquals(last.fits.single().remaining, due.fits.single().remaining)
    }

    @Test fun signBoundaryRejectsOnlyTheContradictedInterpretation() {
        val interval = DeclaredInterval(50000, CounterUnit.KILOMETERS, ServiceCategory.OIL)
        val result = report(listOf(observation(listOf(127, 255), 1000), observation(listOf(128, 0), 1001)), listOf(
            candidate("unsigned", CounterMeaning.ELAPSED, interval = interval),
            candidate("signed", CounterMeaning.ELAPSED, signed = true, interval = interval)))
        assertEquals(listOf(CounterFit("unsigned", listOf(17233L, 17232L))), result.fits)
        assertEquals(listOf("signed"), result.rejections.map { it.hypothesisId })
    }

    @Test fun allOnesNeedsAnExplicitSyntheticMeaning() {
        val result = report(listOf(observation(listOf(255, 255), 1000)), listOf(
            candidate("signed", signed = true, allOnes = AllOnesMeaning.NUMERIC),
            candidate("unsigned", allOnes = AllOnesMeaning.NUMERIC),
            candidate("unspecified"), candidate("unavailable", allOnes = AllOnesMeaning.UNAVAILABLE)))
        assertEquals(listOf(CounterFit("signed", listOf(-1L)), CounterFit("unsigned", listOf(65535L))), result.fits)
        assertEquals(listOf("unspecified", "unavailable"), result.rejections.map { it.hypothesisId })
        assertNotEquals(result.rejections[0].reason, result.rejections[1].reason)
    }

    @Test fun dayAndDistanceCasesRemainSeparate() {
        val days = listOf(observation(listOf(0, 100), 2000, dayOil), observation(listOf(0, 90), 2010, dayOil))
        assertEquals(listOf(100L, 90L), report(days, listOf(candidate("days"))).fits.single().remaining)
        assertThrows(IllegalArgumentException::class.java) {
            report(listOf(days[0], observation(listOf(0, 90), 2010)), listOf(candidate("mixed")))
        }
        val mismatch = report(days, listOf(candidate("wrong-unit", CounterMeaning.ELAPSED,
            interval = DeclaredInterval(200, CounterUnit.KILOMETERS, ServiceCategory.OIL))))
        assertTrue(mismatch.fits.isEmpty())
        assertEquals(listOf("wrong-unit"), mismatch.rejections.map { it.hypothesisId })
    }

    @Test fun invalidObservationMetadataProducesNoResult() {
        val valid = observation(listOf(0, 1), 1000)
        val invalid = listOf(
            listOf(valid.copy(field = kmOil.copy(unit = null))),
            listOf(valid.copy(field = kmOil.copy(category = null))),
            listOf(valid.copy(field = kmOil.copy(widthBits = null))),
            listOf(valid.copy(field = kmOil.copy(widthBits = 8))),
            listOf(valid.copy(field = kmOil.copy(widthBits = 32))),
            listOf(valid.copy(field = kmOil.copy(fieldKey = " "))),
            listOf(valid, valid.copy(field = kmOil.copy(fieldKey = "other"))),
            listOf(valid, valid.copy(field = kmOil.copy(category = ServiceCategory.MAIN))),
            listOf(valid.copy(bytes = listOf(0))),
            listOf(valid.copy(bytes = listOf(0, 1, 2))),
            listOf(valid.copy(bytes = listOf(-1, 1))),
            listOf(valid.copy(bytes = listOf(0, 256))),
            emptyList(),
            List(17) { valid },
            listOf(valid.copy(position = -1)),
            listOf(valid.copy(position = 1_000_000_001)),
        )
        invalid.forEachIndexed { index, observations ->
            assertThrows("invalid observation case $index", IllegalArgumentException::class.java) {
                report(observations, listOf(candidate("valid")))
            }
        }
    }

    @Test fun invalidCandidatesDoNotInventAContext() {
        val observations = listOf(observation(listOf(0, 1), 1000))
        listOf(emptyList(), List(33) { candidate("id-$it") },
            listOf(candidate("duplicate"), candidate("duplicate")), listOf(candidate(" "))).forEach { candidates ->
            assertThrows(IllegalArgumentException::class.java) { report(observations, candidates) }
        }
        val invalid = listOf(
            candidate("zero", scale = 0), candidate("two", scale = 2),
            candidate("missing", CounterMeaning.LAST_SERVICE_PLUS_INTERVAL),
            candidate("interval-zero", CounterMeaning.ELAPSED,
                interval = DeclaredInterval(0, CounterUnit.KILOMETERS, ServiceCategory.OIL)),
            candidate("interval-high", CounterMeaning.ELAPSED,
                interval = DeclaredInterval(1_000_000_001, CounterUnit.KILOMETERS, ServiceCategory.OIL)),
            candidate("wrong-category", CounterMeaning.ELAPSED,
                interval = DeclaredInterval(200, CounterUnit.KILOMETERS, ServiceCategory.MAIN)),
        )
        val result = report(observations, invalid)
        assertTrue(result.fits.isEmpty())
        assertEquals(invalid.map { it.id }, result.rejections.map { it.hypothesisId })
    }

    @Test fun uniqueFitDoesNotPromoteApplicability() {
        val observations = listOf(observation(listOf(0, 100), 1000), observation(listOf(0, 90), 1010))
        for (note in ApplicabilityNote.entries) {
            val result = report(observations, countdownCandidates(), note)
            assertEquals(listOf("remaining"), result.fits.map { it.hypothesisId })
            assertEquals(note, result.applicability)
        }
    }
}
