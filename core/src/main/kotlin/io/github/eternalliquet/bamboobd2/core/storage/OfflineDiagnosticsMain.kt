package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.experiment.IdentificationExperiment
import io.github.eternalliquet.bamboobd2.core.offline.*
import io.github.eternalliquet.bamboobd2.core.protocol.FakeTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Local JVM entry point. It has no hardware binding and never uploads evidence. */
fun main(args: Array<String>) = runBlocking {
    when (args.firstOrNull() ?: "plan") {
        "plan" -> print(IdentificationExperiment.preview())
        "demo" -> {
            val result = IdentificationExperiment.run(FakeTransport(IdentificationExperiment.syntheticReplies()))
            println("SYNTHETIC identification rehearsal: ${result.outcome}; cleanup acknowledged=${result.cleanupConfirmed}")
            println(result.explanation)
            val recording = ManufacturerRecording(result.recording, DiagnosticProtocol.UDS, RecordedFraming.CAN_11BIT_HEADERS)
            print(OfflineDiagnostics.analyze(recording).render())
            println()
            print(OfflineExample.render())
        }
        "fixture" -> print(Json { prettyPrint = true }.encodeToString(ManufacturerRecording.serializer(), OfflineExample.recording()))
        "analyze" -> print(OfflineDiagnostics.analyze(ManufacturerRecording.parse(readBounded(args.getOrNull(1)))).render())
        "capture" -> {
            require(args.size == 4) { "capture needs input path, explicit protocol (UNKNOWN/UDS/KWP) and framing" }
            val recording = ManufacturerRecording.fromCapture(readBounded(args[1]), DiagnosticProtocol.valueOf(args[2]), RecordedFraming.valueOf(args[3]))
            print(OfflineDiagnostics.analyze(recording).render())
        }
        else -> error("Use plan, demo, fixture, analyze <file>, or capture <file> <protocol> <framing>")
    }
}

private fun readBounded(path: String?): String {
    require(!path.isNullOrBlank()) { "Supply a local recording path" }
    val file = File(path)
    require(file.isFile) { "Input must be a regular local file" }
    val max = ManufacturerRecording.MAX_JSON_CHARS
    val bytes = ByteArray(max + 1)
    val count = file.inputStream().use { stream ->
        var used = 0
        while (used < bytes.size) {
            val n = stream.read(bytes, used, bytes.size - used)
            if (n < 0) break
            used += n
        }
        used
    }
    require(count <= max) { "Input exceeds $max bytes" }
    return bytes.copyOf(count).toString(Charsets.UTF_8)
}
