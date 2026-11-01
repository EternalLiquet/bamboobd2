package io.github.eternalliquet.bamboobd2.core.vehicle

import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.protocol.ProtocolForm
import io.github.eternalliquet.bamboobd2.core.protocol.VehicleRequest
import io.github.eternalliquet.bamboobd2.core.reader.ElmObdReader
import io.github.eternalliquet.bamboobd2.core.reader.ReaderStoppedException
import io.github.eternalliquet.bamboobd2.core.reader.StopReason
import kotlinx.coroutines.withTimeoutOrNull

/** How one identification attempt ended. */
sealed interface IdentificationResult {
    /** The vehicle answered generic discovery; [evidence] says what is and is not known. */
    data class Completed(val evidence: VehicleEvidence) : IdentificationResult

    /** Adapter setup or generic discovery stopped, so no vehicle evidence exists. */
    data class Stopped(val reason: StopReason, val detail: String) : IdentificationResult
}

/**
 * The bounded, generic identification sequence for a fresh connection. It reuses the
 * normal reader, so it shares that reader's serialization, deadlines and retry rule:
 *
 * 1. Adapter setup (`ATE0`, `ATL0`, `ATH1`, `ATSP0`) and Mode 01 discovery (`0100`,
 *    `ATDPN`, then only the support pages the vehicle advertises), exactly as a scan.
 * 2. `0900` once, on 11-bit CAN only.
 * 3. `0902` once, only if a responder advertised PID 02.
 *
 * Nothing manufacturer-specific is sent, and the whole attempt has a time limit.
 * The caller owns the connection and must not issue other requests meanwhile.
 */
class VehicleIdentifier(private val reader: ElmObdReader, private val budgetMillis: Long = BUDGET_MILLIS) {

    suspend fun identify(): IdentificationResult =
        withTimeoutOrNull(budgetMillis) { run() }
            ?: IdentificationResult.Stopped(StopReason.NO_RESPONSE, "identification did not finish within ${budgetMillis / 1000} s")

    private suspend fun run(): IdentificationResult {
        val supported = try {
            reader.open()
            reader.readSupportedPids()
        } catch (e: ReaderStoppedException) {
            return IdentificationResult.Stopped(e.reason, e.message.orEmpty())
        }
        val protocol = reader.takeNotes().lastOrNull { it.kind == ElmObdReader.PROTOCOL_NOTE }?.detail
        val form = reader.protocolForm
        if (supported == SupportedPids.Unknown || form == null)
            return IdentificationResult.Completed(VehicleEvidence(protocol, supported, VinRead.NotAvailable("generic discovery found no responder")))
        if (form != ProtocolForm.CAN_11BIT)
            return IdentificationResult.Completed(VehicleEvidence(protocol, supported, VinRead.NotAvailable("VIN support unknown: ${VehicleInfoDecoder.CAN_ONLY}")))
        val vin = try {
            when (val support = VehicleInfoDecoder.support(reader.readVehicleInformation(VehicleRequest.VEHICLE_INFO_SUPPORTED), form)) {
                is VehicleInfoDecoder.Support.NotAvailable -> VinRead.NotAvailable("VIN support unknown: ${support.reason}")
                is VehicleInfoDecoder.Support.Known ->
                    if (support.vinResponders.isEmpty()) VinRead.NotAvailable("no responder advertises the VIN")
                    else VehicleInfoDecoder.vin(reader.readVehicleInformation(VehicleRequest.VIN), form)
            }
        } catch (e: ReaderStoppedException) {
            if (e.reason == StopReason.DISCONNECTED) return IdentificationResult.Stopped(e.reason, e.message.orEmpty())
            VinRead.NotAvailable("VIN read stopped: ${e.message}")
        }
        return IdentificationResult.Completed(VehicleEvidence(protocol, supported, vin))
    }

    companion object {
        /** Whole-attempt limit. Discovery's own 180 s cap still applies inside it. */
        const val BUDGET_MILLIS = 120_000L
    }
}
