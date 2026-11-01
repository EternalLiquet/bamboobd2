package io.github.eternalliquet.bamboobd2.core.manufacturer

import io.github.eternalliquet.bamboobd2.core.manufacturer.porsche.Porsche
import io.github.eternalliquet.bamboobd2.core.manufacturer.subaru.Subaru
import io.github.eternalliquet.bamboobd2.core.vehicle.VehicleEvidence
import io.github.eternalliquet.bamboobd2.core.vehicle.VinRead

/**
 * The bundled manufacturers and the rules that turn connection evidence into a
 * [VehicleContext]. Selection uses only the VIN read on this connection or the user's
 * explicit choice; never an adapter name, a responder ID or an earlier connection.
 */
class ManufacturerRegistry(val entries: List<ManufacturerEntry>) {

    init {
        require(entries.map { it.make }.distinct().size == entries.size) { "duplicate make" }
        require(entries.flatMap { it.wmis }.let { it.distinct().size == it.size }) { "WMI mapped twice" }
    }

    /**
     * Builds the context for fresh evidence, with an optional manual make (honoured only
     * when no VIN was read) and profile (honoured only when it is selectable).
     * [services] caches services already created on this connection, so re-resolving
     * after a confirmation does not construct a second one.
     */
    fun resolve(
        evidence: VehicleEvidence,
        manualProfileId: String? = null,
        services: MutableMap<String, ManufacturerService> = mutableMapOf(),
    ): VehicleContext {
        val make = makeFor(evidence, manualProfileId)
        val base = VehicleContext(evidence, make, null, null, emptyList())
        val profile = manualProfileId?.let { id -> base.selectableProfiles(this).firstOrNull { it.id == id } }
            ?.takeIf { make.entryOrNull?.make == it.make }
        val service = make.entryOrNull?.let { entry -> services.getOrPut(entry.make) { entry.create() } }
        val context = base.copy(profile = profile, service = service)
        return context.copy(capabilities = service?.capabilities.orEmpty().map { CapabilityEvaluator.evaluate(it, context) })
    }

    private fun makeFor(evidence: VehicleEvidence, manualProfileId: String?): MakeEvidence = when (val vin = evidence.vin) {
        is VinRead.Read -> entries.firstOrNull { vin.vin.wmi in it.wmis }?.let { MakeEvidence.FromVin(it) }
            ?: MakeEvidence.Unknown("manufacturer code ${vin.vin.wmi} is not one this app supports")
        else -> manualProfileId?.let { id -> entries.firstOrNull { e -> e.profiles.any { it.id == id } } }
            ?.let { MakeEvidence.Manual(it) }
            ?: MakeEvidence.Unknown(when (vin) {
                is VinRead.Conflict -> "responders returned different VINs"
                is VinRead.NotAvailable -> "VIN not read: ${vin.reason}"
                is VinRead.Read -> error("unreachable")
            })
    }

    companion object {
        /** Everything shipped in this APK. Creating the registry creates no service. */
        val BUNDLED = ManufacturerRegistry(listOf(Porsche.entry, Subaru.entry))
    }
}

/** Applies a capability's requirements to one connection's context. */
object CapabilityEvaluator {
    fun evaluate(definition: CapabilityDefinition, context: VehicleContext): CapabilityState {
        val impl = definition.implementation
        if (impl is Implementation.None) return CapabilityState(definition, CapabilityStatus.UNAVAILABLE, impl.reason)
        for (requirement in definition.requirements) {
            when (requirement) {
                is Requirement.Make -> if (context.make.entryOrNull?.make != requirement.make)
                    return CapabilityState(definition, CapabilityStatus.UNAVAILABLE, "Needs a ${requirement.make}.")
                is Requirement.Module -> return CapabilityState(definition, CapabilityStatus.UNAVAILABLE,
                    "Needs ${requirement.description}, which this app cannot identify yet.")
                is Requirement.Profile -> {
                    val profile = context.profile
                    if (profile == null) return CapabilityState(definition, CapabilityStatus.NEEDS_CONFIRMATION, "Confirm the model to check this.")
                    if (profile.id !in requirement.profileIds)
                        return CapabilityState(definition, CapabilityStatus.UNAVAILABLE, "Not prepared for the ${profile.name}.")
                }
            }
        }
        return when (impl) {
            is Implementation.ResearchExperiment -> CapabilityState(definition, CapabilityStatus.RESEARCH_ONLY, impl.summary)
            is Implementation.None -> error("handled above")
        }
    }
}
