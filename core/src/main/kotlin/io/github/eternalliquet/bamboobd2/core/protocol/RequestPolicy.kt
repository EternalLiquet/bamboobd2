package io.github.eternalliquet.bamboobd2.core.protocol

/**
 * A request that [RequestPolicy] has accepted. The constructor is internal to
 * :core, so Kotlin code in a platform shell cannot build one from arbitrary text
 * or bytes. (Kotlin `internal` does not stop Java callers.)
 */
class Request internal constructor(
    /** Canonical command text, e.g. "010C". */
    val text: String,
    val kind: Kind,
) {
    enum class Kind { VEHICLE, ADAPTER }

    /** Bytes to write: the command followed by a carriage return (ELM327DSJ p8). */
    fun encoded(): ByteArray = (text + "\r").toByteArray(Charsets.US_ASCII)

    override fun toString(): String = "Request($text)"
    override fun equals(other: Any?): Boolean = other is Request && other.text == text && other.kind == kind
    override fun hashCode(): Int = text.hashCode()
}

/** Generic, read-only vehicle requests allowed in milestone 1. Nothing else may be sent to a vehicle. */
enum class VehicleRequest(val text: String) {
    SUPPORTED_PIDS_01_20("0100"),
    ENGINE_LOAD("0104"),
    COOLANT_TEMP("0105"),
    ENGINE_RPM("010C"),
    VEHICLE_SPEED("010D"),
    STORED_DTCS("03"),
    PENDING_DTCS("07"),
}

sealed interface PolicyDecision {
    data class Accepted(val request: Request) : PolicyDecision
    data class Rejected(val reason: String) : PolicyDecision
}

/**
 * Deny-by-default request policy. Only the exact canonical texts of
 * [VehicleRequest] are accepted. ELM327 ignores spaces and control characters
 * and is case-insensitive (ELM327DSJ p9), so variants such as "0 4" or
 * "01 0c" are rejected rather than normalized. Adapter (AT) commands are all
 * rejected until the owner approves an exact list (see docs/PROTOCOL.md).
 */
object RequestPolicy {

    /** Approved adapter-local AT commands. Empty: the proposed list awaits owner approval. */
    val approvedAdapterCommands: List<String> = emptyList()

    /** The full allowlist in canonical form, used for the pinned hash check. */
    val allowlist: List<String> = VehicleRequest.entries.map { it.text } + approvedAdapterCommands

    fun vehicle(request: VehicleRequest): Request = Request(request.text, Request.Kind.VEHICLE)

    fun evaluate(text: String): PolicyDecision {
        VehicleRequest.entries.firstOrNull { it.text == text }?.let {
            return PolicyDecision.Accepted(vehicle(it))
        }
        if (text in approvedAdapterCommands) return PolicyDecision.Accepted(Request(text, Request.Kind.ADAPTER))
        return PolicyDecision.Rejected(
            when {
                text.isBlank() -> "empty or whitespace-only line (a bare CR repeats the last command)"
                text.trim().startsWith("AT", ignoreCase = true) -> "adapter command not approved"
                else -> "not in the read-only vehicle allowlist"
            },
        )
    }
}
