package io.github.eternalliquet.bamboobd2.core.manufacturer.porsche

import io.github.eternalliquet.bamboobd2.core.experiment.IdentificationExperiment
import io.github.eternalliquet.bamboobd2.core.manufacturer.CapabilityDefinition
import io.github.eternalliquet.bamboobd2.core.manufacturer.Implementation
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerEntry
import io.github.eternalliquet.bamboobd2.core.manufacturer.ManufacturerService
import io.github.eternalliquet.bamboobd2.core.manufacturer.Requirement
import io.github.eternalliquet.bamboobd2.core.manufacturer.VehicleProfile

/**
 * Porsche recognition data and service. WMIs WP0 (cars) and WP1 (SUVs) are public
 * ISO 3780 assignments. Profile years are model years for each Cayenne generation.
 */
object Porsche {
    const val MAKE = "Porsche"

    val CAYENNE_9PA = VehicleProfile("porsche.cayenne.9pa", MAKE, "Cayenne (9PA, first generation)", 2003..2010)
    val CAYENNE_92A = VehicleProfile("porsche.cayenne.92a", MAKE, "Cayenne (92A, second generation)", 2011..2018)
    val CAYENNE_9YA = VehicleProfile("porsche.cayenne.9ya", MAKE, "Cayenne (9YA, third generation)", 2019..2099)

    val entry = ManufacturerEntry(MAKE, setOf("WP0", "WP1"), listOf(CAYENNE_9PA, CAYENNE_92A, CAYENNE_9YA)) { PorscheService() }
}

/** Constructing this sends nothing and holds no connection. */
class PorscheService : ManufacturerService {
    override val make = Porsche.MAKE
    override val summary = "No Porsche feature is supported yet. One research experiment exists for the second-generation Cayenne."

    private val cayenne = Requirement.Profile(setOf(Porsche.CAYENNE_92A.id))
    private val cluster = Requirement.Module("the instrument cluster's identity and software variant")
    private val noDefinition = "Not available. No verified definition exists yet (issue #58)."

    override val capabilities = listOf(
        CapabilityDefinition(
            id = "porsche.cayenne92a.part-number-experiment",
            title = "Module part-number experiment",
            requirements = listOf(Requirement.Make(Porsche.MAKE), cayenne),
            implementation = Implementation.ResearchExperiment(
                IdentificationExperiment.PLAN_ID,
                "Research only. After you review it, sends two fixed part-number reads. " +
                    "Results don't confirm which modules answered or any maintenance support.",
            ),
            source = "Issue #64 / PR #65. Prepared from one owner's 2014 Cayenne S; the two routes are hypotheses. " +
                "The latest owner run stopped with NO DATA after flow control (deferred).",
        ),
        CapabilityDefinition("porsche.cayenne92a.oil-service-read", "Oil service counter",
            listOf(Requirement.Make(Porsche.MAKE), cayenne, cluster), Implementation.None(noDefinition), "Issue #58"),
        CapabilityDefinition("porsche.cayenne92a.intermediate-inspection-read", "Intermediate inspection counter",
            listOf(Requirement.Make(Porsche.MAKE), cayenne, cluster), Implementation.None(noDefinition), "Issue #58"),
        CapabilityDefinition("porsche.cayenne92a.main-inspection-read", "Main inspection counter",
            listOf(Requirement.Make(Porsche.MAKE), cayenne, cluster), Implementation.None(noDefinition), "Issue #58"),
        CapabilityDefinition("porsche.service-reset", "Service resets",
            listOf(Requirement.Make(Porsche.MAKE)), Implementation.None("Not supported. This app never resets or writes to the car."),
            "Repository safety rules"),
    )
}
