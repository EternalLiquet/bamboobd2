package io.github.eternalliquet.bamboobd2.core.protocol

/** Inspected source keys are explained with exact sections in docs/PROTOCOL.md. */
data class Mode01Reading(
    val pid: String,
    val labelKey: String,
    val unit: String,
    val bytes: Int,
    val evidence: String,
    val decode: (List<Int>) -> Double,
)

/** Generic Mode 01 scalar readings only. Page markers and unimplemented IDs are never data decoders. */
object Mode01Catalog {
    val pageIds = listOf("00", "20", "40", "60", "80", "A0", "C0", "E0")
    private fun one(pid: String, label: String, unit: String, evidence: String, f: (Int) -> Double) =
        Mode01Reading(pid, label, unit, 1, evidence) { f(it[0]) }
    private fun two(pid: String, label: String, unit: String, evidence: String, f: (Int) -> Double) =
        Mode01Reading(pid, label, unit, 2, evidence) { f(it[0] * 256 + it[1]) }
    private val percent: (Int) -> Double = { it * 100.0 / 255.0 }
    private val temperature: (Int) -> Double = { it - 40.0 }
    private val trim: (Int) -> Double = { it * 100.0 / 128.0 - 100.0 }

    val readings: List<Mode01Reading> = listOf(
        one("04", "engine_load", "%", "emtron", percent),
        one("05", "coolant_temperature", "°C", "elm", temperature),
        one("06", "short_fuel_trim_bank_1", "%", "arcx-ecusim", trim),
        one("07", "long_fuel_trim_bank_1", "%", "ecusim-css", trim),
        one("08", "short_fuel_trim_bank_2", "%", "css-dashlogic", trim),
        one("09", "long_fuel_trim_bank_2", "%", "css-dashlogic", trim),
        one("0B", "intake_manifold_absolute_pressure", "kPa", "emtron") { it.toDouble() },
        two("0C", "engine_speed", "rpm", "elm") { it / 4.0 },
        one("0D", "vehicle_speed", "km/h", "emtron") { it.toDouble() },
        one("0E", "timing_advance", "°", "emtron") { (it - 128) / 2.0 },
        one("0F", "intake_air_temperature", "°C", "emtron", temperature),
        two("10", "mass_air_flow", "g/s", "emtron") { it / 100.0 },
        one("11", "absolute_throttle_position_a", "%", "emtron-snapon", percent),
        two("1F", "run_time_since_engine_start", "s", "emtron") { it.toDouble() },
        one("2F", "fuel_tank_level", "%", "emtron", percent),
        one("33", "barometric_absolute_pressure", "kPa", "emtron") { it.toDouble() },
        two("42", "control_module_voltage", "V", "emtron") { it / 1000.0 },
        one("45", "relative_throttle_position", "%", "dashlogic-snapon", percent),
        one("46", "ambient_air_temperature", "°C", "emtron", temperature),
        one("47", "absolute_throttle_position_b", "%", "dashlogic-snapon", percent),
        one("48", "absolute_throttle_position_c", "%", "dashlogic-snapon", percent),
        one("49", "accelerator_pedal_position_d", "%", "dashlogic-snapon", percent),
        one("4A", "accelerator_pedal_position_e", "%", "dashlogic-snapon", percent),
        one("4B", "accelerator_pedal_position_f", "%", "dashlogic-snapon", percent),
        one("4C", "commanded_throttle_actuator", "%", "dashlogic-snapon", percent),
        one("5A", "relative_accelerator_pedal_position", "%", "dashlogic", percent),
        one("5C", "engine_oil_temperature", "°C", "emtron", temperature),
    )
    private val byPid = readings.associateBy { it.pid }
    fun lookup(pid: String): Mode01Reading? = byPid[pid]
}
