package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.chainWalk
import io.github.kauri_off.kiroku.core.model.sequelChain
import io.github.kauri_off.kiroku.core.repository.detailsStale
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.TestDatabases
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.db.breakWrites
import io.github.kauri_off.kiroku.data.db.dispatcher
import io.github.kauri_off.kiroku.data.db.execute
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The titles of section 7.1 over the store and the client, with recorded pages behind a local server.
@RunWith(RobolectricTestRunner::class)
class MalAnimeRepositoryTest {
    @get:Rule
    internal val network = NetworkFixture()

    @get:Rule
    val databases = TestDatabases()

    private val airing = recordedTitle("title_airing")
    private val id = airing.anime.id

    private class Subject(
        val titles: MalAnimeRepository,
        val store: TitleStore,
        val database: KirokuDatabase,
        val adult: MutableList<Boolean>,
    )

    private fun TestScope.subject(showAdult: Boolean = false): Subject {
        val database = databases.open(this)
        val store = TitleStore(database, dispatcher, network.unexpected::add)
        val asked = mutableListOf<Boolean>()
        val adult = suspend { showAdult.also { asked += it } }
        return Subject(
            MalAnimeRepository(MalTitles(network.http), store, network.clock, adult, network.unexpected::add),
            store,
            database,
            asked,
        )
    }

    private fun serve(page: JsonObject) = network.api.enqueue(answer(200, page.toString(), "application/json; charset=UTF-8"))

    private fun serve(name: String) {
        val recorded = RecordedAnswers.named(name)
        network.api.enqueue(answer(recorded.status, recorded.body, "application/json; charset=UTF-8"))
    }

    private suspend fun Subject.stored() =
        Triple(titles.observeAnime(id).first(), titles.observeDetails(id).first(), titles.observeRefreshedAt(id).first())

    @Test
    fun `stored details are shown without a request while fresh, and a refresh is asked once they are stale`() =
        runTest {
            val s = subject()
            serve("title_airing")
            val fetched = network.clock.now

            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            assertEquals(Triple(airing.anime, airing.details, fetched), s.stored())

            // Shown again over six hours: the stored page, read without a request.
            for (later in listOf(Duration.ofMinutes(1), Duration.ofHours(6).minusMillis(1))) {
                network.clock.now = fetched + later
                val (anime, details, at) = s.stored()
                assertEquals(airing.details, details)
                assertFalse(detailsStale(anime, at, network.clock.now))
            }
            network.clock.now = fetched + Duration.ofHours(6)
            val (anime, _, at) = s.stored()
            assertTrue("an airing title's details are stale after six hours", detailsStale(anime, at, network.clock.now))
            assertEquals(1, network.api.requestCount)
            assertTrue(network.unexpected.isEmpty())
        }

    // The recorded relations of two pages: an airing third season between its prequel and its sequel, and a second
    // season with its prequel alone, whose own pages are not stored.
    @Test
    fun `the stored pages of several titles are read together, and tell the chain as MAL recorded it`() =
        runTest {
            val s = subject()
            val notYetAired = recordedTitle("title_not_yet_aired")
            serve("title_airing")
            serve("title_not_yet_aired")
            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            assertEquals(Outcome.Success(Unit), s.titles.refresh(notYetAired.anime.id))

            val prequel = AnimeId(58514)
            val sequel = AnimeId(62841)
            val pages = s.titles.observeDetailsOf(setOf(id, notYetAired.anime.id, prequel)).first()
            assertEquals(mapOf(id to airing.details, notYetAired.anime.id to notYetAired.details), pages)
            val related = pages.mapValues { it.value.related }
            assertEquals(listOf(id, sequel, prequel), chainWalk(id, related))
            assertEquals(listOf(prequel, id, sequel), sequelChain(airing.anime, related).order.map { it.id })
            assertEquals(listOf(notYetAired.anime.id, AnimeId(52701)), chainWalk(notYetAired.anime.id, related))
            assertEquals(emptyMap<AnimeId, Any>(), s.titles.observeDetailsOf(setOf(prequel, sequel)).first())
            assertTrue(network.unexpected.isEmpty())
        }

    @Test
    fun `a failed refresh changes nothing stored and is reported, as an error to the page and as a fault when it is one`() =
        runTest {
            network.repeatReadsAfter = emptyList()
            val s = subject()
            serve("title_airing")
            s.titles.refresh(id)
            val before = s.stored()
            network.clock.now += Duration.ofDays(2)

            serve("title_unknown")
            assertEquals(Outcome.Failure(DataError.NotFound), s.titles.refresh(id))
            network.api.enqueue(answer(503, ""))
            assertEquals(Outcome.Failure(DataError.ServerError), s.titles.refresh(id))
            network.online = false
            network.api.enqueue(NetworkFixture.dropped())
            assertEquals(Outcome.Failure(DataError.NoConnection), s.titles.refresh(id))
            network.online = true
            assertTrue(network.unexpected.isEmpty())

            serve(recordedJson("title_airing").edited(listOf("id")))
            assertEquals(Outcome.Failure(DataError.ServerError), s.titles.refresh(id))
            assertTrue(network.unexpected.single() is UnusableAnswer)
            network.unexpected.clear()

            // An answer MAL gave and the store could not keep.
            s.database.breakWrites("title_details")
            serve(recordedJson("title_airing").edited(listOf("synopsis"), JsonPrimitive("A newer synopsis.")))
            assertEquals(Outcome.Failure(DataError.StorageError), s.titles.refresh(id))
            assertEquals(1, network.unexpected.size)

            assertEquals(before, s.stored())
            assertEquals(6, network.api.requestCount)
        }

