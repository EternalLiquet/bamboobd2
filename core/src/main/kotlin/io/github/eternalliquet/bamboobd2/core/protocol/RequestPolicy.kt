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

/**
 * Owner-approved adapter configuration and information commands (2026-10-28).
 * They configure or query the adapter itself; none sends a message to a vehicle.
 * Approval covers code and fake/replay tests only, not running them on an adapter.
 * Page numbers refer to ELM327 data sheet revision ELM327DSJ (see docs/PROTOCOL.md).
 */
enum class AdapterCommand(val text: String) {
    /** Echo off (p17). Configuration. */
    ECHO_OFF("ATE0"),

    /** Linefeeds off (p20). Configuration. */
    LINEFEEDS_OFF("ATL0"),

    /** Headers on (p18), so replies show which responder sent them. Configuration. */
    HEADERS_ON("ATH1"),

    /**
     * Describe the protocol by number (p17). Reports the adapter's current or last
     * protocol setting; it is not proof that any ECU is connected or answering.
     */
    DESCRIBE_PROTOCOL_NUMBER("ATDPN"),

    /** Print the version ID (p18). Self-reported text, not an authenticated unique identity. */
    VERSION_ID("ATI"),

    /** Display the device description (p28). Self-reported text, not an authenticated unique identity. */
    DEVICE_DESCRIPTION("AT@1"),

    /** Read the adapter's input voltage (p24), as measured at the adapter, not a vehicle sensor reading. */
    INPUT_VOLTAGE("ATRV"),

    /**
     * Set the protocol to automatic (p26): the next vehicle request searches the
     * protocols until one answers. SP 0 itself writes nothing to EEPROM, but with the
     * adapter's memory function on, a protocol it finds becomes the saved default.
     * The search is adapter-originated traffic. Owner-approved 2026-10-29.
     */
    AUTO_PROTOCOL("ATSP0"),
}

sealed interface PolicyDecision {
    data class Accepted(val request: Request) : PolicyDecision
    data class Rejected(val reason: String) : PolicyDecision
}

/**
 * Deny-by-default request policy. Only the exact canonical texts of
 * [VehicleRequest] and [AdapterCommand] are accepted. ELM327 ignores spaces and
 * control characters and is case-insensitive (ELM327DSJ p9), so variants such as
 * "0 4", "01 0c" or "at e0" are rejected rather than normalized. Every other AT
 * command is rejected.
 */
object RequestPolicy {

    /** Owner-approved adapter commands, exact text. */
    val approvedAdapterCommands: List<String> = AdapterCommand.entries.map { it.text }

    /** The full allowlist in canonical form, used for the pinned hash check. */
    val allowlist: List<String> = VehicleRequest.entries.map { it.text } + approvedAdapterCommands

    fun vehicle(request: VehicleRequest): Request = Request(request.text, Request.Kind.VEHICLE)

    fun adapter(command: AdapterCommand): Request = Request(command.text, Request.Kind.ADAPTER)

    fun evaluate(text: String): PolicyDecision {
        VehicleRequest.entries.firstOrNull { it.text == text }?.let {
            return PolicyDecision.Accepted(vehicle(it))
        }
        if (text in approvedAdapterCommands) return PolicyDecision.Accepted(Request(text, Request.Kind.ADAPTER))
        return PolicyDecision.Rejected(
            when {
                text.isBlank() -> "empty or whitespace-only line (a bare CR repeats the last command)"
                text.trim().startsWith("AT", ignoreCase = true) -> "adapter command not in the approved list"
                else -> "not in the read-only vehicle allowlist"
            },
        )
    }
}
