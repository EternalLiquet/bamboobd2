package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSessionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(id: String = "s1", label: String = "fake:no_codes", complete: Boolean = true) = SessionRecord(
        schemaVersion = 1,
        id = id,
        startedAt = 1_000,
        endedAt = 2_000,
        provenance = Provenance.SIMULATED,
        sourceLabel = label,
        complete = complete,
        outcome = if (complete) SessionOutcome.COMPLETE else SessionOutcome.CANCELLED,
    )

    @Test
    fun saveThenReopenPreservesRecordAndProvenance() {
        val dir = tmp.newFolder()
        val r = record(complete = false)
        assertEquals(SaveResult.Saved("s1.1.json", 1), FileSessionStore(dir).save(r))
        val reopened = FileSessionStore(dir).load("s1")
        assertEquals(r, reopened)
        assertEquals(Provenance.SIMULATED, reopened!!.provenance)
    }

    @Test
    fun saveNeverOverwritesAndReopenReturnsLatest() {
        val dir = tmp.newFolder()
        val store = FileSessionStore(dir)
        store.save(record(label = "first"))
        val first = File(dir, "s1.1.json").readText()
        assertEquals(SaveResult.Saved("s1.2.json", 2), store.save(record(label = "second")))
        assertEquals(first, File(dir, "s1.1.json").readText())
        assertEquals("second", store.load("s1")!!.sourceLabel)
        assertEquals(1, store.list().size)
    }

    @Test
    fun crashBeforeMoveLeavesNoSessionAndTempIsCleanedOnStart() {
        val dir = tmp.newFolder()
        val crashing = FileSessionStore(dir) { throw java.io.IOException("simulated crash") }
        assertTrue(crashing.save(record()) is SaveResult.Failed)
        assertNull(FileSessionStore(dir).load("s1"))

        // A temp file left by a killed process is ignored and removed on start.
        File(dir, "s2.1.json.123456.tmp").writeText("{\"partial\"")
        File(dir, "notes.tmp").writeText("not ours")
        val store = FileSessionStore(dir)
        assertFalse(File(dir, "s2.1.json.123456.tmp").exists())
        assertTrue(File(dir, "notes.tmp").exists())
        assertTrue(store.list().filterIsInstance<StoredSession.Readable>().isEmpty())
        // The failed save also removed its own temp file.
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("s1.") })
    }

    @Test
    fun corruptTruncatedAndUnknownFilesAreListedAsUnreadable() {
        val dir = tmp.newFolder()
        val valid = SessionCodec.encode(record(id = "ok"))
        File(dir, "ok.1.json").writeText(valid)
        File(dir, "trunc.1.json").writeText(valid.take(valid.length / 2))
        File(dir, "garbage.1.json").writeText("not json")
        File(dir, "future.1.json").writeText(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 3").replace("\"ok\"", "\"future\""))
        File(dir, "invented.1.json").writeText(valid.replace("\"SIMULATED\"", "\"INVENTED\"").replace("\"ok\"", "\"invented\""))
        File(dir, "noprov.1.json").writeText(valid.replace("\"provenance\": \"SIMULATED\",", "").replace("\"ok\"", "\"noprov\""))

        val listed = FileSessionStore(dir).list()
        assertEquals(listOf("ok.1.json"), listed.filterIsInstance<StoredSession.Readable>().map { it.fileName })
        val reasons = listed.filterIsInstance<StoredSession.Unreadable>().associate { it.fileName to it.reason }
        assertEquals(setOf("trunc.1.json", "garbage.1.json", "future.1.json", "invented.1.json", "noprov.1.json"), reasons.keys)
        assertEquals("unknown schemaVersion 3", reasons["future.1.json"])
        assertEquals("unknown provenance 'INVENTED'", reasons["invented.1.json"])
        assertEquals("missing provenance", reasons["noprov.1.json"])
    }

    @Test
    fun damagedLatestSaveFallsBackToPreviousCompleteSave() {
        val dir = tmp.newFolder()
        val store = FileSessionStore(dir)
        store.save(record(label = "good"))
        File(dir, "s1.2.json").writeText("{")
        assertEquals("good", store.load("s1")!!.sourceLabel)
        val listed = store.list()
        assertEquals(1, listed.filterIsInstance<StoredSession.Readable>().size)
        assertEquals(1, listed.filterIsInstance<StoredSession.Unreadable>().size)
    }

    @Test
    fun vinShapedValueIsRefusedAndNothingIsWritten() {
        val dir = tmp.newFolder()
        val result = FileSessionStore(dir).save(record(label = "WP1ZZZ92ZELA12345"))
        assertTrue(result is SaveResult.Failed)
        assertTrue(dir.listFiles()!!.isEmpty())
        // Shorter or embedded tokens are not VIN-shaped.
        assertTrue(FileSessionStore(dir).save(record(label = "fake:two_codes")) is SaveResult.Saved)
    }

    @Test
    fun concurrentSavesOfOneSessionNeverOverwrite() {
        val dir = tmp.newFolder()
        val store = FileSessionStore(dir)
        val threads = (1..8).map { n -> Thread { store.save(record(label = "save-$n")) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val saved = dir.list()!!.filter { it.endsWith(".json") }.sorted()
        assertEquals((1..8).map { "s1.$it.json" }.sorted(), saved)
        val labels = saved.map { (SessionCodec.decode(File(dir, it).readText()) as DecodeResult.Ok).record.sourceLabel }.toSet()
        assertEquals((1..8).map { "save-$it" }.toSet(), labels)
    }

    @Test
    fun inconsistentOutcomeAndLowercaseVinAreRejected() {
        val dir = tmp.newFolder()
        val valid = SessionCodec.encode(record(id = "x"))
        File(dir, "x.1.json").writeText(valid.replace("\"complete\": true", "\"complete\": false"))
        assertEquals(
            "complete flag contradicts outcome COMPLETE",
            (FileSessionStore(dir).list().single() as StoredSession.Unreadable).reason,
        )
        assertTrue(FileSessionStore(tmp.newFolder()).save(record(label = "wp1zzz92zela12345")) is SaveResult.Failed)
    }

    @Test
    fun saveRecreatesAMissingDirectory() {
        val dir = File(tmp.newFolder(), "sessions")
        val store = FileSessionStore(dir)
        dir.deleteRecursively()
        assertTrue(store.save(record()) is SaveResult.Saved)
        assertEquals(record(), store.load("s1"))
    }

    @Test
    fun unsafeIdsAreRejected() {
        val dir = tmp.newFolder()
        assertTrue(FileSessionStore(dir).save(record(id = "../escape")) is SaveResult.Failed)
        assertNull(FileSessionStore(dir).load("../escape"))
    }
}
