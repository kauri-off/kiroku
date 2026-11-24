package io.github.kauri_off.kiroku.wiring

import android.content.Intent
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import io.github.kauri_off.kiroku.core.airing.AiringAnswers
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.AiringSource
import io.github.kauri_off.kiroku.core.airing.AiringStore
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.airing.InMemoryAiringStore
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.data.db.RoomAiringStore
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.rules.appSources
import io.github.kauri_off.kiroku.rules.projectDir
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

// The live graph's airing: built at its first use, off the main thread, on the device's signals, and gone with a sign-out (S9).
@RunWith(RobolectricTestRunner::class)
class LiveAiringTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val server = MockWebServer()

    private val device =
        object : Device {
            override fun isOnline() = true

            override fun observeOnline() = flowOf(true)

            override fun isMainThread() = false
        }

    @After
    fun close() = server.close()

    private val account = LiveSource(LiveSource.Account.name, MalHosts.Real, aniList = ServedMal.NO_ANILIST)

    private fun parts(
        scope: CoroutineScope,
        source: LiveSource = account,
        clockSet: Flow<Unit> = flowOf(),
    ) = LiveParts(context, source, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device, clockSet = clockSet)

    private val anime = FixtureAnime.exactToWatch

    private fun watching(vararg titles: Anime) =
        titles.map {
            ListEntry(it, ListValues(ListStatus.Watching, 0, null, null, null), Instant.EPOCH)
        }

    private val schedule = AiringSchedule(listOf(ScheduledEpisode(1, Instant.parse("2026-11-01T15:00:00Z"))), 12)

    private val signIn = "0f8fad5b-d9cb-469f-a165-70867728950e"

    // An expired sign-in, as the session writes it: a zero byte and the sign-in's id. Its list stays readable.
    private fun expired(parts: LiveParts) = parts.file("tokens").writeBytes(byteArrayOf(0) + signIn.encodeToByteArray())

    private suspend fun InMemoryAiringStore.clear() = write(read().keys.associateWith { null })

    private fun stored(parts: LiveParts) = RoomAiringStore(parts.database, Dispatchers.IO, unexpected::add)

    @Test
    fun `reading the session or the list builds nothing of airing, and its first call builds it once, off the main thread`() =
        runTest {
            val built = CopyOnWriteArrayList<Thread>()
            val graph =
                LiveGraph(parts(backgroundScope), listStore = { InMemoryLocalList() }) { parts, source, store, list ->
                    built += Thread.currentThread()
                    listAiring(parts, source, store, list)
                }

            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            val airing = graph.airing
            assertEquals(emptyList<Thread>(), built)

            assertEquals(emptyMap<AnimeId, Any>(), airing.observeAiring().first())
            assertEquals(Outcome.Success(Unit), airing.refreshStale())
            assertSame(airing, graph.airing)
            assertEquals(1, built.size)
            assertNotSame(Looper.getMainLooper().thread, built.single())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `the live airing hears the device's clock being set, and only while it is read`() =
        runTest {
            val listening = AtomicInteger()
            val set =
                MutableSharedFlow<Unit>().let { flow ->
                    flow.onStart { listening.incrementAndGet() }.onCompletion { listening.decrementAndGet() }
                }
            val graph = LiveGraph(parts(backgroundScope, clockSet = set), listStore = { InMemoryLocalList() })
            graph.airing.observeSchedules().first()
            runCurrent()
            assertEquals(0, listening.get())

            val reading = backgroundScope.launch { graph.airing.observeAiring().collect {} }
            graph.airing.observeAiring().first()
            runCurrent()
            assertEquals(1, listening.get())
            reading.cancel()
            runCurrent()
            assertEquals(0, listening.get())
        }

    @Test
    fun `the device's clock and time zone being set are heard, through a receiver held only while they are listened to`() =
        runTest {
            val heard = mutableListOf<Unit>()
            val listening = backgroundScope.launch(Dispatchers.Main.immediate) { deviceClockSet(context).collect { heard += it } }
            shadowOf(Looper.getMainLooper()).idle()
            val receivers = { shadowOf(context).registeredReceivers.count { CLOCK_SET.all(it.intentFilter::hasAction) } }
            assertEquals(1, receivers())

            for (action in CLOCK_SET) {
                context.sendBroadcast(Intent(action))
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(CLOCK_SET.size, heard.size)

            listening.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, receivers())
        }

    @Test
    fun `the time AniList's requests are spaced by goes on while the device sleeps`() {
        val mark = DeviceTime.markNow()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(3))
        assertTrue(mark.elapsedNow() >= 3.hours)
    }

    @Test
    fun `AniList's refusal is kept in the source's own file, which is not backed up, and its client is the source's one`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .setHeader("Retry-After", "60")
                    .build(),
            )
            server.start()
            val parts = parts(backgroundScope, LiveSource("refused", MalHosts.Real, aniList = server.url("/").toString()))
            assertSame(parts.aniList, parts.aniList)

            val answer = parts.aniList.fetch(listOf(anime), Instant.now())
            assertEquals(Outcome.Failure(DataError.Refused), answer.getValue(anime.id))
            assertTrue(File(context.noBackupFilesDir, "refused.anilist.block").isFile)
            assertFalse(File(context.noBackupFilesDir, "account.anilist.block").exists())
        }

    // So that AniList's spacing and its refusal hold for every request of the process.
    @Test
    fun `the AniList client is made in the live graph's file and nowhere else`() {
        val making = Regex("""\bAniListAiring\s*\(""")
        assertEquals(listOf("LiveGraph.kt"), appSources().filter { making.containsMatchIn(it.readText()) }.map { it.name })
    }

    @Test
    fun `a sign-out leaves no airing data in the source's database, whether or not airing was read before it`() =
        runTest {
            for (read in listOf(false, true)) {
                val parts =
                    parts(backgroundScope, LiveSource(if (read) "read" else "unread", MalHosts.Real, aniList = ServedMal.NO_ANILIST))
                expired(parts)
                val held = mapOf(anime.id to StoredAiring(schedule, Instant.now()), AnimeId(1) to StoredAiring(null, Instant.now()))
                stored(parts).write(held)
                val graph = LiveGraph(parts, listStore = { InMemoryLocalList(watching(anime), SignInId(signIn)) })
                if (read) {
                    backgroundScope.launch { graph.airing.observeAiring().collect {} }
                    assertTrue(
                        graph.airing
                            .observeSchedules()
                            .first()
                            .containsKey(anime.id),
                    )
                }

                graph.session.signOut()
                assertEquals("$read", emptyMap<AnimeId, StoredAiring>(), stored(parts).read())
            }
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    // A source that answers once it is let go, and says whether its call was cancelled.
    private class Held : AiringSource {
        val calls = AtomicInteger()
        val answer = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun fetch(
            titles: Collection<Anime>,
            since: Instant,
        ): Map<AnimeId, Outcome<FetchedAiring?>> {
            calls.incrementAndGet()
            try {
                answer.await()
            } catch (e: Exception) {
                cancelled.complete(Unit)
                throw e
            }
            return titles.associate { it.id to Outcome.Success(FetchedAiring(AiringSchedule(emptyList(), null))) }
        }
    }

    @Test
    fun `nothing is asked while signed out, a call under way at a sign-out ends there, and an expired session still asks`() =
        runTest {
            val session = MutableStateFlow<Session>(Session.SignedOut)
            val held = Held()
            val source = SignedInSource(held, session)
            val ended = mapOf(anime.id to Outcome.Failure(DataError.ListEnded))

            assertEquals(ended, source.fetch(listOf(anime), Instant.now()))
            assertEquals(0, held.calls.get())

            session.value = Session.SignedIn(SignInId(signIn))
            val call = async { source.fetch(listOf(anime), Instant.now()) }
            runCurrent()
            assertEquals(1, held.calls.get())
            session.value = Session.SignedOut
            assertEquals(ended, call.await())
            assertTrue(held.cancelled.isCompleted)

            session.value = Session.Expired(SignInId(signIn))
            held.answer.complete(Unit)
            assertTrue(source.fetch(listOf(anime), Instant.now()).getValue(anime.id) is Outcome.Success)
            assertEquals(2, held.calls.get())
        }

    @Test
    fun `a title the source hands over whole while signed in reaches the engine before the call ends, and a pull reaches the source`() =
        runTest {
            val fetched = mapOf(anime.id to Outcome.Success(FetchedAiring(schedule)))
            val pulls = mutableListOf<Set<AnimeId>>()
            val handing =
                object : AiringSource {
                    override suspend fun fetch(
                        titles: Collection<Anime>,
                        since: Instant,
                    ): Map<AnimeId, Outcome<FetchedAiring?>> = error("The whole call alone")

                    override suspend fun fetch(
                        titles: Collection<Anime>,
                        since: Instant,
                        pulled: Set<AnimeId>,
                        whole: suspend (Map<AnimeId, Outcome<FetchedAiring?>>) -> Unit,
                    ): Map<AnimeId, Outcome<FetchedAiring?>> {
                        pulls += pulled
                        whole(fetched)
                        return fetched
                    }
                }
            val owner = SignInId(signIn)
            val handed = mutableListOf<AiringAnswers>()

            val answer =
                SignedInSource(handing, flowOf(Session.SignedIn(owner)))
                    .fetchAnswers(listOf(anime), Instant.now(), setOf(anime.id), coming = false) { handed += it }

            assertEquals(listOf(setOf(anime.id)), pulls)
            assertEquals(listOf(AiringAnswers(fetched, owner)), handed)
            assertEquals(AiringAnswers(fetched, owner), answer)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a returned notification recheck cannot write after another sign-in`() =
        runTest {
            val title = anime.copy(id = AnimeId(61987))
            val body = File(projectDir, "data/src/test/resources/anilist/recorded/airing_weekly.200.json").readText()
            repeat(2) { server.enqueue(MockResponse.Builder().body(body).build()) }
            server.start()
            val source =
                LiveSource(
                    "airing-owner",
                    MalHosts.Real,
                    aniList = server.url("/").toString(),
                    aniListTime = testScheduler.timeSource,
                )
            val parts = parts(backgroundScope, source)
            val session = MutableStateFlow<Session>(Session.SignedIn(SignInId(signIn)))
            val list =
                object : ListSource {
                    override fun observeEntries() = flowOf(watching(title))

                    override fun observeEntry(id: AnimeId) = observeEntries().map { all -> all.firstOrNull { it.anime.id == id } }

                    override fun observeEntriesOf(ids: Set<AnimeId>) = observeEntries().map { all -> all.associateBy { it.anime.id } }

                    override fun observeSyncedAt() = flowOf<Instant?>(null)

                    override suspend fun sync() = Outcome.Success(Unit)
                }
            val returned = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val airing =
                LiveAiring(parts, session, { list }) { parts, source, store, list ->
                    val held =
                        object : AiringStore by store {
                            override suspend fun read(): Map<AnimeId, StoredAiring> {
                                if (server.requestCount == 1 && returned.complete(Unit)) release.await()
                                return store.read()
                            }
                        }
                    listAiring(parts, source, held, list)
                }
            val room = stored(parts)
            room.write(mapOf(title.id to StoredAiring(schedule, Instant.EPOCH)))

            val recheck = async { airing.refresh(setOf(title.id)) }
            returned.await()
            session.value = Session.SignedOut
            airing.forget()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), room.read())
            session.value = Session.SignedIn(SignInId("another-sign-in"))
            release.complete(Unit)
            val retired = recheck.await()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), room.read())
            assertEquals(Outcome.Failure(DataError.ListEnded), retired)

            testScheduler.advanceTimeBy(4_001)
            assertEquals(Outcome.Success(Unit), airing.refresh(setOf(title.id)))
            val refreshed = room.read().getValue(title.id)
            assertEquals(12, refreshed.schedule?.episodes?.size)
            assertEquals(2, server.requestCount)
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `while signed out the store only forgets`() =
        runTest {
            val held = StoredAiring(schedule, Instant.now())
            val inner = InMemoryAiringStore(mapOf(AnimeId(1) to held))
            val store = SignedInStore(inner, flowOf(Session.SignedOut)) { inner.clear() }
            assertFalse(store.writeAnswers(mapOf(anime.id to held), SignInId(signIn)))
            store.write(mapOf(AnimeId(1) to null))
            assertEquals(emptyMap<AnimeId, StoredAiring>(), inner.read())
        }

    @Test
    fun `an expired session still stores, and the store is emptied only once nobody is signed in`() =
        runTest {
            val session = MutableStateFlow<Session>(Session.Expired(SignInId(signIn)))
            val inner = InMemoryAiringStore()
            val store = SignedInStore(inner, session) { inner.clear() }
            val held = mapOf(anime.id to StoredAiring(schedule, Instant.now()))

            assertTrue(store.writeAnswers(held, SignInId(signIn)))
            store.forget()
            assertEquals(held, inner.read())

            session.value = Session.SignedOut
            store.forget()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), inner.read())
        }

    @Test
    fun `a write under way when the session signs out is not left behind by the store's emptying`() =
        runTest {
            val session = MutableStateFlow<Session>(Session.SignedIn(SignInId(signIn)))
            val inner = InMemoryAiringStore()
            val gate = CompletableDeferred<Unit>()
            val slow =
                object : AiringStore by inner {
                    override suspend fun write(titles: Map<AnimeId, StoredAiring?>) {
                        gate.await()
                        inner.write(titles)
                    }
                }
            val store = SignedInStore(slow, session) { inner.clear() }
            val writing = launch { store.writeAnswers(mapOf(anime.id to StoredAiring(null, Instant.now())), SignInId(signIn)) }
            runCurrent()
            session.value = Session.SignedOut
            val forgetting = launch { store.forget() }
            runCurrent()
            gate.complete(Unit)
            writing.join()
            forgetting.join()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), inner.read())
        }

    @Test
    fun `a sign-out empties the airing tables whole, a row the store cannot read included`() =
        runTest {
            val parts = parts(backgroundScope, LiveSource("unreadable", MalHosts.Real, aniList = ServedMal.NO_ANILIST))
            expired(parts)
            val database = written(parts)
            val graph = LiveGraph(parts, listStore = { InMemoryLocalList() })

            graph.session.signOut()
            assertEquals(0L, count(database, "airing"))
            assertEquals(0L, count(database, "airing_episode"))
        }

    @Test
    fun `a sign-out the process did not finish is completed at the next process's first read of airing, and only signed out`() =
        runTest {
            for (signedOut in listOf(true, false)) {
                val parts = parts(backgroundScope, LiveSource("left$signedOut", MalHosts.Real, aniList = ServedMal.NO_ANILIST))
                if (!signedOut) expired(parts)
                val database = written(parts)
                val graph = LiveGraph(parts, listStore = { InMemoryLocalList(watching(anime), SignInId(signIn)) })
                assertEquals(2L, count(database, "airing"))

                graph.airing.observeAiring().first()
                assertEquals("$signedOut", if (signedOut) 0L else 2L, count(database, "airing"))
            }
        }

    @Test
    fun `the live graph's airing holds the device's receiver for its clock and time zone while it is read, and only then`() =
        runTest {
            val parts = LiveParts(context, account, "0123abcd", Clock.systemUTC(), backgroundScope, unexpected::add, device = device)
            val graph = LiveGraph(parts, listStore = { InMemoryLocalList() })
            val receivers = { shadowOf(context).registeredReceivers.count { CLOCK_SET.all(it.intentFilter::hasAction) } }
            graph.airing.observeSchedules().first()
            assertEquals(0, receivers())

            val reading = backgroundScope.launch { graph.airing.observeAiring().collect {} }
            until { receivers() == 1 }
            reading.cancel()
            until { receivers() == 0 }
        }

    // Waits on the threads the graph's parts run on.
    private suspend fun until(done: () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!done()) delay(10)
            }
        }

    // One readable title with an episode, and one whose row says it is unknown yet has a total, which no read gives back.
    private suspend fun written(parts: LiveParts): SQLiteDatabase {
        stored(parts).write(mapOf(anime.id to StoredAiring(schedule, Instant.now())))
        val database =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath("${parts.source.name}.db").path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        withContext(
            Dispatchers.IO,
        ) { database.execSQL("INSERT INTO airing (title_id, fetched_at, known, total_episodes) VALUES (5, 0, 0, 12)") }
        return database
    }

    private suspend fun count(
        database: SQLiteDatabase,
        table: String,
    ): Long = withContext(Dispatchers.IO) { DatabaseUtils.queryNumEntries(database, table) }
}
