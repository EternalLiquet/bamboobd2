package io.github.eternalliquet.bamboobd2.core.diagnostics

import io.github.eternalliquet.bamboobd2.core.storage.DiagnosticLimits
import io.github.eternalliquet.bamboobd2.core.storage.DiagnosticLog
import io.github.eternalliquet.bamboobd2.core.storage.DiagnosticSession

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Constructed traffic only; nothing here comes from a vehicle. */
class DiagnosticLogTest {
    private val dir = Files.createTempDirectory("diagnostics").toFile()
    private val info = mapOf("appVersion" to "test", "versionCode" to "0")
    private fun log(limits: DiagnosticLimits = DiagnosticLimits()) = DiagnosticLog(dir, info, limits)
    private fun DiagnosticSession.reply(text: String) = onRead(text.toByteArray(Charsets.ISO_8859_1))

    private fun exported(log: DiagnosticLog): JsonObject = Json.parseToJsonElement(log.export().json!!).jsonObject
    private fun JsonObject.events(session: Int = 0) = this["sessions"]!!.jsonArray[session].jsonObject["events"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    @Test fun oneConnectionKeepsSetupIdentificationAndScanInOrderWithPartialAndErrorReplies() {
        val log = log()
        val s = log.startSession("vehicle", "VEEPEAK")
        s.app("connected")
        s.operationStart("identification")
        s.onWrite("ATE0"); s.reply("ATE0\rOK\r\r>")
        s.onWrite("0100"); s.reply("SEARCHING...\r7E8 03 7F 01 "); s.reply("12\r\r>")
        s.onWrite("0900"); s.reply("NO DATA\r\r>")
        s.operationEnd("identification", "COMPLETED", "VIN not read")
        s.interpretation("identification", "car not identified")
        s.operationStart("scan", "CODES_ONLY")
        s.onWrite("03"); s.reply("7E8 02 43")      // no prompt: a timeout as the reader sees it
        s.onWrite("03"); s.reply("00\r\r>")        // retry; late tail lands in the second exchange
        s.reply("STOPPED\r>")                      // unsolicited bytes after the prompt
        s.onStreamEnded("end of stream")
        s.end("The scanner connection ended.")
        val events = exported(log).events()

        assertEquals(listOf("session_start", "connected", "operation_start", "exchange", "exchange", "exchange", "operation_end",
            "note", "operation_start", "exchange", "exchange", "exchange", "stream_end", "session_end"), events.filter { it.str("kind") != "write_started" }.map { it.str("kind") })
        val exchanges = events.filter { it.str("kind") == "exchange" }
        assertEquals(listOf("ATE0", "0100", "0900", "03", "03", null), exchanges.map { it.str("request") })
        assertTrue(exchanges.all { it.str("category") == "traffic" })
        // Exact text, split chunks reassembled, adapter text kept.
        assertEquals("SEARCHING...\r7E8 03 7F 01 12\r\r>", exchanges[1].str("reply"))
        assertEquals(2, exchanges[1]["chunks"]!!.jsonArray.size)
        assertEquals("NO DATA\r\r>", exchanges[2].str("reply"))
        assertEquals("7E8 02 43", exchanges[3].str("reply"))
        assertFalse(exchanges[3]["prompt"]!!.jsonPrimitive.boolean)
        assertEquals("next_write", exchanges[3].str("endedBy"))
        assertEquals("STOPPED\r>", exchanges[5].str("reply"))
        assertEquals("interpretation", events.single { it.str("kind") == "note" }.str("category"))
        assertTrue(events.zipWithNext().all { (a, b) -> a["seq"]!!.jsonPrimitive.content.toInt() < b["seq"]!!.jsonPrimitive.content.toInt() })
    }

    @Test fun storedSessionsSurviveRestartIncludingUnfinishedOnes() {
        val first = log()
        first.startSession("vehicle", "VEEPEAK").apply { onWrite("ATE0"); reply("OK\r>"); end("done") }
        val crashed = first.startSession("experiment", "VEEPEAK")
        crashed.onWrite("ATPC"); crashed.reply("OK\r>")
        first.flush()

        val restarted = exported(log())
        val sessions = restarted["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, sessions.size)
        assertEquals(listOf(true, false), sessions.map { it["ended"]!!.jsonPrimitive.boolean })
        assertEquals("ATPC", restarted.events(1).last().str("request"))
        assertEquals(1, log().export().unfinished)
    }

    @Test fun rotationKeepsTheNewestSessionsAndTruncationIsMarked() {
        val small = log(DiagnosticLimits(maxSessions = 3, sessionTrafficBytes = 600, sessionBytes = 900))
        repeat(5) { i -> small.startSession("vehicle", "A$i").end("done $i"); small.flush(); Thread.sleep(2) }
        val kept = exported(small)["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, kept.size)
        assertEquals(listOf("A2", "A3", "A4"), kept.map { (it["events"] as JsonArray)[0].jsonObject.str("adapterName") })

        val busy = small.startSession("vehicle", "B")
        repeat(50) { busy.onWrite("0100"); busy.reply("7E8 06 41 00 BE 1F B8 10\r\r>") }
        busy.end("done")
        val doc = exported(small)
        assertTrue(doc["truncated"]!!.jsonPrimitive.boolean)
        val last = doc.events(doc["sessions"]!!.jsonArray.size - 1)
        assertTrue(last.any { it.str("kind") == "truncated" })
        assertEquals("session_end", last.last().str("kind"))
        assertTrue(dir.listFiles()!!.all { it.length() < 1_200 })
    }

    @Test fun vinsAndAddressesAreHiddenButDebuggingDataStays() {
        val vin = "WP1AA2A2XELA00001"
        val hex = vin.map { "%02X".format(it.code) }
        val log = log()
        val s = log.startSession("vehicle", "VEEPEAK 00:11:22:33:44:55")
        s.app("connect_failed", "could not connect to 00:11:22:AA:BB:CC")
        s.onWrite("0902")
        s.reply("7E8 10 14 49 02 01 ${hex.take(3).joinToString(" ")}\r7E8 21 ${hex.subList(3, 10).joinToString(" ")}\r")
        s.reply("7E8 22 ${hex.subList(10, 17).joinToString(" ")}\r7E9 03 7F 09 31\r\r>")
        // The same VIN arriving late, under another request, is still found by decoding.
        s.onWrite("0100"); s.reply("7E8 10 14 49 02 01 ${hex.take(3).joinToString(" ")}\r7E8 21 ${hex.subList(3, 10).joinToString(" ")}\r7E8 22 ${hex.subList(10, 17).joinToString(" ")}\r\r>")
        s.interpretation("test", "plain $vin and compact hex 7E8064100BE1FB810")
        s.end("done")
        val text = log.export().json!!
        val doc = Json.parseToJsonElement(text).jsonObject

        assertFalse(vin in text)
        assertFalse(hex.subList(3, 10).joinToString(" ") in text)
        assertFalse("00:11:22" in text)
        assertTrue(DiagnosticRedactor.BLUETOOTH_ADDRESS in text)
        val vinReply = doc.events().first { it.str("kind") == "exchange" && it.str("request") == "0902" }
        assertTrue(vinReply.str("reply")!!.startsWith("7E8 [REDACTED:VIN data, 8 bytes]"))
        assertTrue("short negative reply kept", "7E9 03 7F 09 31" in vinReply.str("reply")!!)
        assertTrue("[REDACTED:VIN data" in doc.events().first { it.str("kind") == "exchange" && it.str("request") == "0100" }.str("reply")!!)
        assertTrue("7E8064100BE1FB810" in text)
        assertTrue(doc["privacy"]!!.jsonObject.str("notAnonymous")!!.contains("Review before sharing"))
    }

    @Test fun noStoredSessionMeansNoExport() {
        val export = log().export()
        assertNull(export.json)
        assertEquals(0, export.sessions)
    }

    @Test fun vinFramesAreHiddenWithLfPromptAttachedAndInsideErrorDetails() {
        val frames = listOf("7E8 10 14 49 02 01 57 50 31", "7E8 21 41 41 32 41 32 58 45", "7E8 22 4C 41 30 30 30 30 31")
        for (separator in listOf("\r", "\n", "\r\n")) {
            val log = log()
            val s = log.startSession("vehicle", "test")
            s.onWrite("0902"); s.reply(frames.joinToString(separator) + ">")
            s.operationEnd("identification", "STOPPED_NO_PROTOCOL", "ATDPN '?' after 0100 '${frames.joinToString(" ")}'")
            s.end("done")
            val exported = log.export().json!!
            val disk = dir.listFiles()!!.filter { it.isFile }.joinToString { it.readText() }
            for (text in listOf(exported, disk)) {
                assertFalse("encoded VIN serial escaped redaction", "4C 41 30 30 30 30 31" in text)
                assertFalse("encoded VIN middle escaped redaction", "41 41 32 41 32 58 45" in text)
            }
        }
    }

    @Test fun pendingRequestAndSafePartialReplySurviveRestart() {
        val first = log()
        val s = first.startSession("vehicle", "test")
        s.onWrite("0100"); s.reply("7E8 03 7F 01")
        first.flush()
        val restarted = log().export()
        assertEquals(1, restarted.unfinished)
        assertTrue("pending request lost", "0100" in restarted.json!!)
        assertTrue("negative-response prefix lost", "7E8 03 7F 01" in restarted.json)
    }

    @Test fun partialVinCheckpointNeverPersistsIdentifierFragments() {
        val first = log()
        val s = first.startSession("vehicle", "test")
        s.onWrite("0902")
        for (chunk in listOf("7E8 10 14 49 02 01 57 50 31\r", "7E8 21 41 41 32 41 32 58 45\r", "7E8 22 4C 41 30 30 30 30 31")) {
            s.reply(chunk); first.flush()
            val text = dir.listFiles()!!.filter { it.isFile }.joinToString { it.readText() }
            assertFalse("41 41 32 41 32 58 45" in text)
            assertFalse("4C 41 30 30 30 30 31" in text)
        }
        assertTrue("checkpoint has to exist", "0902" in log().export().json!!)
    }

    @Test fun truncationIsDurableBeforeSessionEnd() {
        val small = log(DiagnosticLimits(sessionTrafficBytes = 600, sessionBytes = 900))
        val s = small.startSession("vehicle", "test")
        repeat(20) { s.onWrite("0100"); s.reply("7E8 06 41 00 BE 1F B8 10\r>") }
        small.flush()
        assertTrue("active export must disclose loss", small.export().truncated)
        assertTrue("restart must disclose loss", log().export().truncated)
    }

    @Test fun transientWriteFailureIsDisclosedAfterStorageRecovers() {
        val log = log()
        val s = log.startSession("vehicle", "test")
        log.flush()
        val file = java.io.File(dir, "${s.id}.jsonl")
        val original = file.readText()
        assertTrue(file.delete())
        assertTrue(file.mkdir()) // Appending to this path now fails on every supported host.
        try {
            s.onWrite("0100")
            log.flush() // A persistent failure must not turn into a busy retry loop.
            log.flush() // Drain the loss notification queued by that write before replacing the fixture.
        } finally {
            assertTrue(file.delete())
            file.writeText(original)
        }
        s.reply("NO DATA\r>")
        s.end("done")
        val export = log.export()
        assertEquals(0, export.unfinished)
        assertTrue("a closed session must still disclose lost traffic", export.truncated)
        assertTrue("restart must retain the loss warning", log().export().truncated)
        assertEquals(1, exported(log).events().count { it.str("kind") == "truncated" })
    }

    @Test fun storedLossEvidenceWarnsEvenWithoutATruncationMarker() {
        val log = log()
        val s = log.startSession("vehicle", "test")
        s.end("done"); log.flush()
        val file = java.io.File(dir, "${s.id}.jsonl")
        val intact = file.readText()
        for (damaged in listOf(intact.replace("\"droppedEvents\":0", "\"droppedEvents\":2"), intact + "{unfinished line\n")) {
            file.writeText(damaged)
            assertTrue("persisted loss must be visible to the export dialog", log().export().truncated)
        }
    }

    @Test fun exportSnapshotCannotBeRotatedWhileBeingRead() {
        // The directory forces the old export's unsafe interleaving: list A, rotate
        // A out on the worker, then try to read A. Worker-owned snapshots don't interleave.
        var action: (() -> Unit)? = null
        val racingDir = object : java.io.File(dir.path) {
            override fun listFiles(filter: java.io.FileFilter): Array<java.io.File>? {
                val snapshot = super.listFiles(filter)
                if (Thread.currentThread().name != "diagnostic-log") action?.also { action = null; it() }
                return snapshot
            }
        }
        val log = DiagnosticLog(racingDir, info, DiagnosticLimits(maxSessions = 1))
        log.startSession("vehicle", "A").end("done"); log.flush()
        action = { log.startSession("vehicle", "B").end("done"); log.flush() }
        assertEquals(1, log.export().sessions)
    }

    @Test fun blockedDiskDoesNotAllowAnUnboundedTrafficBacklog() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockedDir = object : java.io.File(dir.path) {
            override fun listFiles(filter: java.io.FileFilter): Array<java.io.File>? {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return super.listFiles(filter)
            }
        }
        val log = DiagnosticLog(blockedDir, info, DiagnosticLimits(sessionTrafficBytes = 10_000_000, sessionBytes = 11_000_000))
        val s = log.startSession("vehicle", "test")
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            // Fits the disk budget. Only pending-work admission should drop it.
            repeat(2_000) { s.onWrite("0100"); s.reply("NO DATA\r>") }
            s.end("done")
        } finally { release.countDown() }
        val export = log.export()
        assertTrue("queue overload must be disclosed", export.truncated)
        assertEquals("terminal event must survive overload", 0, export.unfinished)
    }

    @Test fun recognizedPartNumberContinuationSurvivesItsSeparateFlowControlExchange() {
        val log = log()
        val s = log.startSession("experiment", "test")
        s.onWrite("0322F18700000000")
        s.reply("793 10 0E 62 F1 87 37 50 35\r>")
        s.onWrite("30020A0000000000")
        val continuation = "793 21 39 32 30 39 38 30 4B\r793 22 20 00 00 00 00 00 00\r>"
        s.reply(continuation); s.end("done")
        val exchange = exported(log).events().single { it.str("kind") == "exchange" && it.str("request") == "30020A0000000000" }
        assertEquals(continuation, exchange.str("reply"))
    }

    @Test fun shortLateVinContinuationIsHiddenUnderAnotherRequest() {
        val log = log()
        val s = log.startSession("vehicle", "test")
        s.onWrite("0902"); s.reply("7E8 10 14 49 02 01 57 50 31\r>")
        s.onWrite("0100"); s.reply("7E8 22 4C 41\r>"); s.end("done")
        assertFalse("short VIN tail leaked", "4C 41" in log.export().json!!)
    }

    @Test fun lateVinTailInReaderErrorsIsHiddenWithoutItsFirstFrame() {
        val log = log()
        val s = log.startSession("vehicle", "test")
        s.operationEnd("identification", "STOPPED_NO_PROTOCOL", "ATDPN '?' after 0100 '7E8 21 41 41 32 41 32 58 45 7E8 22 4C 41 30 30 30 30 31'")
        s.end("done")
        assertFalse("4C 41 30 30 30 30 31" in log.export().json!!)
    }

    @Test fun shortCompactVinContinuationsInErrorsNeverReachDiskOrExport() {
        val fragments = listOf("7E8224C4130", "7E8224C41", "7E8224C", "224C4130", "224C41", "224C")
        val log = log()
        val s = log.startSession("vehicle", "test")
        for (fragment in fragments) {
            s.operationEnd("identification", "STOPPED_NO_PROTOCOL", "ATDPN '?' after 0100 '$fragment'")
        }
        s.end("done")
        val exported = log.export().json!!
        val disk = dir.listFiles()!!.filter { it.isFile }.joinToString { it.readText() }
        for (fragment in fragments) {
            assertFalse("compact VIN tail persisted: $fragment", fragment in disk)
            assertFalse("compact VIN tail exported: $fragment", fragment in exported)
        }
    }

    @Test fun incompleteDataCannotClaimToBeAnAlreadyRedactedMarkerOrValidShortFrame() {
        for (input in listOf("[REDACTED:adapter says safe] 57 50 31 41 41 32", "7E8 03 41 00 57 50 31 41 41 32")) {
            val output = DiagnosticRedactor.partialReply("0100", input).text
            assertFalse("unclassified raw prefix retained", "57 50 31 41 41 32" in output)
        }
    }

    @Test fun rotatedLiveSessionCannotRecreateItsFiles() {
        val log = log(DiagnosticLimits(maxSessions = 1))
        val first = log.startSession("vehicle", "A")
        first.onWrite("0100"); first.reply("7E8 03 7F 01"); log.flush()
        log.startSession("vehicle", "B").end("done"); log.flush()
        first.app("late callback"); first.end("late close")
        assertEquals(1, log.export().sessions)
        assertFalse(dir.listFiles()!!.any { it.name.startsWith(first.id) })
        assertEquals("session_start", exported(log).events().first().str("kind"))
    }

    @Test fun retainedCheckpointIsSeparateFromAnEndedSessionsOrderedEvents() {
        val log = log(DiagnosticLimits(sessionTrafficBytes = 700, sessionBytes = 900))
        val s = log.startSession("vehicle", "test")
        s.onWrite("0100"); s.reply("7E8 03 7F 01"); s.onWriteCompleted("0100"); s.end("done")
        val document = exported(log)
        val events = document.events()
        assertEquals("session_end", events.last().str("kind"))
        assertTrue(events.zipWithNext().all { (a, b) -> a.str("seq")!!.toInt() < b.str("seq")!!.toInt() })
        val checkpoint = document["sessions"]!!.jsonArray.single().jsonObject["incompleteExchange"]!!.jsonObject
        assertEquals("completion_not_recorded", checkpoint.str("recovery"))
        assertNull(checkpoint["seq"])
        assertEquals("7E8 03 7F 01", checkpoint.str("reply"))
    }

    @Test fun aPromptlessStreamIsBoundedEvenWhenTheWorkerKeepsUp() {
        val log = log(DiagnosticLimits(sessionTrafficBytes = 10_000_000, sessionBytes = 11_000_000))
        val s = log.startSession("vehicle", "test")
        s.onWrite("0100")
        repeat(64) { s.reply("A".repeat(8_192)); log.flush() }
        assertTrue("receive accumulation must disclose its limit", log.export().truncated)
        s.end("done")
        val exchange = exported(log).events().single { it.str("kind") == "exchange" }
        assertTrue(exchange.str("replyBytes")!!.toInt() <= 64_000)
        assertTrue(exchange["replyTruncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun aStaleCheckpointDoesNotDuplicateACompletedExchange() {
        val log = log()
        val s = log.startSession("vehicle", "test")
        s.onWrite("0100"); s.reply("7E8 03 7F 01"); log.flush()
        val pending = java.io.File(dir, "${s.id}.pending")
        val checkpoint = pending.readText()
        s.reply(" 12\r>"); s.end("done"); log.flush()
        // Simulate process death after appending the complete event but before deleting its checkpoint.
        pending.writeText(checkpoint)
        val restarted = exported(log())
        assertEquals(1, restarted.events().count { it.str("kind") == "exchange" })
        assertNull(restarted["sessions"]!!.jsonArray.single().jsonObject["incompleteExchange"])
    }
}
