package io.github.eternalliquet.bamboobd2.core.manufacturer

import io.github.eternalliquet.bamboobd2.core.vehicle.VehicleEvidence
import io.github.eternalliquet.bamboobd2.core.vehicle.VinRead
import io.github.eternalliquet.bamboobd2.core.vehicle.YearEvidence

/** How the manufacturer was established for this connection. */
sealed interface MakeEvidence {
    /** From the VIN's manufacturer code. */
    data class FromVin(val entry: ManufacturerEntry) : MakeEvidence

    /** Chosen by the user because no VIN was read. */
    data class Manual(val entry: ManufacturerEntry) : MakeEvidence

    data class Unknown(val reason: String) : MakeEvidence

    val entryOrNull: ManufacturerEntry? get() = (this as? FromVin)?.entry ?: (this as? Manual)?.entry
}

/**
 * Everything the app believes about the vehicle on one connection: generic evidence,
 * the manufacturer match, an optional user-confirmed profile, the lazily created
 * service and the resulting capability states. Built fresh for every connection and
 * discarded when it ends.
 */
data class VehicleContext(
    val evidence: VehicleEvidence,
    val make: MakeEvidence,
    /** Confirmed by the user on this connection; never carried over. */
    val profile: VehicleProfile?,
    val service: ManufacturerService?,
    val capabilities: List<CapabilityState>,
) {
    /** Profiles the user may confirm, consistent with the VIN where one was read. */
    fun selectableProfiles(registry: ManufacturerRegistry): List<VehicleProfile> {
        val candidates = when (val m = make) {
            is MakeEvidence.FromVin -> m.entry.profiles
            // A VIN whose manufacturer code is not recognized still rules out the bundled makes.
            else -> if (evidence.vin is VinRead.Read) emptyList() else registry.entries.flatMap { it.profiles }
        }
        return when (val y = evidence.modelYear) {
            is YearEvidence.FromVin -> candidates.filter { y.year in it.years }
            is YearEvidence.Ambiguous -> candidates.filter { p -> y.candidates.any { it in p.years } }
            is YearEvidence.Unknown -> candidates
        }
    }
}
