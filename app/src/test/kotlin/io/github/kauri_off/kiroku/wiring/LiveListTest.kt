package io.github.kauri_off.kiroku.wiring

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredList
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.list.ListContent
import io.github.kauri_off.kiroku.list.ListViewModel
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.title.TitlePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

// The live graph's list over its own database and MAL's client, MAL being recorded answers behind a local server.
@RunWith(RobolectricTestRunner::class)
class LiveListTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    @get:Rule
    val mal = ServedMal()

    private val context get() = RuntimeEnvironment.getApplication()
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val defaults = ListStatus.entries.associateWith(TabSort::defaultFor)

    private val online =
        object : Device {
            override fun isOnline() = true

            override fun observeOnline() = flowOf(true)

            override fun isMainThread() = false
        }

    // The graphs' processes run on real threads: a sign-in's time limit is real time, not the test's.
    private val processes = CopyOnWriteArrayList<CoroutineScope>()

    private fun process(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { processes += it }

    @Before
    fun keys() = KeysInMemory.install()

    @After
    fun end() {
        runBlocking { processes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        KeysInMemory.uninstall()
    }

    private fun parts(scope: CoroutineScope) =
        LiveParts(context, LiveSource("served", mal.hosts), "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = online)

    // A whole sign-in: MAL's page is opened, and the browser comes back with a code for its state.
    private suspend fun LiveGraph.signInOnce() {
        var address: String? = null
        signIn.start {
            address = it
            true
        }
        val state = checkNotNull(address).toHttpUrl().queryParameter("state")
        signIn.redirected("${BuildConfig.OAUTH_REDIRECT}?code=the-code&state=$state")
        assertEquals(SignInStep.SignedIn, signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
    }

    private suspend fun LiveGraph.ids(): Set<Long> =
        list
            .observeEntries()
            .first()
            .map { it.anime.id.value }
            .toSet()

    private fun TestScope.store(parts: LiveParts) = RoomLocalList(parts.database, backgroundScope, Dispatchers.IO) {}

    @Test
    fun `a sign-in and its download fill the list with MAL's pages, and nothing is written`() =
        runTest(dispatcher) {
            val graph = LiveGraph(parts(process()))
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())

            graph.signInOnce()
            assertEquals(emptySet<Long>(), graph.ids())
            assertEquals(Outcome.Success(Unit), graph.list.sync())

            assertEquals(ServedMal.ids(mal.pages).toSet(), graph.ids())
            assertEquals(30, graph.ids().size)
            assertNotNull(graph.list.observeSyncedAt().first())
            assertTrue(mal.asked.toString(), mal.asked.all { it.startsWith("GET ") || it.startsWith("POST /v1/oauth2/token ") })
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `an entry the app repaired on reading is kept as MAL gave it and is not written back`() =
        runTest(dispatcher) {
            val unknown = 63537L
            mal.pages = listOf(ServedMal.altered(mal.pages[0], mapOf(unknown to ("status" to "rewatching")))) + mal.pages.drop(1)
            val graph = LiveGraph(parts(process()))
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())

            val entry = checkNotNull(graph.list.observeEntry(AnimeId(unknown)).first())
            assertEquals(EntryStatus.Unrecognized("rewatching"), entry.status)
            assertEquals(30, graph.ids().size)
            assertEquals(emptyList<String>(), mal.writes)
            assertTrue(unexpected.toString(), unexpected.single() is RepairedAnswer)
        }

    @Test
    fun `a sign-out leaves no token, entry, title or choice, and the next sign-in shows nothing of the one before`() =
        runTest(dispatcher) {
            val parts = parts(process())
            val graph = LiveGraph(parts)
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            graph.preferences.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending))
            val first = graph.list.observeEntries().first()
            assertTrue(parts.tokenFile.length() > 0)

            graph.session.signOut()

            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0L, parts.tokenFile.length())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), store(parts).readAll())
            assertEquals(StoredList(null, null), store(parts).state())
            val titles = TitleStore(parts.database, Dispatchers.IO) {}
            first.forEach { assertNull(titles.observeAnime(it.anime.id).first()) }
            assertEquals(defaults, graph.preferences.observeListSorts().first())
            val asked = mal.asked.size
            assertEquals(Outcome.Failure(DataError.SessionExpired), graph.list.sync())
            assertEquals(asked, mal.asked.size)

            // Another user, whose list is one page.
            mal.pages = listOf(ServedMal.recorded("list_public_3.200.json"))
            graph.signInOnce()
            assertEquals(emptySet<Long>(), graph.ids())
            assertNull(graph.list.observeSyncedAt().first())
            assertEquals(defaults, graph.preferences.observeListSorts().first())
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            assertEquals(ServedMal.ids(mal.pages).toSet(), graph.ids())
            assertEquals(emptyList<String>(), mal.writes)
        }

    @Test
    fun `a sign-out clears what an earlier process stored, also before anything asked for the list`() =
        runTest(dispatcher) {
            val earlier = process()
            val before = LiveGraph(parts(earlier))
            before.signInOnce()
            assertEquals(Outcome.Success(Unit), before.list.sync())
            before.preferences.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending))
            earlier.coroutineContext.job.cancelAndJoin()

            val parts = parts(process())
            val graph = LiveGraph(parts)
            assertEquals(30, store(parts).readAll().size)
            graph.session.signOut()

            assertEquals(emptyMap<AnimeId, StoredEntry>(), store(parts).readAll())
            assertEquals(StoredList(null, null), store(parts).state())
            assertEquals(defaults, graph.preferences.observeListSorts().first())
            assertEquals(0L, parts.tokenFile.length())
        }

    // Until M3 the live airing source has nothing: rows and title pages show no aired count, "to watch" or next episode.
    @Test
    fun `with no airing data no row and no title page shows an airing line, and Watching is ordered by the last update`() =
        runTest(dispatcher) {
            val watching = "status" to "watching"
            val pages = mal.pages
            mal.pages =
                listOf(
                    ServedMal.altered(pages[0], mapOf(63537L to watching, 41457L to watching, 32998L to watching)),
                    ServedMal.altered(pages[1], mapOf(62100L to watching)),
                    pages[2],
                )
            val graph = LiveGraph(parts(process()))
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            val entries = graph.list.observeEntries().first()
            assertEquals(emptyMap<AnimeId, Any>(), graph.airing.observeAiring().first())

            val list =
                ListViewModel(
                    graph.list,
                    graph.anime,
                    graph.airing,
                    graph.session,
                    graph.connection,
                    graph.preferences,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    ShellMessages(backgroundScope, graph.list),
                    graph.background,
                )
            showing(list.state, list.shown)
            val tabs =
                list.state
                    .first { state -> (state.content as? ListContent.Tabs)?.pages?.values?.sumOf { it.rows.size } == entries.size }
                    .content as ListContent.Tabs

            val lastUpdated = entries.filter { it.status == ListStatus.Watching }.sortedByDescending { it.updatedAt }.map { it.anime.id }
            assertEquals(5, lastUpdated.size)
            assertEquals(ListSort.UpNext, tabs.pages.getValue(ListStatus.Watching).sort)
            assertEquals(
                lastUpdated,
                tabs.pages
                    .getValue(ListStatus.Watching)
                    .rows
                    .map { it.id },
            )
            tabs.pages.values
                .flatMap { it.rows }
                .forEach { assertNull(it.title, it.line3) }
            for (entry in entries) {
                val anime = checkNotNull(graph.anime.observeAnime(entry.anime.id).first())
                val page = TitlePage.of(anime, entry, null, null, emptyMap(), graph.titleLanguage, graph.clock)
                assertFalse(anime.titles.romaji, page.airing is AiringPhase.Aired)
            }
        }

    @Test
    fun `a +1 is one write that carries the entry's five values, the others as MAL had them`() =
        runTest(dispatcher) {
            val id = 63293L
            val graph = LiveGraph(parts(process()))
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            val answer = ServedMal.listStatus(mal.pages[1], id).put("num_episodes_watched", 5)
            mal.write = { ServedMal.answer(200, answer.toString()) }
            val asked = mal.arrived.value
            val entry = checkNotNull(graph.list.observeEntry(AnimeId(id)).first())

            graph.list.write(ListWrite.MarkNextWatched(entry.anime))
            mal.arrived.first { it > asked }
            assertEquals(
                5,
                graph.list
                    .observeEntry(AnimeId(id))
                    .first()
                    ?.values
                    ?.watchedEpisodes,
            )
            val form = "status=watching&score=0&num_watched_episodes=5&start_date=2026-10-14&finish_date="
            assertEquals(listOf("PATCH /v2/anime/$id/my_list_status $form"), mal.writes)
        }

    // MAL made the change, and its answer cannot be read: the row shows MAL's last values only until a download.
    @Test
    fun `a write whose answer cannot be read is settled by a download that follows it`() =
        runTest(dispatcher, timeout = 30.seconds) {
            val id = 63293L
            val graph = LiveGraph(parts(process()))
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            val applied = mal.pages.map { ServedMal.altered(it, mapOf(id to ("num_episodes_watched" to 5))) }
            mal.write = {
                mal.pages = applied
                ServedMal.answer(200, "[]")
            }
            val failure = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { graph.list.observeWriteFailures().first() }
            val entry = checkNotNull(graph.list.observeEntry(AnimeId(id)).first())

            graph.list.write(ListWrite.MarkNextWatched(entry.anime))
            assertEquals(DataError.ServerError, failure.await().error)
            val wrote = mal.asked.indexOfFirst { it.startsWith("PATCH ") }
            mal.arrived.first { mal.asked.drop(wrote + 1).any { it.startsWith("GET /v2/users/@me/animelist") } }

            assertEquals(
                5,
                graph.list
                    .observeEntry(AnimeId(id))
                    .first { it?.values?.watchedEpisodes == 5 }
                    ?.values
                    ?.watchedEpisodes,
            )
            assertEquals(1, mal.writes.size)
        }
}
