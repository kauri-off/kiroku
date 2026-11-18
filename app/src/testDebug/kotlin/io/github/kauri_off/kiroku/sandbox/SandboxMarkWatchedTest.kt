package io.github.kauri_off.kiroku.sandbox

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.notify.MarkWatched
import io.github.kauri_off.kiroku.notify.MarkWatchedTries
import io.github.kauri_off.kiroku.notify.MarkWatchedWork
import io.github.kauri_off.kiroku.notify.PeriodicWork
import io.github.kauri_off.kiroku.notify.PeriodicWorker
import io.github.kauri_off.kiroku.notify.Tap
import io.github.kauri_off.kiroku.notify.TestScheduledWork
import io.github.kauri_off.kiroku.notify.Tried
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

// "Mark watched" from a notification on the sandbox (N9): each try of a tap as its scheduled work makes it, in one process
// of the app after another over the same files, with no request leaving the device.
@RunWith(RobolectricTestRunner::class)
class SandboxMarkWatchedTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val keystore =
        object : ExternalResource() {
            override fun before() = KeysInMemory.install()

            override fun after() = KeysInMemory.uninstall()
        }

    @get:Rule(order = 1)
    val connections = SandboxAppTest.NoConnection()

    @get:Rule(order = 2)
    val hurried = HurriedAniList()

    @get:Rule(order = 2)
    val time = MovedClock()

    private val context get() = RuntimeEnvironment.getApplication()
    private val unexpected = CopyOnWriteArrayList<Throwable>()

    private class Process(
        val graph: SandboxGraph,
        val scope: CoroutineScope,
    )

    private var process: Process? = null

    private fun <T> waited(block: suspend () -> T): T = runBlocking { withTimeout(WAIT) { block() } }

    // A new process of the app on the sandbox, over the files the one before it left; that one has ended.
    private fun started(): SandboxGraph {
        ended()
        val scope = applicationScope(unexpected::add)
        val graph =
            SandboxGraph.create(context, Clock.systemUTC(), Online) { source, device, clock ->
                LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, unexpected::add, device = device)
            }
        process = Process(graph, scope)
        return graph
    }

    private fun ended() {
        val was = process ?: return
        process = null
        waited {
            was.scope.coroutineContext.job
                .cancelAndJoin()
        }
    }

    @After
    fun end() {
        ended()
        assertEquals(emptyList<String>(), unexpected.map { it.stackTraceToString() })
        assertEquals(emptyList<URI>(), connections.asked)
    }

    private fun now(): Instant = Instant.now().plus(time.ahead)

    private fun SandboxGraph.signedIn() =
        waited {
            signIn.start { error("The sandbox opened a browser") }
            assertEquals(SignInStep.SignedIn, signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
        }

    private fun SandboxGraph.synced() {
        assertEquals(Outcome.Success(Unit), waited { list.sync() })
        assertEquals(Outcome.Success(Unit), waited { airing.refresh() })
    }

    // Signed in, with the list and its airing data downloaded.
    private fun ready(): SandboxGraph = started().also { it.signedIn() }.also { it.synced() }

    private fun SandboxGraph.tries() = MarkWatchedTries(withoutScreen, checkNotNull(SandboxGraph.movedClock), unexpected::add)

    // One try of a tap on [title]'s notification for [episode], tapped at [tappedAt], after [tried] tries.
    private fun SandboxGraph.tried(
        title: AnimeId,
        episode: Int,
        tappedAt: Instant = now(),
        tried: Int = 0,
        madeIn: SignInId? = null,
    ): Tried = waited { tries().attempt(markOf(title, episode), tappedAt, tried, madeIn ?: signIn()) }

    // The action of [title]'s notification as it is shown, or as one for [episode] would be.
    private fun markOf(
        title: AnimeId,
        episode: Int,
        source: String = SOURCE,
    ) = MarkWatched(source, title, episode, shown()[title] ?: Shown(episode, null, episode, now(), now()))

    private fun SandboxGraph.watching(): ListEntry =
        waited { list.observeEntries().first() }.first { it.status == ListStatus.Watching && it.anime.id != soon }

    private fun SandboxGraph.entry(title: AnimeId): ListEntry = checkNotNull(waited { list.observeEntry(title).first() })

    // The count MAL has, as a download brings it.
    private fun SandboxGraph.watchedAtMal(title: AnimeId): Int {
        assertEquals(Outcome.Success(Unit), waited { list.sync() })
        return entry(title).values.watchedEpisodes
    }

    private fun SandboxGraph.requests(since: Int): List<String> = sandbox.mal.asked.drop(since)

    private fun SandboxGraph.asked(): Int = sandbox.mal.asked.size

    private fun SandboxGraph.writes(since: Int): List<String> = requests(since).filter { it.startsWith(PATCH) }

    // What the device shows of the sandbox's notifications, as the engine reads it back.
    private fun shown(): Map<AnimeId, Shown> = waited { EpisodeNotifications(context, SOURCE, { TitleLanguage.Romaji }).shown() }

    private fun posted(title: AnimeId): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .activeNotifications
            .singleOrNull { it.tag == EpisodeNotifications.tag(SOURCE, title) && it.id == EpisodeNotifications.EPISODE_ID }
            ?.notification

    private val soon = AnimeId(SandboxAiring.SOON)

    private fun SandboxGraph.coming(): List<Coming> = waited { notifications.observeComing().first { it != null } }!!

    // Signed in to a sandbox laid out as of now and synced, with the soon title's episode aired since and announced by the alarm's run.
    private fun announced(): SandboxGraph {
        val graph = started()
        graph.signedIn()
        waited { withContext(Dispatchers.IO) { graph.sandbox.reset() } }
        graph.synced()
        waited { graph.withoutScreen.notificationRuns!!.run(RunTrigger.AppOpened) }
        val next = graph.coming().single { it.anime.id == soon }
        time.ahead = time.ahead + Duration.between(now(), next.notifyAt).plusSeconds(1)
        waited { graph.withoutScreen.notificationRuns!!.run(RunTrigger.Alarm) }
        assertEquals(next.episode, shown().getValue(soon).episode)
        return graph
    }

    @Test
    fun `two taps, a tap after a sync brought the change, and a second try after MAL accepted the first raise the count once (N9)`() {
        val graph = ready()
        val title = graph.watching()
        val next = title.values.watchedEpisodes + 1
        val before = graph.asked()

        assertEquals(Tried.Saved, graph.tried(title.anime.id, next))
        assertEquals(Tried.Saved, graph.tried(title.anime.id, next))
        // A new process, as a second try of the same tap after MAL had accepted the first.
        val later = started()
        assertEquals(Tried.Saved, later.tried(title.anime.id, next, tried = 1))

        assertEquals(listOf(PATCH + ENTRY.format(title.anime.id.value)), later.writes(0) + graph.writes(before))
        assertEquals(next, later.watchedAtMal(title.anime.id))

        // The next episode marked by a screen, and the list synced, before its notification's action is tapped.
        val another = later.entry(title.anime.id)
        assertEquals(Outcome.Success(Unit), waited { later.list.send(ListWrite.MarkWatched(another.anime, next + 1)) })
        assertEquals(next + 1, later.watchedAtMal(title.anime.id))
        val synced = later.asked()
        assertEquals(Tried.Saved, later.tried(title.anime.id, next + 1))
        assertEquals(emptyList<String>(), later.writes(synced))
        assertEquals(next + 1, later.watchedAtMal(title.anime.id))
    }

    @Test
    fun `a tap for an episode that is not the next unwatched one changes nothing, and its notification says it could not be saved`() {
        val graph = announced()
        val title = graph.entry(soon)
        val watched = title.values.watchedEpisodes
        val notification = shown().getValue(soon)
        val before = graph.asked()

        for (episode in listOf(watched + 2, watched + 5)) assertEquals(Tried.NotSaved, graph.tried(soon, episode))

        assertEquals(emptyList<String>(), graph.writes(before))
        assertEquals(watched, graph.watchedAtMal(soon))
        // The title's notification says so in its place, and the engine takes it for the notification it posted: a run keeps it.
        assertEquals(COULD_NOT_SAVE.format(watched + 5), posted(soon)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(notification, shown().getValue(soon))
        waited { graph.withoutScreen.notificationRuns!!.run(RunTrigger.AppOpened) }
        assertEquals(COULD_NOT_SAVE.format(watched + 5), posted(soon)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, posted(soon)!!.actions.orEmpty().size)
    }

    @Test
    fun `a write that succeeded leaves the notification the list asks for (N7)`() {
        val graph = announced()
        val title = graph.entry(soon)
        val announced = shown().getValue(soon)
        val watched = title.values.watchedEpisodes
        assertEquals(watched + 1, announced.marks)

        assertEquals(Tried.Saved, graph.tried(soon, watched + 1))

        // Watched up to the announced episode, it goes; short of it, it stays and names the next episode, posted silently.
        if (watched + 1 >= announced.episode) {
            assertEquals(null, shown()[soon])
        } else {
            assertEquals(announced.copy(toWatch = announced.toWatch?.minus(1), marks = watched + 2), shown().getValue(soon))
            assertTrue(posted(soon)!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }
    }

    @Test
    fun `offline the write waits, sends nothing, and is sent when the connection returns`() {
        val graph = ready()
        val title = graph.watching()
        val next = title.values.watchedEpisodes + 1
        graph.scenarios.set(Scenario(offline = true))
        val before = graph.asked()

        val waited = graph.tried(title.anime.id, next)

        assertEquals(Tried.Again(MarkWatchedTries.waitAfter(0)), waited)
        assertEquals(emptyList<String>(), graph.writes(before))
        graph.scenarios.set(Scenario())
        assertEquals(Tried.Saved, graph.tried(title.anime.id, next, tried = 1))
        assertEquals(1, graph.writes(before).size)
        assertEquals(next, graph.watchedAtMal(title.anime.id))
    }

    // MAL refuses a write, which sets its back-off.
    private fun SandboxGraph.refused() {
        val other = waited { list.observeEntries().first() }.last { it.status == ListStatus.Watching }
        scenarios.set(Scenario(writes = WriteFault.Refused))
        assertEquals(Outcome.Failure(DataError.Refused), waited { list.send(ListWrite.MarkNextWatched(other.anime)) })
        scenarios.set(Scenario())
        assertTrue(File(context.noBackupFilesDir, "sandbox.backoff").isFile)
    }

    @Test
    fun `during MAL's refusal no request is sent before its end, and the write is sent after it`() {
        val graph = ready()
        val title = graph.watching()
        val next = title.values.watchedEpisodes + 1
        graph.refused()
        val before = graph.asked()

        val waiting = graph.tried(title.anime.id, next)

        assertTrue("$waiting", waiting is Tried.Again)
        val wait = (waiting as Tried.Again).after
        assertTrue("$wait", wait > Duration.ofMinutes(14) && wait <= Duration.ofMinutes(15))
        // A try that comes early sends nothing either, and waits for what is left.
        time.ahead = time.ahead + wait.minusMinutes(1)
        val early = graph.tried(title.anime.id, next, tried = 1)
        assertTrue("$early", early is Tried.Again && early.after <= Duration.ofMinutes(1))
        assertEquals(emptyList<String>(), graph.requests(before))

        time.ahead = time.ahead + (early as Tried.Again).after
        assertEquals(Tried.Saved, graph.tried(title.anime.id, next, tried = 2))
        assertEquals(1, graph.writes(before).size)
        assertEquals(next, graph.watchedAtMal(title.anime.id))
    }

    @Test
    fun `tokens that are due are renewed before the write`() {
        val graph = ready()
        val title = graph.watching()
        time.ahead = DUE
        val before = graph.asked()

        assertEquals(Tried.Saved, graph.tried(title.anime.id, title.values.watchedEpisodes + 1))

        val sent = graph.requests(before)
        assertEquals(TOKEN, sent.first())
        assertEquals(listOf(PATCH + ENTRY.format(title.anime.id.value)), sent.filter { it.startsWith(PATCH) })
    }

    // The process dies after the tap was taken and before MAL answers: the work's next run, in a new process, sends it once.
    @Test
    fun `a write whose process died before MAL answered is sent once by the next try`() {
        var graph = ready()
        // MAL made the write and its answer was lost; and the process died before the write was sent.
        for (answered in listOf(true, false)) {
            val title = graph.watching()
            val watched = graph.watchedAtMal(title.anime.id)
            val before = graph.asked()
            graph.sandbox.mal.delayMillis = ANSWER_TAKES
            val awaited = if (answered) PATCH else GET
            val trying = graph.tryingAside(markOf(title.anime.id, watched + 1))
            waited { while (graph.requests(before).none { it.startsWith(awaited) }) delay(POLL) }
            // The process dies: its try first, then the rest of it.
            waited { trying.cancelAndJoin() }
            ended()
            // MAL makes what it was sent, and its answer reaches nobody.
            Thread.sleep(ANSWER_TAKES + POLL)

            val later = started()
            assertEquals("$answered", Tried.Saved, later.tried(title.anime.id, watched + 1, tried = 1))

            assertEquals("$answered", 1, (graph.writes(before) + later.writes(0)).size)
            assertEquals("$answered", watched + 1, later.watchedAtMal(title.anime.id))
            graph = later
        }
    }

    // A try in a thread of its own, as scheduled work makes it, which the test can end as the process's death would.
    private fun SandboxGraph.tryingAside(mark: MarkWatched): Job {
        val trying = Job()
        val madeIn = signIn()
        thread { runBlocking(trying) { tries().attempt(mark, now(), 0, madeIn) } }
        return trying
    }

    @Test
    fun `the waiting ends a day after the tap, and a try that starts later, or would wait past it, sends nothing and says so`() {
        val graph = announced()
        val title = graph.entry(soon)
        val next = title.values.watchedEpisodes + 1
        val before = graph.asked()

        assertEquals(Tried.NotSaved, graph.tried(soon, next, tappedAt = now().minus(MarkWatchedTries.WAITED_AT_MOST)))
        assertEquals(emptyList<String>(), graph.requests(before))
        assertEquals(COULD_NOT_SAVE.format(next), posted(soon)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())

        // MAL's refusal outlasts what is left of the day.
        graph.refused()
        val refused = graph.asked()
        val late = now().minus(MarkWatchedTries.WAITED_AT_MOST).plusSeconds(600)
        assertEquals(Tried.NotSaved, graph.tried(soon, next, tappedAt = late))
        assertEquals(emptyList<String>(), graph.requests(refused))
        // A wait that ends within the day is waited.
        val early = now().minus(MarkWatchedTries.WAITED_AT_MOST).plus(Duration.ofMinutes(20))
        assertTrue(graph.tried(soon, next, tappedAt = early) is Tried.Again)
        assertEquals(next - 1, graph.watchedAtMal(soon))
    }

    @Test
    fun `a tap on another source's notification sends nothing to this one's list`() {
        val graph = ready()
        val title = graph.watching()
        val before = graph.asked()

        val tried =
            waited { graph.tries().attempt(markOf(title.anime.id, title.values.watchedEpisodes + 1, "account"), now(), 0, graph.signIn()) }

        assertEquals(Tried.Dropped, tried)
        assertEquals(emptyList<String>(), graph.requests(before))
    }

    private fun SandboxGraph.signIn(): SignInId = (waited { session.observeSession().first() } as Session.SignedIn).signIn

    // Everything of the sandbox's on the device, the group's summary included.
    private fun ours() =
        shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications.filter {
            it.tag?.contains(":$SOURCE") == true
        }

    // S9: a sign-out takes the user's taps with it.
    @Test
    fun `a tap waiting at a sign-out ends with it, sends and shows nothing, and leaves no work`() {
        TestScheduledWork.install(context)
        val graph = announced()
        val next = graph.entry(soon).values.watchedEpisodes + 1
        val madeIn = graph.signIn()
        graph.scenarios.set(Scenario(offline = true))
        val tap = Tap(markOf(soon, next), now(), madeIn)
        waited { MarkWatchedWork.tapped(context, tap).forEach { it.await() } }
        assertTrue(graph.tried(soon, next, madeIn = madeIn) is Tried.Again)
        assertEquals(
            2,
            WorkManager
                .getInstance(context)
                .getWorkInfosByTag(MarkWatchedWork.sourceTag(SOURCE))
                .get()
                .size,
        )
        graph.scenarios.set(Scenario())
        PeriodicWork.schedule(context)
        val periodic = listOf(PeriodicWork.ONLINE, PeriodicWork.STORED).associateWith { TestScheduledWork.periodic(context, it).single() }
        val before = graph.asked()

        waited { graph.session.signOut() }

        assertEquals(emptyList<Any>(), WorkManager.getInstance(context).getWorkInfosByTag(MarkWatchedWork.sourceTag(SOURCE)).get())
        // The periodic works are nobody's: they stay as they were, named by their worker alone, and run signed out as the app does.
        for ((name, was) in periodic) {
            val now = TestScheduledWork.periodic(context, name).single()
            assertEquals(was.id to WorkInfo.State.ENQUEUED, now.id to now.state)
            assertEquals(setOf(PeriodicWorker::class.java.name), now.tags)
        }
        // A try that a failed cancel would have left finds nobody signed in.
        assertEquals(Tried.Dropped, graph.tried(soon, next, madeIn = madeIn))
        waited { graph.tries().gaveUp(tap.mark, madeIn) }
        assertEquals(emptyList<String>(), graph.writes(before))
        assertEquals(emptyList<Any>(), ours())
    }

    // As an Undo and a Retry are, a tap is bound to the sign-in whose list it was made in.
    @Test
    fun `a tap made in another sign-in's list sends nothing and shows nothing`() {
        val graph = announced()
        val shownBefore = posted(soon)?.extras?.getCharSequence(Notification.EXTRA_TEXT)
        val before = graph.asked()

        assertEquals(Tried.Dropped, graph.tried(soon, graph.entry(soon).values.watchedEpisodes + 1, madeIn = SignInId("another")))

        assertEquals(emptyList<String>(), graph.requests(before))
        assertEquals(shownBefore, posted(soon)?.extras?.getCharSequence(Notification.EXTRA_TEXT))
    }

    // A-F2: the person dismissed the notification before the write failed; the failure is still the engine's to take back.
    @Test
    fun `could not save for a dismissed notification goes when the list shows the episode watched, and at a sign-out with its summary`() {
        for (signingOut in listOf(false, true)) {
            val graph = announced()
            val title = graph.entry(soon)
            val announced = shown().getValue(soon)
            val mark = markOf(soon, title.values.watchedEpisodes + 2)
            context
                .getSystemService(
                    NotificationManager::class.java,
                ).cancel(EpisodeNotifications.tag(SOURCE, soon), EpisodeNotifications.EPISODE_ID)

            assertEquals(Tried.NotSaved, waited { graph.tries().attempt(mark, now(), 0, graph.signIn()) })
            assertEquals(COULD_NOT_SAVE.format(mark.episode), posted(soon)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(announced, shown().getValue(soon))

            if (signingOut) {
                waited { graph.session.signOut() }
            } else {
                // A screen marks the announced episode, as a sync would bring it.
                assertEquals(Outcome.Success(Unit), waited { graph.list.send(ListWrite.SetWatched(title.anime, announced.episode)) })
                waited { graph.withoutScreen.notificationRuns!!.run(RunTrigger.Changed) }
            }
            assertEquals("$signingOut", emptyList<Any>(), ours())
            if (!signingOut) waited { graph.session.signOut() }
            ended()
        }
    }

    // A device with a connection, which a test's has not.
    private object Online : Device {
        override fun isOnline() = true

        override fun observeOnline(): Flow<Boolean> = flowOf(true)

        override fun isMainThread() = Looper.getMainLooper().thread === Thread.currentThread()
    }

    private companion object {
        const val WAIT = 20_000L
        const val SOURCE = "sandbox"
        const val PATCH = "PATCH"
        const val GET = "GET sandbox.invalid/v2/anime/"
        const val ENTRY = " sandbox.invalid/v2/anime/%d/my_list_status"
        const val TOKEN = "POST sandbox.invalid/v1/oauth2/token"
        const val COULD_NOT_SAVE = "Could not save: episode\u00A0%d\u00A0watched"
        const val ANSWER_TAKES = 1_500L
        const val POLL = 20L

        // The age at which tokens are renewed, and a little more.
        val DUE: Duration = Duration.ofDays(7).plusHours(1)
    }
}
