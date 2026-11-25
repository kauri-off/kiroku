package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.data.anime.MalTitles
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class MalMangaRepositoryTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val context = RuntimeEnvironment.getApplication()

    @get:Rule
    val files = TemporaryFolder()

    private val sessions by lazy { SessionFixture(files.root) }
    private val session by lazy { sessions.open() }

    private val dbName = "manga-test.db"
    private val clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC)

    @After
    fun removeTestDatabase() {
        runBlocking { sessions.close() }
        context.deleteDatabase(dbName)
    }

    @Test
    fun `the real manga response and the anime with the same id live in separate tables across restart`() {
        val id = MangaId(1)
        serve("title_finished")
        serve("manga_completed")

        val saved =
            KirokuDatabase.open(context, dbName).withDatabase { db ->
                runBlocking {
                    val anime = (MalTitles(network.http).title(AnimeId(1), adult = false) as Outcome.Success).value
                    assertEquals("Cowboy Bebop", anime.anime.titles.romaji)
                    TitleStore(db, Dispatchers.IO, network.unexpected::add).refresh(anime.anime, anime.details, clock.instant())
                    val repository = repository(db)
                    assertEquals(Outcome.Success(null), repository.stored(id))
                    (repository.refresh(id) as Outcome.Success).value
                }
            }

        assertEquals(
            "/v2/anime/1",
            network.api
                .received()
                .url.encodedPath,
        )
        val request = network.api.received()
        assertEquals("/v2/manga/1", request.url.encodedPath)
        assertEquals(MalFields.MANGA_DETAILS, request.url.queryParameter("fields"))
        assertEquals(clock.instant(), saved.fetchedAt)
        with(saved.details) {
            assertEquals(id, this.id)
            assertEquals("Monster", titles.romaji)
            assertEquals(MangaFormat.Known.Manga, format)
            assertEquals(MangaPublication.Known.Finished, publication)
            assertEquals(18, volumes)
            assertEquals(162, chapters)
            assertEquals(9.16, meanScore)
            assertTrue(synopsis.orEmpty().contains("Tenma"))
            assertNotNull(posterUrl)
            assertNotNull(largePosterUrl)
        }

        KirokuDatabase.open(context, dbName).withDatabase { db ->
            runBlocking {
                val repository = repository(db)
                assertEquals(Outcome.Success(saved), repository.stored(id))
                val store = TitleStore(db, Dispatchers.IO, network.unexpected::add)
                assertEquals(
                    "Cowboy Bebop",
                    store
                        .observeAnime(AnimeId(1))
                        .first()
                        ?.titles
                        ?.romaji,
                )
            }
        }
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `publishing manga omits unknown totals and a failed read keeps the cached page`() {
        serve("manga_publishing")
        serve("title_unknown")
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val id = MangaId(13)
                val repository = repository(db)
                val saved = (repository.refresh(id) as Outcome.Success).value
                assertEquals("One Piece", saved.details.titles.romaji)
                assertEquals(MangaPublication.Known.Publishing, saved.details.publication)
                assertNull(saved.details.volumes)
                assertNull(saved.details.chapters)

                assertEquals(Outcome.Failure(DataError.NotFound), repository.refresh(id))
                assertEquals(Outcome.Success(saved), repository.stored(id))
            }
        }
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `missing fields are omitted, bad fields are reported and a response for the wrong id is rejected`() {
        val changed =
            recordedJson("manga_completed")
                .edited(listOf("main_picture"))
                .edited(listOf("synopsis"))
                .edited(listOf("num_chapters"), JsonPrimitive(-42))
                .edited(listOf("media_type"), JsonPrimitive("some_new_type"))
        network.api.enqueue(answer(200, changed.toString()))
        serve("manga_publishing")
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val id = MangaId(1)
                val repository = repository(db)
                val saved = (repository.refresh(id) as Outcome.Success).value
                assertNull(saved.details.posterUrl)
                assertNull(saved.details.synopsis)
                assertNull(saved.details.chapters)
                assertEquals(MangaFormat.Unrecognized("some_new_type"), saved.details.format)
                assertEquals(setOf("num_chapters"), (network.unexpected.single() as RepairedAnswer).fields)
                assertEquals(Outcome.Failure(DataError.ServerError), repository.refresh(id))
                assertEquals(Outcome.Success(saved), repository.stored(id))
            }
        }
    }

    @Test
    fun `signed-out cache survives a signed-out start and is erased when the list changes hands`() {
        serve("manga_completed")
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            val scope = CoroutineScope(Dispatchers.IO)
            try {
                runBlocking {
                    val id = MangaId(1)
                    val list = RoomLocalList(db, scope, Dispatchers.IO, network.unexpected::add)
                    val repository = repository(db)
                    val saved = repository.refresh(id)
                    assertTrue(saved is Outcome.Success)
                    list.clear(null)
                    assertEquals(saved, repository.stored(id))
                    assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access", "refresh")))
                    assertEquals(Outcome.Failure(DataError.ListEnded), repository.stored(id))
                    list.clear(session.currentSession()?.owner)
                    assertEquals(Outcome.Success(null), repository.stored(id))
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `signed-out cold cache read waits for cleanup of the persisted owner`() {
        val id = MangaId(1)
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val oldOwner = SignInId("retired")
                db.list().setState { it.copy(owner = oldOwner.value) }
                db.manga().save(recordedJson("manga_completed").toManga(id, mutableSetOf()).toRow(1))
                val cleanupStarted = CompletableDeferred<Unit>()
                val cleanupFinished = CompletableDeferred<Unit>()
                val guarded =
                    repository(db) {
                        cleanupStarted.complete(Unit)
                        cleanupFinished.await()
                    }
                val lookup = async { guarded.stored(id) }
                withTimeout(5_000) { cleanupStarted.await() }
                assertTrue(!lookup.isCompleted)
                assertEquals(Outcome.Failure(DataError.ListEnded), repository(db).stored(id))
                db.manga().deleteAll()
                db.list().setState { it.copy(owner = null) }
                cleanupFinished.complete(Unit)
                assertEquals(Outcome.Success(null), lookup.await())
            }
        }
    }

    @Test
    fun `an in-flight signed-out read cannot save after a sign-in and back to signed out`() {
        val recorded = RecordedAnswers.named("manga_completed")
        val release = CountDownLatch(1)
        network.answers {
            check(release.await(5, TimeUnit.SECONDS))
            answer(recorded.status, recorded.body)
        }
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val id = MangaId(1)
                val repository = repository(db)
                val before = repository.observeOwner().first()
                val request = async(Dispatchers.Default) { repository.refresh(id, before) { true } }
                try {
                    assertEquals(
                        "/v2/manga/1",
                        network.api
                            .received()
                            .url.encodedPath,
                    )
                    assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access", "refresh")))
                    assertEquals(Outcome.Failure(DataError.ListEnded), repository.stored(id, before))
                    session.signOut()
                    assertTrue(before !== repository.observeOwner().first())
                } finally {
                    release.countDown()
                }
                assertEquals(Outcome.Failure(DataError.ListEnded), request.await())
                assertEquals(Outcome.Success(null), repository.stored(id))
            }
        }
    }

    @Test
    fun `refresh leaving a held preflight defers while hidden without sending to MAL`() {
        serve("manga_completed")
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val repository =
                    MalMangaRepository(network.http, {
                        entered.complete(Unit)
                        release.await()
                        db
                    }, clock, session) {}
                val id = MangaId(1)
                val owner = repository.observeOwner().first()
                val visible = AtomicBoolean(true)
                val pending = async { repository.refresh(id, owner, visible::get) }
                withTimeout(5_000) { entered.await() }
                visible.set(false)
                release.complete(Unit)
                assertNull(pending.await())
                assertEquals(0, network.api.requestCount)
                assertTrue(network.unexpected.isEmpty())

                visible.set(true)
                assertTrue(repository.refresh(id, owner, visible::get) is Outcome.Success)
                assertEquals(1, network.api.requestCount)
            }
        }
    }

    @Test
    fun `hidden preflight storage failure defers refresh but reports the storage error`() {
        serve("manga_completed")
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var failStorage = true
                val repository =
                    MalMangaRepository(network.http, {
                        entered.complete(Unit)
                        release.await()
                        check(!failStorage)
                        db
                    }, clock, session) {}
                val id = MangaId(1)
                val owner = repository.observeOwner().first()
                val visible = AtomicBoolean(true)
                val pending = async { repository.refresh(id, owner, visible::get) }
                withTimeout(5_000) { entered.await() }
                visible.set(false)
                release.complete(Unit)
                assertNull(pending.await())
                assertEquals(0, network.api.requestCount)
                assertEquals(1, network.unexpected.size)

                failStorage = false
                visible.set(true)
                assertTrue(repository.refresh(id, owner, visible::get) is Outcome.Success)
                assertEquals(1, network.api.requestCount)
            }
        }
    }

    @Test
    fun `first send queued by OkHttp defers while hidden and sends once on return`() {
        val visible = AtomicBoolean(true)
        queuedRead(change = { visible.set(false) }, canDispatch = visible::get, expected = null, reopen = true)
    }

    @Test
    fun `a queued read from a retired owner ends even if it is hidden too`() {
        val visible = AtomicBoolean(true)
        queuedRead(
            change = {
                visible.set(false)
                assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("replacement-access", "replacement-refresh")))
            },
            canDispatch = visible::get,
            expected = Outcome.Failure(DataError.ListEnded),
        )
    }

    private fun queuedRead(
        change: suspend () -> Unit,
        canDispatch: () -> Boolean,
        expected: Outcome<StoredManga>?,
        reopen: Boolean = false,
    ) {
        val dispatcher = Dispatcher().apply { maxRequests = 1 }
        network.calls = dispatcher
        val release = CountDownLatch(1)
        val recorded = RecordedAnswers.named("manga_completed")
        network.answers { request ->
            if (request.url.encodedPath == "/v2/thing") {
                check(release.await(5, TimeUnit.SECONDS))
                answer()
            } else {
                answer(recorded.status, recorded.body)
            }
        }
        KirokuDatabase.builder(context, null).build().withDatabase { db ->
            runBlocking {
                val repository = repository(db)
                val owner = repository.observeOwner().first()
                val id = MangaId(1)
                val occupying = async(Dispatchers.Default) { network.http.call { network.things.value.read() } }
                val pending =
                    try {
                        assertEquals(
                            "/v2/thing",
                            network.api
                                .received()
                                .url.encodedPath,
                        )
                        val pending = async { repository.refresh(id, owner, canDispatch) }
                        withTimeout(5_000) {
                            while (dispatcher.queuedCallsCount() == 0) delay(1)
                        }
                        change()
                        pending
                    } finally {
                        release.countDown()
                    }

                occupying.await()
                assertEquals(expected, pending.await())
                assertEquals(1, network.api.requestCount)
                assertTrue(network.unexpected.isEmpty())
                if (reopen) {
                    assertTrue(repository.refresh(id, owner) { true } is Outcome.Success)
                    assertEquals(
                        "/v2/manga/1",
                        network.api
                            .received()
                            .url.encodedPath,
                    )
                    assertEquals(2, network.api.requestCount)
                }
            }
        }
    }

    private fun repository(
        db: KirokuDatabase,
        awaitList: suspend () -> Unit = {},
    ): MalMangaRepository = MalMangaRepository(network.http, { db }, clock, session, awaitList)

    private suspend fun MangaRepository.stored(id: MangaId) = stored(id, observeOwner().first())

    private suspend fun MangaRepository.refresh(id: MangaId) = refresh(id, observeOwner().first()) { true }

    private fun <T> KirokuDatabase.withDatabase(block: (KirokuDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun serve(name: String) {
        val recorded = RecordedAnswers.named(name)
        network.api.enqueue(answer(recorded.status, recorded.body))
    }
}
