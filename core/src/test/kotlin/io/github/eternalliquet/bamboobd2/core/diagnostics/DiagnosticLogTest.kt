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
            "note", "operation_start", "exchange", "exchange", "exchange", "stream_end", "session_end"), events.map { it.str("kind") })
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
        assertEquals(listOf("truncated", "session_end"), last.takeLast(2).map { it.str("kind") })
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
        val vinReply = doc.events().first { it.str("request") == "0902" }
        assertTrue(vinReply.str("reply")!!.startsWith("7E8 [REDACTED:VIN data, 8 bytes]"))
        assertTrue("short negative reply kept", "7E9 03 7F 09 31" in vinReply.str("reply")!!)
        assertTrue("[REDACTED:VIN data" in doc.events().first { it.str("request") == "0100" }.str("reply")!!)
        assertTrue("7E8064100BE1FB810" in text)
        assertTrue(doc["privacy"]!!.jsonObject.str("notAnonymous")!!.contains("Review before sharing"))
    }

    @Test fun noStoredSessionMeansNoExport() {
        val export = log().export()
        assertNull(export.json)
        assertEquals(0, export.sessions)
    }
}
