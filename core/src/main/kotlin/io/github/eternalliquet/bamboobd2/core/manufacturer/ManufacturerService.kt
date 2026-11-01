package io.github.eternalliquet.bamboobd2.core.manufacturer

/**
 * A model line/generation that a manufacturer entry can recognize. Selecting one is
 * always a confirmation by the user: a VIN gives the manufacturer and model year here,
 * never the model. Years are model years.
 */
data class VehicleProfile(
    val id: String,
    val make: String,
    /** Short name shown to the user, e.g. "Cayenne (92A)". */
    val name: String,
    val years: IntRange,
)

/** What a capability needs before it can be offered. */
sealed interface Requirement {
    /** The manufacturer, from the VIN or the user's choice when no VIN was read. */
    data class Make(val make: String) : Requirement

    /** One of these model profiles, confirmed by the user for this connection. */
    data class Profile(val profileIds: Set<String>) : Requirement

    /**
     * Evidence identifying a specific module and its hardware/software variant. This
     * app collects no such evidence yet, so the requirement is never met and the
     * capability stays unavailable. Manual selection cannot satisfy it.
     */
    data class Module(val description: String) : Requirement
}

/** Whether, and how far, the app implements a capability. */
sealed interface Implementation {
    /** Not implemented; the reason is shown to the user. */
    data class None(val reason: String) : Implementation

    /** A separate, owner-reviewed research experiment with its own explicit start. */
    data class ResearchExperiment(val experimentId: String, val summary: String) : Implementation
}

/** Static description of one manufacturer feature. */
data class CapabilityDefinition(
    val id: String,
    val title: String,
    val requirements: List<Requirement>,
    val implementation: Implementation,
    /** Where the definition comes from and how far it is verified. */
    val source: String,
)

enum class CapabilityStatus {
    /** Requirements met and an experiment exists; it is research, not a supported feature. */
    RESEARCH_ONLY,

    /** Could apply once the user confirms the model. */
    NEEDS_CONFIRMATION,

    /** Not available for this vehicle or not implemented; the reason says which. */
    UNAVAILABLE,
}

data class CapabilityState(val definition: CapabilityDefinition, val status: CapabilityStatus, val reason: String)

/**
 * Manufacturer-specific knowledge for one make. Services are bundled in the APK and
 * created only when the current connection's evidence selects the make.
 *
 * A service holds no transport and constructing it sends nothing. Loading a service is
 * not permission to send any command: a future supported operation needs its own
 * reviewed request policy and must run through the connection's single owner.
 */
interface ManufacturerService {
    val make: String
    val capabilities: List<CapabilityDefinition>

    /** One plain sentence describing what this make offers today. */
    val summary: String
}

/**
 * A bundled manufacturer: lightweight recognition data used before any service exists,
 * and a factory that is invoked only when that make is selected.
 */
class ManufacturerEntry(
    val make: String,
    /** World manufacturer identifiers (ISO 3780) this app maps to [make]. */
    val wmis: Set<String>,
    val profiles: List<VehicleProfile>,
    private val factory: () -> ManufacturerService,
) {
    internal fun create(): ManufacturerService = factory().also { check(it.make == make) { "factory returned ${it.make}" } }
}
