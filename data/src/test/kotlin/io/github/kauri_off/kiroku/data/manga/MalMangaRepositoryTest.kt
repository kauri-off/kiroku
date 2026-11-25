package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.SignInId
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class MalMangaRepositoryTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val context = RuntimeEnvironment.getApplication()
    private val dbName = "manga-test.db"
    private val clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC)

    @After
    fun removeTestDatabase() {
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
                    val repository = MalMangaRepository(network.http, { db }, clock)
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
                val repository = MalMangaRepository(network.http, { db }, clock)
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
                val repository = MalMangaRepository(network.http, { db }, clock)
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
                val repository = MalMangaRepository(network.http, { db }, clock)
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
                    val repository = MalMangaRepository(network.http, { db }, clock)
                    val saved = repository.refresh(id)
                    assertTrue(saved is Outcome.Success)
                    list.clear(null)
                    assertEquals(saved, repository.stored(id))
                    list.clear(SignInId("new-owner"))
                    assertEquals(Outcome.Success(null), repository.stored(id))
                }
            } finally {
                scope.cancel()
            }
        }
    }

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
