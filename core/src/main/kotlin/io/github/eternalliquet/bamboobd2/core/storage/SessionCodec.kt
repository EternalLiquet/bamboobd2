package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
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
        require(record.schemaVersion == SessionRecord.SCHEMA_VERSION) {
            "Unsupported schema version ${record.schemaVersion}"
        }
        val tree = json.encodeToJsonElement(SessionRecord.serializer(), record)
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
        if (version != SessionRecord.SCHEMA_VERSION) return DecodeResult.Invalid("unknown schemaVersion $version")
        val provenance = (tree["provenance"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return DecodeResult.Invalid("missing provenance")
        if (Provenance.entries.none { it.name == provenance }) {
            return DecodeResult.Invalid("unknown provenance '$provenance'")
        }
        val record = try {
            json.decodeFromJsonElement(SessionRecord.serializer(), tree)
        } catch (e: SerializationException) {
            return DecodeResult.Invalid("does not match schema v1: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Invalid("does not match schema v1: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        // A saved session has always finished, and "complete" must agree with its outcome.
        val outcome = record.outcome ?: return DecodeResult.Invalid("missing outcome")
        if (record.complete != (outcome == SessionOutcome.COMPLETE)) {
            return DecodeResult.Invalid("complete flag contradicts outcome $outcome")
        }
        return DecodeResult.Ok(record)
    }

    private fun findVinShaped(element: JsonElement, field: String = ""): String? = when (element) {
        is JsonObject -> element.entries
            .filterNot { it.key in vinScanExempt }
            .firstNotNullOfOrNull { (k, v) -> findVinShaped(v, k) }
        is JsonArray -> element.firstNotNullOfOrNull { findVinShaped(it, field) }
        is JsonPrimitive -> if (element.isString && vinShaped.containsMatchIn(element.content)) field else null
    }
}
