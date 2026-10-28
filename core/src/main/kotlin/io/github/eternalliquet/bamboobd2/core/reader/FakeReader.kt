package io.github.eternalliquet.bamboobd2.core.reader

import io.github.eternalliquet.bamboobd2.core.model.Dtc
import io.github.eternalliquet.bamboobd2.core.model.DtcKind
import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.ReadingResult
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import kotlinx.coroutines.delay

/** Scripted starter scenarios. The values are invented and describe no real vehicle. */
enum class FakeScenario(val label: String) {
    NO_CODES("No codes"),
    TWO_CODES("Two codes"),
    UNSUPPORTED_PID("Unsupported reading"),
}

/**
 * Simulated reader. Each step waits [stepDelayMillis] (virtual time in tests) so a
 * session can be cancelled part-way through.
 */
class FakeReader(
    private val scenario: FakeScenario,
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
) : DiagnosticReader {

    override val provenance: Provenance = Provenance.SIMULATED
    override val sourceLabel: String = "fake:${scenario.name.lowercase()}"

    override suspend fun readSupportedPids(): SupportedPids {
        delay(stepDelayMillis)
        val all = ObdPid.entries.map { it.code }
        return SupportedPids.Known(
            if (scenario == FakeScenario.UNSUPPORTED_PID) all - ObdPid.VEHICLE_SPEED.code else all,
        )
    }

    override suspend fun readDtcs(kind: DtcKind): List<Dtc> {
        delay(stepDelayMillis)
        if (scenario != FakeScenario.TWO_CODES) return emptyList()
        return when (kind) {
            DtcKind.STORED -> listOf(Dtc("P0133", DtcKind.STORED))
            DtcKind.PENDING -> listOf(Dtc("P0171", DtcKind.PENDING))
        }
    }

    override suspend fun readPid(pid: String): ReadingResult {
        delay(stepDelayMillis)
        return when (pid) {
            ObdPid.ENGINE_LOAD.code -> ReadingResult.Value(50.2, ObdPid.ENGINE_LOAD.unit)
            ObdPid.COOLANT_TEMP.code -> ReadingResult.Value(83.0, ObdPid.COOLANT_TEMP.unit)
            ObdPid.ENGINE_RPM.code -> ReadingResult.Value(1726.0, ObdPid.ENGINE_RPM.unit)
            ObdPid.VEHICLE_SPEED.code -> ReadingResult.Value(0.0, ObdPid.VEHICLE_SPEED.unit)
            else -> ReadingResult.UnknownCapability
        }
    }

    companion object {
        const val DEFAULT_STEP_DELAY_MILLIS = 400L
    }
}
