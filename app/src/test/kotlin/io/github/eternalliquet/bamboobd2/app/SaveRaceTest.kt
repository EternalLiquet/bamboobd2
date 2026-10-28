package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.eternalliquet.bamboobd2.core.model.SessionOutcome
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression: a save that completes after the user has left its session must not
 * mark the next session as saved. Runs under Robolectric (JVM), not on a device.
 */
@RunWith(RobolectricTestRunner::class)
class SaveRaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val main = StandardTestDispatcher()

    /** Holds store work dispatched to IO until the test releases it. */
    private class GatedIo : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val pending: Int get() = queue.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun releaseAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun AppViewModel.finished() = (session.value as SessionState.Finished).record

    @Test
    fun olderSaveCompletingLaterDoesNotMarkNewSessionSaved() {
        val dir = tmp.newFolder()
        val store = FileSessionStore(dir)
        val io = GatedIo()
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>(), store, io)

        // Session A finishes and its save starts, but the write is held.
        vm.start(FakeScenario.TWO_CODES)
        main.scheduler.advanceUntilIdle()
        val a = vm.finished()
        vm.save()
        main.scheduler.runCurrent()
        assertEquals(1, io.pending)
        assertEquals(SaveStatus.Saving, vm.saveStatus.value[a.id])

        // Leave A and start B while A's save is still in flight.
        vm.finishSession()
        vm.start(FakeScenario.NO_CODES)
        main.scheduler.runCurrent()

        // A's write completes now, while B is running.
        io.releaseAll()
        main.scheduler.runCurrent()
        assertEquals(SaveStatus.Done(SaveResult.Saved("${a.id}.1.json", 1)), vm.saveStatus.value[a.id])

        // B is cancelled with partial data. It must still be unsaved and savable.
        vm.cancel()
        main.scheduler.advanceUntilIdle()
        io.releaseAll() // list refresh queued by A's save
        val b = vm.finished()
        assertEquals(SessionOutcome.CANCELLED, b.outcome)
        assertNull(vm.saveStatus.value[b.id])

        vm.save()
        main.scheduler.runCurrent()
        io.releaseAll()
        main.scheduler.advanceUntilIdle()
        io.releaseAll()
        assertEquals(SaveStatus.Done(SaveResult.Saved("${b.id}.1.json", 1)), vm.saveStatus.value[b.id])

        // Both sessions were written exactly once and reopen intact.
        assertEquals(setOf("${a.id}.1.json", "${b.id}.1.json"), dir.list()!!.toSet())
        assertEquals(a, store.load(a.id))
        assertEquals(b, store.load(b.id))
        assertTrue(b.readings.size < 4)
    }
}
