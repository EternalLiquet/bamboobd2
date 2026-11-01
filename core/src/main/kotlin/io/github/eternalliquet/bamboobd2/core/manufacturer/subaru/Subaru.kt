package io.github.eternalliquet.bamboobd2.core.manufacturer.subaru

import io.github.eternalliquet.bamboobd2.core.manufacturer.CapabilityDefinition
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerEntry
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerService
import io.github.eternalliquet.bamboobd2.core.manufacturer.VehicleProfile

/**
 * Subaru recognition data and service. WMIs JF1, JF2 (Japan) and 4S3, 4S4 (Indiana)
 * are public ISO 3780 assignments. BRZ generations by model year.
 */
object Subaru {
    const val MAKE = "Subaru"

    val BRZ_ZC6 = VehicleProfile("subaru.brz.zc6", MAKE, "BRZ (ZC6, first generation)", 2013..2020)
    val BRZ_ZD8 = VehicleProfile("subaru.brz.zd8", MAKE, "BRZ (ZD8, second generation)", 2022..2099)

    val entry = ManufacturerEntry(MAKE, setOf("JF1", "JF2", "4S3", "4S4"), listOf(BRZ_ZC6, BRZ_ZD8)) { SubaruService() }
}

/** No Subaru-specific feature is implemented; generic OBD covers these cars today. */
class SubaruService : ManufacturerService {
    override val make = Subaru.MAKE
    override val summary = "No Subaru-specific features yet. Generic OBD scans work for this car."
    override val capabilities = emptyList<CapabilityDefinition>()
}
