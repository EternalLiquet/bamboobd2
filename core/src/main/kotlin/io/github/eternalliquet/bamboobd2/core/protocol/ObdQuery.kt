package io.github.eternalliquet.bamboobd2.core.protocol

import io.github.eternalliquet.bamboobd2.core.model.ObdPid
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids

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
    private val requests = mapOf(
        ObdPid.ENGINE_LOAD to VehicleRequest.ENGINE_LOAD,
        ObdPid.COOLANT_TEMP to VehicleRequest.COOLANT_TEMP,
        ObdPid.ENGINE_RPM to VehicleRequest.ENGINE_RPM,
        ObdPid.VEHICLE_SPEED to VehicleRequest.VEHICLE_SPEED,
    )

    fun readingRequests(supported: SupportedPids): List<VehicleRequest> = when (supported) {
        SupportedPids.Unknown -> emptyList()
        is SupportedPids.Known -> ObdPid.entries.filter { it.code in supported.pids }.map { requests.getValue(it) }
    }

    /** Converts a decoded 0100 reply into the session model; anything else leaves support unknown. */
    fun supportedFrom(result: ObdResult): SupportedPids = when (result) {
        is ObdResult.SupportedPids -> SupportedPids.Known(result.union.sorted().map { "%02X".format(it) })
        else -> SupportedPids.Unknown
    }
}
