package io.github.eternalliquet.bamboobd2.core

import io.github.eternalliquet.bamboobd2.core.model.Provenance
import io.github.eternalliquet.bamboobd2.core.reader.FakeReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** G3: a full simulated session runs, saves and reopens on a plain JVM. */
class HeadlessSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun runSaveReopenWithoutAndroid() = runTest {
        val controller = SessionController(this, clock = { testScheduler.currentTime }, ids = { "headless-1" })
        controller.start(FakeReader(FakeScenario.TWO_CODES))
        advanceUntilIdle()
        val record = (controller.state.value as SessionState.Finished).record

        val dir = tmp.newFolder()
        assertTrue(FileSessionStore(dir).save(record) is SaveResult.Saved)
        val reopened = FileSessionStore(dir).load("headless-1")
        assertEquals(record, reopened)
        assertEquals(Provenance.SIMULATED, reopened!!.provenance)

        // No Android classes are reachable from this test's classpath.
        val android = runCatching { Class.forName("android.app.Activity") }
        assertTrue(android.isFailure)
    }
}