    // Section 7.1: a stored value is replaced by MAL's, never by one MAL left out or the mapper filled in.
    @Test
    fun `a refresh of the details does not lose a value the summary had`() =
        runTest {
            val s = subject()
            s.store.addUnknown(listOf(airing.anime))
            val without = listOf("main_picture", "mean", "rank", "genres", "start_date", "broadcast", "num_episodes", "status", "title")
            serve(without.fold(recordedJson("title_airing")) { page, key -> page.edited(listOf(key)) })

            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            assertEquals(airing.anime, s.titles.observeAnime(id).first())
            assertEquals(airing.details, s.titles.observeDetails(id).first())

            // Values read only in part: the stored ones stand.
            val partial =
                listOf(
                    listOf("genres", "0", "name") to null,
                    listOf("broadcast", "start_time") to JsonPrimitive("24:00"),
                    listOf("studios", "0", "name") to null,
                    listOf("related_anime", "0", "node", "id") to null,
                    listOf("recommendations", "0", "node", "id") to null,
                )
            serve(partial.fold(recordedJson("title_airing")) { page, (path, value) -> page.edited(path, value) })
            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            assertEquals(airing.anime, s.titles.observeAnime(id).first())
            assertEquals(airing.details, s.titles.observeDetails(id).first())
        }

    @Test
    fun `a summary that comes after the page, without the status or the main title, does not lose them`() =
        runTest {
            val s = subject()
            val list = RoomLocalList(s.database, backgroundScope, dispatcher, network.unexpected::add)
            list.clear(SignInId("a"))
            serve("title_airing")
            s.titles.refresh(id)

            val summary =
                recordedJson("title_airing")
                    .edited(listOf("status"))
                    .edited(listOf("title"))
                    .toNode()
                    .toAnime(Repairs())
            assertEquals(AiringStatus.Unrecognized(""), summary.airingStatus)
            assertEquals(airing.anime.titles.english, summary.titles.romaji)
            list.download(listOf(summary), emptyMap(), network.clock.now)

            assertEquals(airing.anime, s.titles.observeAnime(id).first())
        }

    @Test
    fun `a store that cannot be read is reported and ends each flow with a storage error`() =
        runTest {
            val s = subject()
            s.database.execute("ALTER TABLE title RENAME TO title_gone")
            s.database.execute("ALTER TABLE title_details RENAME TO title_details_gone")
            for (flow in listOf(
                s.titles.observeAnime(id),
                s.titles.observeDetails(id),
                s.titles.observeRefreshedAt(id),
                s.titles.observePagesRead(),
            )) {
                val ended = runCatching { flow.first() }.exceptionOrNull()
                assertEquals(DataError.StorageError, (ended as DataException).error)
            }
            assertEquals(4, network.unexpected.size)
        }

    @Test
    fun `a title reached from a related row has that row's data before its own page is asked for`() =
        runTest {
            val s = subject()
            serve("title_airing")
            s.titles.refresh(id)
            network.api.received()

            val named = airing.details.related.map { it.anime } + airing.details.recommendations
            assertTrue(named.isNotEmpty())
            for (anime in named) {
                assertEquals(anime, s.titles.observeAnime(anime.id).first())
                assertNull(s.titles.observeDetails(anime.id).first())
                assertNull("never fetched, so stale", s.titles.observeRefreshedAt(anime.id).first())
            }
            assertEquals(1, network.api.requestCount)
        }

    // A title its page names is stored, but its own page is not read.
    @Test
    fun `the pages read are the titles whose own page is stored, not the titles a page names`() =
        runTest {
            val s = subject()
            assertEquals(emptySet<AnimeId>(), s.titles.observePagesRead().first())
            serve("title_airing")
            s.titles.refresh(id)
            assertEquals(setOf(id), s.titles.observePagesRead().first())
        }

    @Test
    fun `a page is asked with adult recommendations exactly when the setting shows adult content`() =
        runTest {
            for (adult in listOf(false, true)) {
                val s = subject(showAdult = adult)
                serve("title_airing")
                s.titles.refresh(id)
                val nsfw =
                    network.api
                        .received()
                        .url
                        .queryParameter("nsfw")
                assertEquals(if (adult) "true" else null, nsfw)
                assertEquals(listOf(adult), s.adult)
            }
        }

    @Test
    fun `a title MAL has under another id is not taken for the one asked`() =
        runTest {
            val s = subject()
            serve("title_airing")
            val asked = AnimeId(id.value + 1)
            assertEquals(Outcome.Failure(DataError.ServerError), s.titles.refresh(asked))
            assertTrue(network.unexpected.single() is AnotherTitleAnswer)
            assertNull(s.titles.observeAnime(asked).first())
            assertNull(s.titles.observeAnime(id).first())
        }

    // S9: a sign-out's clear ends the list a page was asked in, so the answer that comes after it is not stored.
    @Test
    fun `a page asked before a sign-out is not stored after the sign-out cleared the store`() =
        runTest {
            val s = subject()
            val list = RoomLocalList(s.database, backgroundScope, dispatcher, network.unexpected::add)
            list.clear(SignInId("a"))
            val recorded = RecordedAnswers.named("title_airing")
            val arrived = CountDownLatch(1)
            val cleared = CountDownLatch(1)
            network.answers {
                arrived.countDown()
                cleared.await(5, TimeUnit.SECONDS)
                answer(recorded.status, recorded.body, "application/json; charset=UTF-8")
            }

            val refresh = async { s.titles.refresh(id) }
            runCurrent()
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            list.clear(null)
            cleared.countDown()

            assertEquals(Outcome.Failure(DataError.ListEnded), refresh.await())
            assertEquals(Triple(null, null, null), s.stored())
            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            assertEquals(airing.anime, s.titles.observeAnime(id).first())
        }
}
