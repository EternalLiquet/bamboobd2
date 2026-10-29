package io.github.eternalliquet.bamboobd2.core.protocol

import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.model.ObdPid

/**
 * One request and its reply over a transport: write, collect bytes until the
 * prompt, parse, decode. No timeout, retry or session logic here; those belong
 * to the session layer (issue #10).
 */
object ObdQuery {
    suspend fun run(transport: AdapterTransport, request: VehicleRequest, format: ResponseFormat): ObdResult {
        val accepted = RequestPolicy.vehicle(request)
        transport.write(accepted)
        val assembler = ResponseAssembler()
        while (true) {
            val chunk = transport.read() ?: return ObdResult.Incomplete(assembler.pending())
            val responses = assembler.feed(chunk)
            if (responses.isNotEmpty()) {
                // Anything after the first prompt is not part of this reply.
                val parsed = ElmResponseParser.parse(responses.first(), accepted, format)
                return ObdDecoder.decode(request, parsed, format.form)
            }
        }
    }
}

/** Which of the four readings may be requested. Unknown support means none (F8). */
object ReadPlan {
    fun readingRequests(supported: SupportedPids): List<VehicleRequest> =
        ObdPid.entries.mapNotNull { old -> Mode01Catalog.lookup(old.code) }
            .filter { supported is SupportedPids.Known && it.pid in supported.pids }
            .map { reading -> VehicleRequest.entries.single { it.text == "01${reading.pid}" } }

    /** Converts a decoded 0100 reply into the session model; anything else leaves support unknown. */
    fun supportedFrom(result: ObdResult): SupportedPids = when (result) {
        is ObdResult.SupportedPids -> SupportedPids.Known(result.union.sorted().map { "%02X".format(it) })
        else -> SupportedPids.Unknown
    }
}
