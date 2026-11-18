package io.github.kauri_off.kiroku.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.Looper
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import io.github.kauri_off.kiroku.wiring.WithoutScreenOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

// The tap kept by scheduled work (N9): the receiver hands it over, each try schedules the next while it waits, each tap's
// waiting ends a day after it, and a sign-out ends them all. On Android 11, where the system runs such work as a foreground
// service, on Android 12, where it no longer does, and on a current one.
@RunWith(RobolectricTestRunner::class)
@Config(application = TapsApplication::class, sdk = [30, 31, 35], qualifiers = "en")
class MarkWatchedWorkTest {
    private val application = RuntimeEnvironment.getApplication() as TapsApplication
    private val app get() = application.app
    private val work get() = WorkManager.getInstance(application)

    // Scheduled work's own tasks (its records), which a test may hold to see what waits for them.
    private val tasks = SteppedExecutor()

    private val title = FixtureAnime.exactToWatch
    private val said = Shown(4, 1, 4, NOW, NOW)
    private val mark = MarkWatched(SOURCE, title.id, 4, said)
    private val signIn = SignInId("a")

    @Before
    fun scheduledWork() = TestScheduledWork.install(application, tasks)

    @After
    fun noScheduledWork() {
        // A test that failed while holding them would leave the end waiting for them.
        tasks.refusing = false
        tasks.release()
        TestScheduledWork.end()
    }

    private fun receiver() =
        shadowOf(application)
            .registeredReceivers
            .map { it.broadcastReceiver }
            .filterIsInstance<MarkWatchedReceiver>()
            .single()

    private fun pending() = shadowOf(receiver()).originalPendingResult

    private fun released() = pending()?.let { shadowOf(it).future.isDone } == true

    // A tap on the action of [on], and its broadcast let go.
    private fun tapped(on: MarkWatched = mark) {
        val before = pending()
        application.sendBroadcast(markWatchedIntent(application, on))
        until { pending() !== before && released() }
    }

