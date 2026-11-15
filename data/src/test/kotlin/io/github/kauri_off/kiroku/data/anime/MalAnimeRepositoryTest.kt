package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.detailsStale
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.TestDatabases
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.db.breakWrites
import io.github.kauri_off.kiroku.data.db.dispatcher
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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

    @Test
    fun `a refresh of the details does not lose a value the summary had`() =
        runTest {
            val s = subject()
            s.store.addUnknown(listOf(airing.anime))
            val without = listOf("main_picture", "mean", "rank", "genres", "start_date", "broadcast", "num_episodes")
            serve(without.fold(recordedJson("title_airing")) { page, key -> page.edited(listOf(key)) })

            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))

            assertEquals(airing.anime, s.titles.observeAnime(id).first())
            assertEquals(airing.details, s.titles.observeDetails(id).first())
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
            assertEquals(Outcome.Failure(DataError.ServerError), s.titles.refresh(AnimeId(id.value + 1)))
        }
}
