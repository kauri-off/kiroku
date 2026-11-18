package io.github.kauri_off.kiroku.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import io.github.kauri_off.kiroku.wiring.WithoutScreenOwner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// The tap kept by scheduled work (N9): the receiver hands it over, each try schedules the next while it waits, and the
// waiting ends a day after the tap. On Android 11, where the system runs such work as a foreground service, and on a current one.
@RunWith(RobolectricTestRunner::class)
@Config(application = TapsApplication::class, sdk = [30, 35], qualifiers = "en")
class MarkWatchedWorkTest {
    private val application = RuntimeEnvironment.getApplication() as TapsApplication
    private val app get() = application.app
    private val work get() = WorkManager.getInstance(application)
    private val driver get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(application))

    private val title = FixtureAnime.exactToWatch
    private val mark = MarkWatched(SOURCE, title.id, 4)

    @Before
    fun scheduledWork() {
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(application, configuration)
    }

    // Scheduled work is one for the JVM: left set up, a later test would find it set up before its app asked for it.
    @After
    fun noScheduledWork() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun tapped(on: MarkWatched = mark) {
        application.sendBroadcast(markWatchedIntent(application, on))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tries(of: MarkWatched = mark): List<WorkInfo> = work.getWorkInfosForUniqueWork(MarkWatchedWork.triesName(of)).get()

    private fun end(of: MarkWatched = mark): WorkInfo = work.getWorkInfosForUniqueWork(MarkWatchedWork.endName(of)).get().single()

    // Runs a try whose constraints and time have come, and waits for it to end.
    private fun ran(id: UUID): WorkInfo {
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < until) {
            val info = work.getWorkInfoById(id).get()!!
            if (info.state.isFinished) return info
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL)
        }
        error("$id did not end")
    }

    private fun shown(): Notification? =
        application
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .singleOrNull { it.tag == EpisodeNotifications.tag(SOURCE, title.id) && it.id == EpisodeNotifications.EPISODE_ID }
            ?.notification

    @Test
    fun `a tap is held until scheduled work keeps it, a try at once with a connection, and the end of the waiting a day later`() {
        val receiver =
            shadowOf(application)
                .registeredReceivers
                .map { it.broadcastReceiver }
                .filterIsInstance<MarkWatchedReceiver>()
                .single()

        tapped()

        assertTrue(shadowOf(receiver).wentAsync())
        assertTrue(shadowOf(shadowOf(receiver).originalPendingResult).future.isDone)
        val first = tries().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(0L, first.initialDelayMillis)
        assertTrue(MarkWatchedWork.tryOf(Tap(mark, NOW), Duration.ZERO).workSpec.expedited)
        val waitingEnds = end()
        assertEquals(WorkInfo.State.ENQUEUED, waitingEnds.state)
        assertEquals(NetworkType.NOT_REQUIRED, waitingEnds.constraints.requiredNetworkType)
        val day = MarkWatchedTries.WAITED_AT_MOST.toMillis()
        assertTrue("${waitingEnds.initialDelayMillis}", waitingEnds.initialDelayMillis in day - WAIT_SECONDS * 1000..day)
        // Nothing is sent before the try runs.
        assertEquals(emptyList<ListWrite>(), app.sent.toList())

        assertEquals(WorkInfo.State.SUCCEEDED, ran(first.id).state)
        assertEquals(listOf<ListWrite>(ListWrite.MarkWatched(title, 4)), app.sent.toList())
        assertEquals(listOf(RunTrigger.MarkedWatched), app.runs.started.toList())
    }

    @Test
    fun `a try that has to wait schedules the next at the end of the wait, which also waits for a connection`() {
        app.answers += ListWork.Refused(Duration.ofMinutes(15))
        app.answers += ListWork.Done(Outcome.Failure(DataError.NoConnection))
        tapped()

        ran(tries().single().id)

        val next = tries().single { !it.state.isFinished }
        assertEquals(Duration.ofMinutes(15).toMillis(), next.initialDelayMillis)
        assertFalse(MarkWatchedWork.tryOf(Tap(mark, NOW), Duration.ofMinutes(15)).workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
        ran(next.id)
        val third = tries().single { !it.state.isFinished }
        assertEquals(MarkWatchedTries.waitAfter(1).toMillis(), third.initialDelayMillis)
        ran(third.id)
        assertEquals(3, app.sent.size)
        assertTrue(tries().all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(listOf(RunTrigger.MarkedWatched), app.runs.started.toList())
    }

    @Test
    fun `taps on one title are tried one after another, in the order they came`() {
        tapped()
        tapped(mark.copy(episode = 5))

        val first = tries().single { it.state == WorkInfo.State.ENQUEUED }
        val second = tries().single { it.state == WorkInfo.State.BLOCKED }
        ran(first.id)
        assertEquals(WorkInfo.State.ENQUEUED, work.getWorkInfoById(second.id).get()!!.state)
        ran(second.id)

        assertEquals(listOf<ListWrite>(ListWrite.MarkWatched(title, 4), ListWrite.MarkWatched(title, 5)), app.sent.toList())
    }

    @Test
    fun `at the end of the waiting, tries that still wait for a connection end with nothing sent, and the notification says so`() {
        runBlocking { application.shown.post(title, Shown(4, 1, 4, NOW, NOW), alert = true) }
        tapped()
        val waiting = tries().single()

        assertEquals(WorkInfo.State.SUCCEEDED, ran(end().id).state)

        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfoById(waiting.id).get()!!.state)
        assertEquals(emptyList<ListWrite>(), app.sent.toList())
        assertEquals("Could not save: episode\u00A04\u00A0watched", shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun `once the tries have ended, the end of the waiting does nothing`() {
        tapped()
        ran(tries().single().id)
        val sent = app.sent.toList()

        ran(end().id)

        assertEquals(sent, app.sent.toList())
        assertEquals(null, shown())
    }

    @Test
    fun `a write that throws counts as failed, is reported, and its notification says so`() {
        app.thrown = IllegalStateException(BROKEN)
        val unexpected = CopyOnWriteArrayList<Throwable>()

        val tried = runBlocking { MarkWatchedTries(app, Clock.systemUTC(), unexpected::add).attempt(mark, Instant.now(), 0) }

        assertEquals(Tried.NotSaved, tried)
        assertEquals(listOf(BROKEN), unexpected.map { it.message })
        assertEquals("Could not save: episode\u00A04\u00A0watched", shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    // N9: before Android 12 the work runs as a foreground service, which shows "Saving…" on its own quiet channel; from 12 none runs.
    @Test
    fun `the foreground service is there before Android 12 alone, and says it is saving on its own low-importance channel`() {
        assertEquals(Build.VERSION.SDK_INT < Build.VERSION_CODES.S, application.resources.getBoolean(R.bool.saving_in_the_foreground))
        val worker = TestListenableWorkerBuilder.from(application, MarkWatchedWorker::class.java).build() as MarkWatchedWorker

        val info = runBlocking { worker.getForegroundInfo() }

        assertEquals(MarkWatchedWorker.SAVING_ID, info.notificationId)
        assertFalse(info.notificationId in setOf(EpisodeNotifications.EPISODE_ID, EpisodeNotifications.SUMMARY_ID))
        assertEquals(
            "Saving…",
            info.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString(),
        )
        val channel = application.getSystemService(NotificationManager::class.java).getNotificationChannel(info.notification.channelId)
        assertEquals("Saving changes", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    private companion object {
        const val SOURCE = "test"
        const val BROKEN = "broken"
        const val WAIT_SECONDS = 10L
        const val POLL = 10L
        val NOW: Instant = Instant.parse("2026-11-12T09:00:00Z")
    }
}

// The application of these tests: the app as code without a screen has it, with the list's answers scripted.
class TapsApplication :
    Application(),
    WithoutScreenOwner {
    val shown by lazy { EpisodeNotifications(this, "test", { TitleLanguage.Romaji }, kotlin.time.Duration.ZERO) }
    val app by lazy { ScriptedList(shown) }
    override val withoutScreen: WithoutScreen get() = app
}

class ScriptedList(
    override val shown: EpisodeNotifications,
) : WithoutScreen {
    val sent = CopyOnWriteArrayList<ListWrite>()

    // Answered in turn; once none is left, every write succeeds.
    val answers = CopyOnWriteArrayList<ListWork<Outcome<Unit>>>()

    @Volatile
    var thrown: Exception? = null

    val runs = RecordedRuns()
    override val notificationRuns get() = runs

    override suspend fun syncList(): ListWork<Outcome<Unit>> = error("Not asked")

    override suspend fun sendToList(write: ListWrite): ListWork<Outcome<Unit>> {
        thrown?.let { throw it }
        sent += write
        return answers.removeFirstOrNull() ?: ListWork.Done(Outcome.Success(Unit))
    }

    override suspend fun titleOnList(id: AnimeId): Anime? = listOf(FixtureAnime.exactToWatch).firstOrNull { it.id == id }
}
