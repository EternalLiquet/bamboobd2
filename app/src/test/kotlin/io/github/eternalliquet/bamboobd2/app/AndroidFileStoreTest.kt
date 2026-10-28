package io.github.eternalliquet.bamboobd2.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Write-once storage in the app's files directory, run at the minSdk and target
 * API levels under Robolectric. Robolectric uses the host JVM's file APIs, so
 * this checks the wiring, not a device's file system behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AndroidFileStoreTest {
    @Test
    fun saveAndReopenInAppFilesDir() {
        val dir = ApplicationProvider.getApplicationContext<Context>().filesDir.resolve("sessions-test")
        dir.deleteRecursively()
        val record = SessionRecord(
            schemaVersion = 1, id = "android-1", startedAt = 1, endedAt = 2,
            provenance = Provenance.SIMULATED, sourceLabel = "fake:no_codes",
            complete = true, outcome = SessionOutcome.COMPLETE,
        )
        assertTrue(FileSessionStore(dir).save(record) is SaveResult.Saved)
        assertTrue(FileSessionStore(dir).save(record) is SaveResult.Saved)
        assertEquals(setOf("android-1.1.json", "android-1.2.json"), dir.list()!!.toSet())
        assertEquals(record, FileSessionStore(dir).load("android-1"))
    }
}
