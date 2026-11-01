package io.github.eternalliquet.bamboobd2.core.vehicle

import io.github.eternalliquet.bamboobd2.core.model.PageStatus
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids

/**
 * A 17-character vehicle identification number read from the car.
 *
 * Private data: it lives in memory for one connection only. [toString] is masked so
 * the full value cannot reach a log by accident; it is never saved or exported.
 * Structure from ISO 3779 (VIN content) and ISO 3780 (manufacturer code, WMI).
 */
class Vin private constructor(internal val value: String) {
    /** World manufacturer identifier: the first three characters (ISO 3780). */
    val wmi: String get() = value.substring(0, 3)

    /** Position 10; whether this is a model-year code depends on the applicable rules. */
    val yearCode: Char get() = value[9]

    /**
     * Position 9 check digit computed as 49 CFR 565.15 specifies. Required for North
     * American vehicles only, so a mismatch elsewhere is normal and proves nothing.
     */
    val checkDigitMatches: Boolean get() = checkDigit(value) == value[8]

    /** Only the manufacturer code is shown; the serial part stays hidden. */
    fun masked(): String = wmi + "•".repeat(LENGTH - 3)

    /** Market and vehicle-class applicability of a year rule are not established by a WMI or check digit. */
    fun modelYear(): YearEvidence = YearEvidence.Unknown("VIN model-year rule applicability is not established")

    override fun equals(other: Any?): Boolean = other is Vin && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "Vin(${masked()})"

    companion object {
        const val LENGTH = 17

        private val allowed = Regex("[A-HJ-NPR-Z0-9]{17}")
        private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

        /** Null unless [text] is exactly 17 VIN characters (no I, O or Q). */
        fun parse(text: String): Vin? = if (allowed.matches(text)) Vin(text) else null

        private fun transliterate(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in "AJ" -> 1; in "BKS" -> 2; in "CLT" -> 3; in "DMU" -> 4; in "ENV" -> 5
            in "FW" -> 6; in "GPX" -> 7; in "HY" -> 8; in "RZ" -> 9
            else -> 0
        }

        private fun checkDigit(vin: String): Char {
            val sum = vin.indices.sumOf { transliterate(vin[it]) * weights[it] }
            return (sum % 11).let { if (it == 10) 'X' else '0' + it }
        }
    }
}

/** What the VIN says about the model year. Never more certain than the rule used. */
sealed interface YearEvidence {
    data class FromVin(val year: Int, val basis: String) : YearEvidence
    data class Ambiguous(val candidates: List<Int>, val reason: String) : YearEvidence
    data class Unknown(val reason: String) : YearEvidence
}

/** Outcome of the standard VIN read for one connection. */
sealed interface VinRead {
    /** All responders that returned a valid VIN agreed on it. */
    data class Read(val vin: Vin, val responders: List<String?>) : VinRead

    /** Responders returned different valid VINs; none is chosen. */
    data class Conflict(val responders: List<String?>) : VinRead

    /** Not read: unsupported, no reply, negative, malformed, or not attempted. */
    data class NotAvailable(val reason: String) : VinRead
}

/**
 * Generic evidence gathered on one connection: no manufacturer knowledge. A VIN supplies
 * a manufacturer code. Year-rule applicability is unknown; model, trim, market, engine,
 * modules and supported commands are never derived from it here.
 */
data class VehicleEvidence(
    /** What ATDPN reported, e.g. "6: ISO 15765-4 CAN (11 bit ID, 500 kbaud), found by automatic search". */
    val protocol: String?,
    val supportedPids: SupportedPids,
    val vin: VinRead,
) {
    /** Responder IDs with at least one valid support page. */
    val responders: List<String?>
        get() = (supportedPids as? SupportedPids.Known)?.pages.orEmpty()
            .filter { it.status == PageStatus.VALID }.map { it.ecu }.distinct()

    val modelYear: YearEvidence
        get() = when (val v = vin) {
            is VinRead.Read -> v.vin.modelYear()
            is VinRead.Conflict -> YearEvidence.Unknown("responders returned different VINs")
            is VinRead.NotAvailable -> YearEvidence.Unknown("VIN not read")
        }
}
