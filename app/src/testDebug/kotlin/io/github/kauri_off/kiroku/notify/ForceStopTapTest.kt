package io.github.kauri_off.kiroku.notify

import android.app.job.JobScheduler
import android.os.Looper
import androidx.work.WorkManager
import androidx.work.await
import io.github.kauri_off.kiroku.KirokuApplication
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.UUID

// A force-stop drops the app's jobs, and scheduled work is set up only when asked for: the opening asks, and a tap that was
// waiting is on the system's job scheduler again. The real application, on the system's job scheduler, at Android 11: from
// Android 14 scheduled work keeps its jobs in a namespace of the scheduler's that the test's scheduler does not keep.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ForceStopTapTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val source = StartsOn(DataSource.Demo)

    private val application get() = RuntimeEnvironment.getApplication() as KirokuApplication
    private val jobs get() = application.getSystemService(JobScheduler::class.java)

    // The system's jobs of [works].
    private fun jobsOf(works: List<UUID>) = jobs.allPendingJobs.filter { it.extras.getString(WORK_SPEC) in works.map(UUID::toString) }

    // Waits for the system to hold a job of [works], which scheduled work gives it after its records.
    private fun scheduled(works: List<UUID>) {
        val limit = System.nanoTime() + WAIT * 1_000_000
        while (jobsOf(works).isEmpty() && System.nanoTime() < limit) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(PAUSE)
        }
    }

    @Test
    fun `an opening after a force-stop puts a waiting tap back on the system's job scheduler`() {
        try {
            TestScheduledWork.onTheSystem(application)
            // A try that waits for its time, as one after MAL refused.
            val tap = Tap(MarkWatched(DataSource.Demo.key, AnimeId(TITLE), 2, Shown(2, 1, 2, Instant.now(), Instant.now())), Instant.now())
            runBlocking { MarkWatchedWork.next(application, tap, Duration.ofHours(1)).await() }
            val tries =
                WorkManager
                    .getInstance(application)
                    .getWorkInfosByTag(MarkWatchedWork.tapTag(tap))
                    .get()
                    .map { it.id }
            assertEquals(1, tries.size)
            scheduled(tries)
            assertEquals(1, jobsOf(tries).size)

            // A force-stop: the system drops the app's jobs and ends its process.
            jobs.cancelAll()
            TestScheduledWork.died()
            assertEquals(emptyList<Any>(), jobsOf(tries))

            val opened = application.heldRuns.start(RunTrigger.AppOpened)
            runBlocking { withTimeout(WAIT) { opened.join() } }
            scheduled(tries)

            assertEquals(1, jobsOf(tries).size)
            assertTrue(jobsOf(tries).single().minLatencyMillis > 0)
        } finally {
            TestScheduledWork.died()
        }
    }

    private companion object {
        const val TITLE = 900_001L
        const val WAIT = 20_000L
        const val PAUSE = 20L
        const val WORK_SPEC = "EXTRA_WORK_SPEC_ID"
    }
}
