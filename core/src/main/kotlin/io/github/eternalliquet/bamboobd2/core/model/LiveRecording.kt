package io.github.eternalliquet.bamboobd2.core.model

import kotlinx.serialization.Serializable

/** Explicit host-software policy, independent of measured vehicle behavior. */
@Serializable
data class LiveRecording(
    val selectedPids: List<String> = emptyList(),
    val policyId: String = "provisional-live-v1",
    val writeCount: Int = 0,
    val retryCount: Int = 0,
    val samples: List<LiveSample> = emptyList(),
    val events: List<LiveEvent> = emptyList(),
    val stopReason: LiveStopReason? = null,
)

@Serializable
enum class LiveSampleOutcome { VALUE, MISSING, FAILURE }

@Serializable
data class LiveSample(
    val sequence: Long,
    val atMillis: Long,
    val elapsedMillis: Long,
    val pid: String,
    val ecu: String?,
    val unit: String,
    val outcome: LiveSampleOutcome,
    val value: Double? = null,
    val reason: String? = null,
)

@Serializable
enum class LiveEventKind { PAUSE, RESUME, END }

@Serializable
data class LiveEvent(val elapsedMillis: Long, val kind: LiveEventKind, val reason: LiveStopReason? = null)

@Serializable
enum class LiveStopReason {
    USER, DURATION_LIMIT, WRITE_LIMIT, RETRY_LIMIT, SAMPLE_LIMIT, CANCELLED, FAILED, DISCONNECTED
}
