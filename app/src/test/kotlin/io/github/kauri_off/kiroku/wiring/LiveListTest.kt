package io.github.kauri_off.kiroku.wiring

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.airing.effective
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredList
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.list.ListContent
import io.github.kauri_off.kiroku.list.ListEvent
import io.github.kauri_off.kiroku.list.ListViewModel
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
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
        LiveParts(
            context,
            LiveSource("served", mal.hosts, aniList = ServedMal.NO_ANILIST, tenrai = ServedMal.NO_TENRAI),
            "0123abcd",
            Clock.systemUTC(),
            scope,
            unexpected::add,
            device = online,
        )

    // A whole sign-in: MAL's page is opened, and the browser comes back with a code for its state.
    private suspend fun SignIn.once() {
        var address: String? = null
        start {
            address = it
            true
        }
        val state = checkNotNull(address).toHttpUrl().queryParameter("state")
        redirected("${BuildConfig.OAUTH_REDIRECT}?code=the-code&state=$state")
        assertEquals(SignInStep.SignedIn, step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
    }

    private suspend fun LiveGraph.signInOnce() = signIn.once()

    // The List screen over the graph, shown until the test ends.
    private fun TestScope.listScreen(graph: LiveGraph): ListViewModel =
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
        ).also { showing(it.state, it.shown) }

    private suspend fun ListViewModel.showingRows(count: Int): ListContent.Tabs =
        state.first { state -> (state.content as? ListContent.Tabs)?.pages?.values?.sumOf { it.rows.size } == count }.content
            as ListContent.Tabs

    private fun lastUpdatedFirst(
        entries: List<ListEntry>,
        status: ListStatus,
    ) = entries.filter { it.status == status }.sortedByDescending { it.updatedAt }.map { it.anime.id }

    private suspend fun LiveGraph.ids(): Set<Long> =
        list
            .observeEntries()
            .first()
            .map { it.anime.id.value }
            .toSet()

    private fun TestScope.store(parts: LiveParts) = RoomLocalList(parts.database, backgroundScope, Dispatchers.IO) {}

    @Test
    fun `the live manga reader retains a failed snapshot and clears it on replacement and sign-out`() =
        runBlocking {
            mal.manga = ServedMal.answer(200, mangaPage)
            val parts = parts(process())
            val graph = LiveGraph(parts, listStore = { InMemoryLocalList() })
            val reader = graph.mangaList
            assertTrue(mangaRequests().isEmpty())
            graph.signInOnce()
            val first = (graph.account.currentSession() as Session.SignedIn).signIn
            assertEquals(Outcome.Success(null), reader.stored(first))
            assertTrue(mangaRequests().isEmpty())
            val saved = reader.refresh(first) as Outcome.Success
            assertEquals(
                "Test Manga",
                saved.value.entries
                    .single()
                    .titles.romaji,
            )

            mal.manga = ServedMal.answer(400, "")
            assertEquals(Outcome.Failure(DataError.InvalidRequest), reader.refresh(first))
            assertEquals(saved, reader.stored(first))

            graph.signInOnce()
            val second = (graph.account.currentSession() as Session.SignedIn).signIn
            assertEquals(Outcome.Failure(DataError.ListEnded), reader.stored(first))
            assertEquals(Outcome.Success(null), reader.stored(second))
            withTimeout(5_000) {
                while (mangaRows(parts) != 0L) delay(10)
            }

            mal.manga = ServedMal.answer(200, mangaPage)
            assertTrue(reader.refresh(second) is Outcome.Success)
            assertEquals(1L, mangaRows(parts))
            graph.session.signOut()
            assertEquals(Session.SignedOut, graph.account.currentSession())
            assertEquals(0L, mangaRows(parts))
            assertEquals(Outcome.Failure(DataError.ListEnded), reader.stored(second))
            val requests = mangaRequests()
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.startsWith("GET ") })
            assertTrue(unexpected.isEmpty())
        }

    @Test
    fun `startup removes retired manga rows and keeps the expired owner's cache`() =
        runBlocking {
            val parts = parts(process())
            val owner = SignInId("0f8fad5b-d9cb-469f-a165-70867728950e")
            parts.tokenFile.writeBytes(byteArrayOf(0) + owner.value.encodeToByteArray())
            liveTitles(parts).observeAnime(AnimeId(1)).first()
            val file = context.getDatabasePath(parts.databaseName).path
            SQLiteDatabase.openDatabase(file, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("INSERT INTO manga_list_state (owner, synced_at) VALUES (?, 0), ('retired', 0)", arrayOf(owner.value))
                db.execSQL(
                    "INSERT INTO manga_list_entry (owner, manga_id, romaji, synonyms, status) " +
                        "VALUES (?, 1, 'Retained', '[]', 'reading'), ('retired', 2, 'Retired', '[]', 'reading')",
                    arrayOf(owner.value),
                )
            }
            val graph = LiveGraph(parts, listStore = { InMemoryLocalList() })
            assertEquals(Session.Expired(owner), graph.session.observeSession().first())
            withTimeout(5_000) {
                while (mangaRows(parts) != 1L) delay(10)
            }
            val cached = graph.mangaList.stored(owner) as Outcome.Success
            assertEquals(
                "Retained",
                cached.value
                    ?.entries
                    ?.single()
                    ?.titles
                    ?.romaji,
            )
            assertEquals(Outcome.Failure(DataError.SessionExpired), graph.mangaList.refresh(owner))
            assertEquals(emptyList<String>(), mal.asked)

            graph.signOut(Session.Expired(owner))
            assertEquals(0L, mangaRows(parts))
            assertTrue(unexpected.isEmpty())
        }

    private fun mangaRequests() = mal.asked.filter { "/v2/users/@me/mangalist?" in it }

    private fun mangaRows(parts: LiveParts): Long =
        SQLiteDatabase
            .openDatabase(context.getDatabasePath(parts.databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { DatabaseUtils.queryNumEntries(it, "manga_list_entry") }

    private val mangaPage =
        """{"data":[{"node":{"id":1,"title":"Test Manga"},"list_status":{"status":"reading"}}],"paging":{}}"""

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
            graph.preferences.updateOpenedProfiles { listOf("sample_viewer") }
            val first = graph.list.observeEntries().first()
            assertTrue(parts.tokenFile.length() > 0)
            // A List screen stays open through the sign-out and the next sign-in, as the debug menu leaves it.
            val screen = listScreen(graph)
            assertEquals(
                ListSort.Score,
                screen
                    .showingRows(30)
                    .pages
                    .getValue(ListStatus.Completed)
                    .sort,
            )
            screen.onEvent(ListEvent.SelectTab(ListStatus.Completed))

            graph.session.signOut()
            assertEquals(ListContent.SignedOut, screen.state.first { it.content == ListContent.SignedOut }.content)

            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0L, parts.tokenFile.length())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), store(parts).readAll())
            assertEquals(StoredList(null, null), store(parts).state())
            val titles = TitleStore(parts.database, Dispatchers.IO) {}
            first.forEach { assertNull(titles.observeAnime(it.anime.id).first()) }
            assertEquals(defaults, graph.preferences.observeListSorts().first())
            assertEquals(emptyList<String>(), graph.preferences.observeOpenedProfiles().first())
            val asked = mal.asked.size
            assertEquals(Outcome.Failure(DataError.SessionExpired), graph.list.sync())
            assertEquals(asked, mal.asked.size)

            // Another user, whose list is one page; the List still open downloads it by itself.
            mal.pages = listOf(ServedMal.recorded("list_public_3.200.json"))
            val ids = ServedMal.ids(mal.pages).toSet()
            graph.signInOnce()
            assertTrue(ids.containsAll(graph.ids()))
            val shown = screen.showingRows(ids.size)
            val second = graph.list.observeEntries().first()
            assertEquals(ids, second.map { it.anime.id.value }.toSet())
            assertNotNull(graph.list.observeSyncedAt().first())
            assertEquals(emptyList<String>(), mal.writes)
            assertEquals(ListStatus.Watching, screen.state.value.tab)
            val completed = shown.pages.getValue(ListStatus.Completed)
            assertEquals(ListSort.LastUpdated, completed.sort)
            assertEquals(lastUpdatedFirst(second, ListStatus.Completed), completed.rows.map { it.id })
            assertEquals(defaults, graph.preferences.observeListSorts().first())
        }

    // The sorts are forgotten first, so a clear cut short leaves the list as the last owner's, and its next use clears both.
    @Test
    fun `a sign-out whose sorts could not be forgotten keeps the list's owner and entries until the next read clears both`() =
        runTest(dispatcher) {
            val parts = parts(process())
            val session = liveSession(parts)
            var failing = false
            val list = liveList(parts, session, ::liveListStore) { if (failing) throw IOException("The disk is full") }
            liveSignIn(parts, session).once()
            assertEquals(Outcome.Success(Unit), list.sync())

            failing = true
            session.signOut()
            assertEquals(emptyList<ListEntry>(), list.observeEntries().first())
            assertEquals(30, store(parts).readAll().size)
            assertNotNull(store(parts).state().owner)

            failing = false
            assertEquals(emptyList<ListEntry>(), list.observeEntries().first())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), store(parts).readAll())
            assertEquals(StoredList(null, null), store(parts).state())
        }

    @Test
    fun `a start signed out leaves the choices, which only a new owner of the list forgets`() =
        runTest(dispatcher) {
            val graph = LiveGraph(parts(process()))
            val score = TabSort(ListSort.Score, SortDirection.Ascending)
            graph.preferences.setListSort(ListStatus.Completed, score)
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            assertEquals(
                score,
                graph.preferences
                    .observeListSorts()
                    .first()
                    .getValue(ListStatus.Completed),
            )

            graph.signInOnce()
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            assertEquals(defaults, graph.preferences.observeListSorts().first())
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

    // Section 2.3: with AniList out of reach, every unfinished title shows MAL's estimate, nothing is stored and the List shows its rows.
    @Test
    fun `with AniList out of reach every unfinished title has MAL's estimate and nothing is stored`() =
        runTest(dispatcher) {
            val graph = LiveGraph(parts(process()))
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            val entries = graph.list.observeEntries().first()

            assertTrue(graph.airing.refresh() is Outcome.Failure)
            val airing = graph.airing.observeAiring().first()
            val unfinished = entries.filter { it.anime.airingStatus.effective != AiringStatus.Finished }.map { it.anime.id }
            assertTrue(unfinished.isNotEmpty())
            assertEquals(unfinished.toSet(), airing.keys)
            assertTrue(airing.toString(), airing.values.all { it.inexact != null })
            assertEquals(emptyMap<AnimeId, Any>(), graph.airing.observeSchedules().first())
            listScreen(graph).showingRows(entries.size)
        }

    @Test
    fun `a +1 is one write that carries the count alone, and the row ends with the count MAL answered`() =
        runTest(dispatcher, timeout = 30.seconds) {
            val id = 63293L
            val graph = LiveGraph(parts(process()))
            graph.signInOnce()
            assertEquals(Outcome.Success(Unit), graph.list.sync())
            // MAL keeps another count than the one sent, so the row can only show it from MAL's answer.
            val answer = ServedMal.listStatus(mal.pages[1], id).put("num_episodes_watched", 6)
            mal.write = { ServedMal.answer(200, answer.toString()) }
            val entry = checkNotNull(graph.list.observeEntry(AnimeId(id)).first())

            graph.list.write(ListWrite.MarkNextWatched(entry.anime))
            graph.list.observeEntry(AnimeId(id)).first { it?.values?.watchedEpisodes == 6 }
            val form = "num_watched_episodes=5"
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
