package io.github.eternalliquet.bamboobd2.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog

/** Where a session's data came from. Readers derive it from their transport's kind; callers cannot set it. */
@Serializable
enum class Provenance {
    /** Scripted by [io.github.eternalliquet.bamboobd2.core.reader.FakeReader] or a fake transport; not a vehicle scan. */
    SIMULATED,

    /** Adapter bytes replayed from a script or sanitized capture; not a live scan. */
    REPLAY,

    /** Read over a real adapter connection. */
    LIVE,
}

@Serializable
enum class DtcKind { STORED, PENDING }

/** A generic emissions-related trouble code. [ecu] is set when the responder is known. */
@Serializable
data class Dtc(val code: String, val kind: DtcKind, val ecu: String? = null)

@Serializable
sealed interface SupportedPids {
    /**
     * [pids] were confirmed by at least one responder. When [complete] is false,
     * some responder never reported its map, so a PID missing from [pids] is
     * unknown rather than unsupported.
     */
    @Serializable
    @SerialName("known")
    data class Known(
        val pids: List<String>,
        val complete: Boolean = true,
        val pages: List<PageEvidence> = emptyList(),
    ) : SupportedPids

    /** The supported-PID map could not be read. Nothing is requested on that basis. */
    @Serializable
    @SerialName("unknown")
    data object Unknown : SupportedPids
}

@Serializable
enum class PageStatus { VALID, MISSING, NO_DATA, NEGATIVE, MALFORMED, PENDING, CONFLICT }

/** One responder and one requested support page. VALID with no IDs is an observed zero map. */
@Serializable
data class PageEvidence(
    val ecu: String?,
    val pageBase: String,
    val status: PageStatus,
    val supportedIds: List<String> = emptyList(),
    val continues: Boolean = false,
    val reason: String = "",
)

enum class Capability { SUPPORTED_AND_DECODABLE, SUPPORTED_NOT_DECODED, UNSUPPORTED, UNKNOWN }
data class CapabilityResult(val capability: Capability, val reason: String, val pageBase: String)

/** A v1 union says at least one unidentified responder supported a PID, never which ECU. */
fun SupportedPids.capability(pid: String, ecu: String? = null): CapabilityResult {
    val n = pid.toIntOrNull(16)?.takeIf { pid.length == 2 && it in 1..255 }
        ?: return CapabilityResult(Capability.UNKNOWN, "invalid PID", "")
    if (pid in Mode01Catalog.pageIds) return CapabilityResult(Capability.UNKNOWN, "continuation marker is metadata", pid)
    val base = ((n - 1) / 32) * 32
    val page = "%02X".format(base)
    if (this !is SupportedPids.Known) return CapabilityResult(Capability.UNKNOWN, "no valid support map", page)
    fun supported() = CapabilityResult(
        if (Mode01Catalog.lookup(pid) != null) Capability.SUPPORTED_AND_DECODABLE else Capability.SUPPORTED_NOT_DECODED,
        "advertised by responder", page,
    )
    if (pages.isEmpty()) return when {
        pid in pids && ecu == null -> supported()
        ecu != null -> CapabilityResult(Capability.UNKNOWN, "legacy union has no responder identity", page)
        complete && base == 0 -> CapabilityResult(Capability.UNSUPPORTED, "legacy complete first-page union", page)
        base > 0 -> CapabilityResult(Capability.UNKNOWN, "legacy union has no page evidence for this range", page)
        else -> CapabilityResult(Capability.UNKNOWN, "legacy partial union", page)
    }
    fun forEcu(id: String?): CapabilityResult {
        val chain = pages.filter { it.ecu == id }
        for (b in 0..base step 32) {
            val bHex = "%02X".format(b)
            val entries = chain.filter { it.pageBase == bHex }
            val valid = entries.singleOrNull()?.takeIf { it.status == PageStatus.VALID }
                ?: return CapabilityResult(Capability.UNKNOWN, "missing or uncertain support page", bHex)
            if (b == base) return if (pid in valid.supportedIds) supported()
                else CapabilityResult(Capability.UNSUPPORTED, "valid responder map excludes PID", page)
            if (!valid.continues)
                return CapabilityResult(Capability.UNSUPPORTED, "responder chain terminated", bHex)
        }
        return CapabilityResult(Capability.UNKNOWN, "page unavailable", page)
    }
    if (ecu != null) return forEcu(ecu)
    val states = pages.map { it.ecu }.distinct().map(::forEcu)
    return states.firstOrNull { it.capability == Capability.SUPPORTED_AND_DECODABLE || it.capability == Capability.SUPPORTED_NOT_DECODED }
        ?: if (states.isNotEmpty() && states.all { it.capability == Capability.UNSUPPORTED }) states.first()
        else CapabilityResult(Capability.UNKNOWN, "at least one responder is unresolved", page)
}

