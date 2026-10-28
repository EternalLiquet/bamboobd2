package io.github.eternalliquet.bamboobd2.core.protocol

import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One synthetic fixture file under src/test/resources/fixtures/protocol (F16 metadata). */
@Serializable
data class ProtocolFixture(
    val id: String,
    val provenance: String,
    val sourceTier: String,
    val source: String,
    val request: String,
    val headers: Boolean,
    val form: String,
    val reply: String,
    val expected: String,
    val sha256: String? = null,
    val evidenceLog: String? = null,
) {
    val vehicleRequest: VehicleRequest get() = VehicleRequest.entries.single { it.text == request }
    val format: ResponseFormat get() = ResponseFormat(headers, ProtocolForm.valueOf(form))
}

object ProtocolFixtures {
    val dir = File("src/test/resources/fixtures/protocol")
    private val json = Json { ignoreUnknownKeys = false }

    fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }

    fun all(): List<ProtocolFixture> = files().map { json.decodeFromString(ProtocolFixture.serializer(), it.readText()) }

    /** Runs one reply through FakeTransport, framing, parsing and decoding. */
    fun run(request: VehicleRequest, format: ResponseFormat, chunks: List<ByteArray>): ObdResult = runBlocking {
        ObdQuery.run(FakeTransport(mapOf(request.text to chunks)), request, format)
    }

    fun run(f: ProtocolFixture, reply: String = f.reply): ObdResult =
        run(f.vehicleRequest, f.format, FakeTransport.text(reply))

    /** Stable text form of a result, compared against each fixture's "expected". */
    fun render(r: ObdResult): String = when (r) {
        is ObdResult.SupportedPids -> "supported:" + r.perEcu.joinToString(";") { e ->
            "${e.ecu ?: "-"}=" + e.value.sorted().joinToString(",") { "%02X".format(it) }
        }
        is ObdResult.Reading -> "reading:${r.unit}:" + r.perEcu.joinToString(";") { e ->
            "${e.ecu ?: "-"}=" + String.format(Locale.ROOT, "%.1f", e.value)
        }
        is ObdResult.Dtcs -> "dtcs:" + r.codes.joinToString(";") { "${it.ecu ?: "-"}/${it.code}/${it.kind}" }
        ObdResult.NoData -> "nodata"
        is ObdResult.AdapterError -> "adapter:${r.kind}"
        is ObdResult.Negative -> "negative:${r.ecu ?: "-"}:%02X:%02X".format(r.service, r.code)
        is ObdResult.Malformed -> "malformed"
        is ObdResult.Incomplete -> "incomplete"
    }

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
}
