package io.github.eternalliquet.bamboobd2.core.manufacturer

import io.github.eternalliquet.bamboobd2.core.manufacturer.porsche.Porsche
import io.github.eternalliquet.bamboobd2.core.manufacturer.porsche.PorscheService
import io.github.eternalliquet.bamboobd2.core.manufacturer.subaru.Subaru
import io.github.eternalliquet.bamboobd2.core.manufacturer.subaru.SubaruService
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.vehicle.VehicleEvidence
import io.github.eternalliquet.bamboobd2.core.vehicle.Vin
import io.github.eternalliquet.bamboobd2.core.vehicle.VinRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection and capability gating from constructed evidence. No transport exists here at all. */
class ManufacturerRegistryTest {
    private var porscheCreated = 0
    private var subaruCreated = 0
    private val registry = ManufacturerRegistry(listOf(
        ManufacturerEntry(Porsche.MAKE, Porsche.entry.wmis, Porsche.entry.profiles) { porscheCreated++; PorscheService() },
        ManufacturerEntry(Subaru.MAKE, Subaru.entry.wmis, Subaru.entry.profiles) { subaruCreated++; SubaruService() },
    ))

    private fun evidence(vin: String?) = VehicleEvidence("6: CAN", SupportedPids.Known(emptyList()),
        vin?.let { VinRead.Read(Vin.parse(it)!!, listOf("7E8")) } ?: VinRead.NotAvailable("no responder advertises the VIN"))

    private fun VehicleContext.status(id: String) = capabilities.single { it.definition.id == id }.status

    @Test
    fun onlyTheMatchingServiceIsCreatedAndOnlyOncePerConnection() {
        val services = mutableMapOf<String, ManufacturerService>()
        val first = registry.resolve(evidence(CAYENNE), services = services)
        val confirmed = registry.resolve(evidence(CAYENNE), Porsche.CAYENNE_92A.id, services)
        assertEquals(1, porscheCreated)
        assertEquals(0, subaruCreated)
        assertTrue(first.service === confirmed.service)

        // A new connection (new cache) to the BRZ never sees the Porsche service.
        val brz = registry.resolve(evidence(BRZ), services = mutableMapOf())
        assertEquals(Subaru.MAKE, brz.service!!.make)
        assertTrue(brz.capabilities.isEmpty())
        assertEquals(1, porscheCreated)
        assertEquals(1, subaruCreated)
    }

    @Test
    fun vinDecidesTheMakeAndManualChoiceCannotOverrideIt() {
        val brz = registry.resolve(evidence(BRZ), Porsche.CAYENNE_92A.id)
        assertEquals(Subaru.MAKE, brz.make.entryOrNull!!.make)
        assertNull(brz.profile)
        assertEquals(0, porscheCreated)
        // Without established year-rule applicability, either generation may be confirmed.
        assertEquals(Subaru.entry.profiles, brz.selectableProfiles(registry))
    }

    @Test
    fun unrecognisedManufacturerCodeStaysGenericOnly() {
        val other = registry.resolve(evidence("1HGCM82633A000001"), Porsche.CAYENNE_92A.id)
        assertTrue(other.make is MakeEvidence.Unknown)
        assertNull(other.service)
        assertTrue(other.selectableProfiles(registry).isEmpty())
        assertEquals(0, porscheCreated + subaruCreated)
    }

    @Test
    fun capabilitiesNeedConfirmationAndModulesCannotBeWaived() {
        val unconfirmed = registry.resolve(evidence(CAYENNE))
        assertEquals(CapabilityStatus.NEEDS_CONFIRMATION, unconfirmed.status(EXPERIMENT))

        val confirmed = registry.resolve(evidence(CAYENNE), Porsche.CAYENNE_92A.id)
        assertEquals(CapabilityStatus.RESEARCH_ONLY, confirmed.status(EXPERIMENT))
        // Oil, intermediate and main inspection stay separate and unavailable.
        for (id in listOf("oil-service-read", "intermediate-inspection-read", "main-inspection-read"))
            assertEquals(CapabilityStatus.UNAVAILABLE, confirmed.status("porsche.cayenne92a.$id"))
        assertEquals(CapabilityStatus.UNAVAILABLE, confirmed.status("porsche.service-reset"))
        assertTrue(confirmed.capabilities.none { it.status == CapabilityStatus.RESEARCH_ONLY && it.definition.id != EXPERIMENT })

        // Without a VIN the user may choose the make; it is marked manual and the wrong generation is refused.
        val manual = registry.resolve(evidence(null), Porsche.CAYENNE_9YA.id)
        assertTrue(manual.make is MakeEvidence.Manual)
        assertEquals(CapabilityStatus.UNAVAILABLE, manual.status(EXPERIMENT))
        // With no VIN and no choice, nothing is selected and no service exists.
        val unknown = registry.resolve(evidence(null))
        assertNull(unknown.service)
        assertEquals(5, unknown.selectableProfiles(registry).size)
    }

    private companion object {
        const val CAYENNE = "WP1AA2A2XELA00001"
        const val BRZ = "JF1ZCAC1XD1600001"
        const val EXPERIMENT = "porsche.cayenne92a.part-number-experiment"
    }
}
