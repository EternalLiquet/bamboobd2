package io.github.eternalliquet.bamboobd2.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    data class Known(val pids: List<String>, val complete: Boolean = true) : SupportedPids

    /** The supported-PID map could not be read. Nothing is requested on that basis. */
    @Serializable
    @SerialName("unknown")
    data object Unknown : SupportedPids
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
    /** True when both stored and pending codes were read, so an empty list means none were reported. */
    val allCodesRead: Boolean
        get() = DtcKind.STORED in dtcKindsRead && DtcKind.PENDING in dtcKindsRead

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** The four generic Mode 01 readings in scope for milestone 1. */
enum class ObdPid(val code: String, val unit: String) {
    ENGINE_LOAD("04", "%"),
    COOLANT_TEMP("05", "°C"),
    ENGINE_RPM("0C", "rpm"),
    VEHICLE_SPEED("0D", "km/h"),
}