@Serializable
sealed interface ReadingResult {
    @Serializable
    @SerialName("value")
    data class Value(val value: Double, val unit: String, val ecu: String? = null) : ReadingResult

    /** The request was sent and the adapter reported NO DATA. This is not "unsupported". */
    @Serializable
    @SerialName("no_data")
    data object NoData : ReadingResult

    /** The request was sent but gave no usable value (adapter error, malformed or negative reply). */
    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String) : ReadingResult

    /** The supported-PID map says the vehicle does not report this PID. */
    @Serializable
    @SerialName("unsupported")
    data object Unsupported : ReadingResult

    /** Support could not be determined, so the PID was not requested. */
    @Serializable
    @SerialName("unknown_capability")
    data object UnknownCapability : ReadingResult
}

@Serializable
data class Reading(val pid: String, val result: ReadingResult, val atMillis: Long)

@Serializable
enum class SessionOutcome {
    COMPLETE,
    CANCELLED,

    /** The reader stopped with an error or no response; data read so far is kept. */
    FAILED,

    /** The adapter connection ended; data read so far is kept. Never reconnected automatically. */
    DISCONNECTED,
}

@Serializable
data class SessionEvent(val atMillis: Long, val kind: String, val detail: String = "")

/**
 * Schema v1 session record, as described in docs/ANDROID_MILESTONE_1.md. v1 also
 * carries [dtcKindsRead] and the FAILED outcome. v1 is unreleased; a file
 * without [dtcKindsRead] reopens with its codes shown as not read.
 */
@Serializable
data class SessionRecord(
    val schemaVersion: Int,
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val provenance: Provenance,
    val sourceLabel: String,
    val complete: Boolean,
    val outcome: SessionOutcome? = null,
    val supportedPids: SupportedPids = SupportedPids.Unknown,
    val dtcs: List<Dtc> = emptyList(),
    /** Which code lists were actually read. Empty [dtcs] means "none reported" only for these. */
    val dtcKindsRead: List<DtcKind> = emptyList(),
    val readings: List<Reading> = emptyList(),
    val events: List<SessionEvent> = emptyList(),
) {
    /**
     * True when the car gave nothing usable: no supported-PID map, no code list
     * read, no codes and no reading value. A session can be COMPLETE and still read
     * nothing, for example when the adapter could not initialize the bus.
     */
    val readNothing: Boolean
        get() = supportedPids == SupportedPids.Unknown && dtcKindsRead.isEmpty() && dtcs.isEmpty() &&
            readings.none { it.result is ReadingResult.Value }

    /** True when both stored and pending codes were read, so an empty list means none were reported. */
    val allCodesRead: Boolean
        get() = DtcKind.STORED in dtcKindsRead && DtcKind.PENDING in dtcKindsRead

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

/** The four generic Mode 01 readings in scope for milestone 1. */
enum class ObdPid(val code: String, val unit: String) {
    ENGINE_LOAD("04", "%"),
    COOLANT_TEMP("05", "°C"),
    ENGINE_RPM("0C", "rpm"),
    VEHICLE_SPEED("0D", "km/h"),
}
