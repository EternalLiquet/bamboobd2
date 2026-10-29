package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.model.Capability
import io.github.eternalliquet.bamboobd2.core.model.PageEvidence
import io.github.eternalliquet.bamboobd2.core.model.PageStatus
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.model.SupportedPids
import io.github.eternalliquet.bamboobd2.core.model.capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode01SchemaTest {
    @Test fun v1ReopensWithoutInventingEcuAndV2MapsRoundTrip() {
        val old = SessionRecord(1, "old", 1, 2, Provenance.REPLAY, "synthetic", true,
            outcome = SessionOutcome.COMPLETE, supportedPids = SupportedPids.Known(listOf("05")))
        // This file is read only; decoding never rewrites its original bytes.
        val bytes = SessionCodec.encode(old)
        val reopened = (SessionCodec.decode(bytes) as DecodeResult.Ok).record
        assertEquals(bytes, SessionCodec.encode(reopened))
        assertEquals(emptyList<PageEvidence>(), (reopened.supportedPids as SupportedPids.Known).pages)
        assertEquals(Capability.UNKNOWN, reopened.supportedPids.capability("05", "7E8").capability)
        assertEquals(Capability.SUPPORTED_AND_DECODABLE, reopened.supportedPids.capability("05").capability)

        val current = old.copy(schemaVersion = 2, supportedPids = SupportedPids.Known(listOf("05"),
            pages = listOf(PageEvidence("7E8", "00", PageStatus.VALID, listOf("05")),
                PageEvidence("7E9", "00", PageStatus.VALID))))
        val encoded = SessionCodec.encode(current)
        assertEquals(current, (SessionCodec.decode(encoded) as DecodeResult.Ok).record)
        assertTrue(SessionCodec.decode(encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")) is DecodeResult.Invalid)
        assertTrue(SessionCodec.decode(encoded.replace("\"provenance\": \"REPLAY\"", "\"provenance\": \"MADE_UP\"")) is DecodeResult.Invalid)
    }
}