    private fun until(condition: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < limit) { "Not within $WAIT_SECONDS s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL)
        }
    }

    private fun tries(of: MarkWatched = mark): List<WorkInfo> = work.getWorkInfosForUniqueWork(MarkWatchedWork.triesName(of)).get()

    private fun all(source: String = SOURCE): List<WorkInfo> = work.getWorkInfosByTag(MarkWatchedWork.sourceTag(source)).get()

    // The ends of the waiting of the taps on [of], one a tap.
    private fun ends(of: MarkWatched = mark): List<WorkInfo> {
        val tries = tries(of).map { it.id }.toSet()
        return all(of.source).filter { it.id !in tries }
    }

    // Runs work whose constraints and time have come, and waits for it to end.
    private fun ran(id: UUID): WorkInfo {
        TestScheduledWork.run(application, work.getWorkInfoById(id).get()!!)
        lateinit var info: WorkInfo
        until {
            work
                .getWorkInfoById(id)
                .get()!!
                .also { info = it }
                .state.isFinished
        }
        return info
    }

    private fun shown(): Notification? =
        application
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .singleOrNull { it.tag == EpisodeNotifications.tag(SOURCE, title.id) && it.id == EpisodeNotifications.EPISODE_ID }
            ?.notification

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `a tap is held until scheduled work keeps it, a try at once with a connection, and the end of the waiting a day later`() {
        tasks.held = true
        application.sendBroadcast(markWatchedIntent(application, mark))
        until { shadowOf(receiver()).wentAsync() }
        Thread.sleep(HELD)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("let go before scheduled work kept the tap", released())

        tasks.release()
        until { released() }

        val first = tries().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertEquals(0L, first.initialDelayMillis)
        assertTrue(MarkWatchedWork.tryOf(Tap(mark, NOW), Duration.ZERO).workSpec.expedited)
        val waitingEnds = ends().single()
        assertEquals(WorkInfo.State.ENQUEUED, waitingEnds.state)
        assertEquals(NetworkType.NOT_REQUIRED, waitingEnds.constraints.requiredNetworkType)
        val day = MarkWatchedTries.WAITED_AT_MOST.toMillis()
        assertTrue("${waitingEnds.initialDelayMillis}", waitingEnds.initialDelayMillis in day - WAIT_SECONDS * 1000..day)
        // Nothing is sent before the try runs.
        assertEquals(emptyList<ListWrite>(), app.sent.toList())

        assertEquals(WorkInfo.State.SUCCEEDED, ran(first.id).state)
        assertEquals(listOf<ListWrite>(ListWrite.MarkWatched(title, 4, signIn)), app.sent.toList())
        assertEquals(listOf(RunTrigger.MarkedWatched), app.runs.started.toList())
    }

    // Scheduled work's own records could not be written: the tap is not lost without a word.
    @Test
    fun `a tap that scheduled work cannot keep says at once that it could not be saved`() {
        tasks.refusing = true

        tapped()

        assertEquals("Could not save: episode\u00A04\u00A0watched", shown()?.text())
        assertEquals(emptyList<ListWrite>(), app.sent.toList())
    }

    @Test
    fun `a try that has to wait schedules the next at the end of the wait, in the same sign-in's list, also waiting for a connection`() {
        app.answers += ListWork.Refused(Duration.ofMinutes(15))
        app.answers += ListWork.Done(Outcome.Failure(DataError.NoConnection))
        tapped()

        ran(tries().single().id)

        val next = tries().single { !it.state.isFinished }
        assertEquals(Duration.ofMinutes(15).toMillis(), next.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
        assertFalse(MarkWatchedWork.tryOf(Tap(mark, NOW), Duration.ofMinutes(15)).workSpec.expedited)
        ran(next.id)
        val third = tries().single { !it.state.isFinished }
        assertEquals(MarkWatchedTries.waitAfter(1).toMillis(), third.initialDelayMillis)
        // The sign-in changes while it waits: the tap is not its user's, and nothing is sent or shown.
        app.session = Session.SignedIn(SignInId("b"))
        ran(third.id)
        assertEquals(listOf<ListWrite>(ListWrite.MarkWatched(title, 4, signIn)), app.sent.distinct())
        assertEquals(2, app.sent.size)
        assertTrue(tries().all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(emptyList<RunTrigger>(), app.runs.started.toList())
        assertNull(shown())
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

        assertEquals(listOf<ListWrite>(ListWrite.MarkWatched(title, 4, signIn), ListWrite.MarkWatched(title, 5, signIn)), app.sent.toList())
    }

    // A-L4: each tap's waiting ends a day after it, whatever taps come later.
    @Test
    fun `at the end of a tap's waiting its tries still waiting for a connection say so and are left, and a later tap keeps its own day`() {
        runBlocking { application.shown.post(title, said, alert = true) }
        tapped()
        Thread.sleep(2)
        tapped(mark.copy(episode = 5))
        val (firstEnd, secondEnd) =
            ends().sortedBy { info ->
                info.tags
                    .single { it.startsWith(END) }
                    .substringAfterLast(':')
                    .toLong()
            }
        val waiting = tries()

        assertEquals(WorkInfo.State.SUCCEEDED, ran(firstEnd.id).state)

        assertEquals(emptyList<ListWrite>(), app.sent.toList())
        assertEquals("Could not save: episode\u00A04\u00A0watched", shown()?.text())
        // Neither tap's tries are cut off: they end themselves, and the later tap has its own end still to come.
        assertEquals(waiting.map { it.state }, tries().map { it.state })
        assertEquals(WorkInfo.State.ENQUEUED, work.getWorkInfoById(secondEnd.id).get()!!.state)
    }

    @Test
    fun `once the tries have ended, the end of the waiting does nothing`() {
        tapped()
        ran(tries().single().id)
        val sent = app.sent.toList()

        ran(ends().single().id)

        assertEquals(sent, app.sent.toList())
        assertNull(shown())
    }

    // S9: a sign-out ends the source's taps, and scheduled work keeps nothing of them; another source's are not its own.
    @Test
    fun `a sign-out ends every tap of its source and leaves nothing of them in scheduled work`() {
        tapped()
        tapped(mark.copy(source = OTHER))
        val ended = all()
        assertEquals(2, ended.size)

        runBlocking { MarkWatchedWork.forget(application, SOURCE) }

        assertEquals(emptyList<WorkInfo>(), all())
        for (info in ended) assertNull(work.getWorkInfoById(info.id).get())
        assertEquals(2, all(OTHER).size)
        assertEquals(emptyList<ListWrite>(), app.sent.toList())
    }

    @Test
    fun `a try with nobody signed in, or another sign-in than the tap's, sends and shows nothing`() {
        runBlocking { application.shown.post(title, said, alert = true) }
        val tries = MarkWatchedTries(app, Clock.systemUTC()) { error("Reported: $it") }
        val notification = shown()

        app.session = Session.SignedOut
        assertEquals(Tried.Dropped, runBlocking { tries.attempt(mark, Instant.now(), 0) })
        runBlocking { tries.gaveUp(mark) }
        app.session = Session.SignedIn(signIn)
        assertEquals(Tried.Dropped, runBlocking { tries.attempt(mark, Instant.now(), 1, SignInId("b")) })
        runBlocking { tries.gaveUp(mark, SignInId("b")) }

        assertEquals(emptyList<ListWrite>(), app.sent.toList())
        // A sign-out that comes between the try's look at the session and its write.
        app.answers += ListWork.SignedOut
        assertEquals(Tried.Dropped, runBlocking { tries.attempt(mark, Instant.now(), 0) })
        assertEquals(notification?.text(), shown()?.text())
    }

    @Test
    fun `a write that throws counts as failed, is reported, and its notification says so`() {
        app.thrown = IllegalStateException(BROKEN)
        val unexpected = CopyOnWriteArrayList<Throwable>()

        val tried = runBlocking { MarkWatchedTries(app, Clock.systemUTC(), unexpected::add).attempt(mark, Instant.now(), 0) }

        assertEquals(Tried.NotSaved, tried)
        assertEquals(listOf(BROKEN), unexpected.map { it.message })
        assertEquals("Could not save: episode\u00A04\u00A0watched", shown()?.text())
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
        const val OTHER = "other"
        const val END = "mark-watched-end:"
        const val BROKEN = "broken"
        const val WAIT_SECONDS = 10L
        const val POLL = 10L

        // Long enough for a broadcast let go too early to show it.
        const val HELD = 300L
        val NOW: Instant = Instant.parse("2026-11-12T09:00:00Z")
    }
}

// Runs a task at once, or, while held, when released; while refusing, takes none, as a store that cannot be written.
class SteppedExecutor : Executor {
    @Volatile
    var held = false

    @Volatile
    var refusing = false

    private val waiting = ConcurrentLinkedQueue<Runnable>()

    override fun execute(command: Runnable) {
        if (refusing) throw RejectedExecutionException("Scheduled work's store takes nothing")
        if (held) waiting += command else command.run()
    }

    fun release() {
        held = false
        while (true) (waiting.poll() ?: return).run()
    }
}

// The application of these tests: the app as code without a screen has it, with the list's answers scripted.
class TapsApplication :
    Application(),
    WithoutScreenOwner,
    HeldRunsOwner {
    val shown by lazy { EpisodeNotifications(this, "test", { TitleLanguage.Romaji }, kotlin.time.Duration.ZERO) }
    val app by lazy { ScriptedList(shown) }
    override val withoutScreen: WithoutScreen get() = app
    override val heldRuns = HeldRuns(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { null }
}

class ScriptedList(
    override val notSaved: EpisodeNotifications,
) : WithoutScreen {
    val sent = CopyOnWriteArrayList<ListWrite>()

    // Answered in turn; once none is left, every write succeeds.
    val answers = CopyOnWriteArrayList<ListWork<Outcome<Unit>>>()

    @Volatile
    var thrown: Exception? = null

    @Volatile
    var session: Session = Session.SignedIn(SignInId("a"))

    val runs = RecordedRuns()
    override val notificationRuns get() = runs

    override suspend fun syncList(): ListWork<Outcome<Unit>> = error("Not asked")

    override suspend fun updateAiring(): Outcome<Unit> = error("Not asked")

    override suspend fun sendToList(write: ListWrite): ListWork<Outcome<Unit>> {
        thrown?.let { throw it }
        sent += write
        // As the list's engine refuses a write bound to a list that is not the session's.
        if (write.list != null &&
            write.list != (session as? Session.SignedIn)?.signIn
        ) {
            return ListWork.Done(Outcome.Failure(DataError.ListEnded))
        }
        return answers.removeFirstOrNull() ?: ListWork.Done(Outcome.Success(Unit))
    }

    override suspend fun titleOnList(id: AnimeId): Anime? = listOf(FixtureAnime.exactToWatch).firstOrNull { it.id == id }

    override suspend fun session(): Session = session
}
