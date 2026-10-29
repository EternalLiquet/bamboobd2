package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.LiveEventKind
import io.github.eternalliquet.bamboobd2.core.model.LiveSampleOutcome
import io.github.eternalliquet.bamboobd2.core.model.LiveStopReason
import io.github.eternalliquet.bamboobd2.core.model.Capability
import io.github.eternalliquet.bamboobd2.core.model.capability
import io.github.eternalliquet.bamboobd2.core.protocol.Mode01Catalog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

sealed interface DecodeResult {
    data class Ok(val record: SessionRecord) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

class RecordRejectedException(message: String) : IllegalArgumentException(message)

/** Encodes session records as deterministic JSON and validates records on the way back in. */
object SessionCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    // A delimited 17-character token in the VIN alphabet (no I, O or Q), any case.
    private val vinShaped = Regex("(?<![A-Za-z0-9])[A-HJ-NPR-Za-hj-npr-z0-9]{17}(?![A-Za-z0-9])")

    /** Field names whose values are exempt from the VIN-shape scan (hashes, later). */
    private val vinScanExempt = setOf("sourceSha256")

    fun encode(record: SessionRecord): String {
        require(record.schemaVersion in 1..SessionRecord.SCHEMA_VERSION) {
            "Unsupported schema version ${record.schemaVersion}"
        }
        validateRecording(record)?.let { throw RecordRejectedException(it) }
        val encoded = json.encodeToJsonElement(SessionRecord.serializer(), record).jsonObject
        val tree = if (record.recording == null) JsonObject(encoded.filterKeys { it != "recording" }) else encoded
        findVinShaped(tree)?.let {
            throw RecordRejectedException("Refusing to save a VIN-shaped value in field '$it'")
        }
        return json.encodeToString(JsonElement.serializer(), tree) + "\n"
    }

    fun decode(text: String): DecodeResult {
        val tree = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return DecodeResult.Invalid("not valid JSON")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Invalid("not a JSON object")
        }
        val version = (tree["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return DecodeResult.Invalid("missing schemaVersion")
        if (version !in 1..SessionRecord.SCHEMA_VERSION) return DecodeResult.Invalid("unknown schemaVersion $version")
        if (version == 1 && "recording" in tree) return DecodeResult.Invalid("schema v1 cannot contain recording")
        val provenance = (tree["provenance"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return DecodeResult.Invalid("missing provenance")
        if (Provenance.entries.none { it.name == provenance }) {
            return DecodeResult.Invalid("unknown provenance '$provenance'")
        }
        val record = try {
            json.decodeFromJsonElement(SessionRecord.serializer(), tree)
        } catch (e: SerializationException) {
            return DecodeResult.Invalid("does not match schema v$version: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Invalid("does not match schema v$version: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (version == 1 && (record.supportedPids as? io.github.eternalliquet.bamboobd2.core.model.SupportedPids.Known)?.pages?.isNotEmpty() == true)
            return DecodeResult.Invalid("schema v1 cannot contain responder page evidence")
        validateRecording(record)?.let { return DecodeResult.Invalid(it) }
        // A saved session has always finished, and "complete" must agree with its outcome.
        val outcome = record.outcome ?: return DecodeResult.Invalid("missing outcome")
        if (record.complete != (outcome == SessionOutcome.COMPLETE)) {
            return DecodeResult.Invalid("complete flag contradicts outcome $outcome")
        }
        return DecodeResult.Ok(record)
    }

    private fun validateRecording(record: SessionRecord): String? {
        val live = record.recording ?: return null
        if (record.schemaVersion != 2) return "recording requires schema v2"
        if (record.outcome == null || record.endedAt == null || live.stopReason == null)
            return "recording has not ended"
        if (record.readings.isNotEmpty()) return "recording cannot duplicate scalar snapshot readings"
        if (live.policyId != "provisional-live-v1") return "unknown live policy"
        if (live.selectedPids.size > 4 || live.selectedPids.distinct().size != live.selectedPids.size ||
            live.selectedPids.any { Mode01Catalog.lookup(it) == null ||
                record.supportedPids.capability(it).capability != Capability.SUPPORTED_AND_DECODABLE })
            return "invalid live selection"
        if (live.writeCount !in 0..1200 || live.retryCount !in 0..3 || live.retryCount > live.writeCount)
            return "invalid live write/retry counters"
        if (live.samples.size > 10_000) return "too many live outcomes"
        var lastElapsed = -1L
        live.samples.forEachIndexed { index, sample ->
            if (sample.sequence != index.toLong() + 1 || sample.elapsedMillis < lastElapsed ||
                sample.elapsedMillis !in 0..600_000 || sample.pid !in live.selectedPids ||
                sample.unit != Mode01Catalog.lookup(sample.pid)?.unit) return "invalid live sample order or identity"
            if (sample.outcome == LiveSampleOutcome.VALUE) {
                if (sample.value?.isFinite() != true || sample.reason != null) return "invalid live value"
            } else if (sample.value != null || sample.reason.isNullOrBlank()) return "invalid live missing/failure outcome"
            lastElapsed = sample.elapsedMillis
        }
        var lastEvent = -1L
        var paused = false
        live.events.forEachIndexed { index, event ->
            if (event.elapsedMillis < lastEvent || event.elapsedMillis !in 0..600_000 ||
                (event.kind == LiveEventKind.END) != (event.reason != null) ||
                (event.kind == LiveEventKind.END) != (index == live.events.lastIndex)) return "invalid live event order"
            when (event.kind) {
                LiveEventKind.PAUSE -> if (paused) return "invalid live event transition" else paused = true
                LiveEventKind.RESUME -> if (!paused) return "invalid live event transition" else paused = false
                LiveEventKind.END -> Unit
            }
            lastEvent = event.elapsedMillis
        }
        if (live.stopReason != live.events.lastOrNull()?.reason ||
            live.events.lastOrNull()?.elapsedMillis?.let { it < lastElapsed } == true)
            return "live stop reason contradicts events"
        val expected = when (live.stopReason) {
            LiveStopReason.USER, LiveStopReason.DURATION_LIMIT, LiveStopReason.WRITE_LIMIT,
            LiveStopReason.RETRY_LIMIT, LiveStopReason.SAMPLE_LIMIT -> SessionOutcome.COMPLETE
            LiveStopReason.CANCELLED -> SessionOutcome.CANCELLED
            LiveStopReason.DISCONNECTED -> SessionOutcome.DISCONNECTED
            LiveStopReason.FAILED -> SessionOutcome.FAILED
        }
        if (record.outcome != expected || record.complete != (expected == SessionOutcome.COMPLETE))
            return "live stop reason contradicts session outcome"
        return null
    }

    private fun findVinShaped(element: JsonElement, field: String = ""): String? = when (element) {
        is JsonObject -> element.entries
            .filterNot { it.key in vinScanExempt }
            .firstNotNullOfOrNull { (k, v) -> findVinShaped(v, k) }
        is JsonArray -> element.firstNotNullOfOrNull { findVinShaped(it, field) }
        is JsonPrimitive -> if (element.isString && vinShaped.containsMatchIn(element.content)) field else null
    }
}
