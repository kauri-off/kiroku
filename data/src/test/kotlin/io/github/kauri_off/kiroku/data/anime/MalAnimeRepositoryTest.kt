package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitlePicture
import io.github.kauri_off.kiroku.core.model.chainWalk
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.model.sequelChain
import io.github.kauri_off.kiroku.core.repository.detailsStale
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.TestDatabases
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.db.breakWrites
import io.github.kauri_off.kiroku.data.db.dispatcher
import io.github.kauri_off.kiroku.data.db.execute
import io.github.kauri_off.kiroku.data.list.MalRemoteList
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The titles of section 7.1 over the store and the client, with recorded pages behind a local server.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MalAnimeRepositoryTest {
    @get:Rule
    internal val network = NetworkFixture()

    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val folder = TemporaryFolder()

    private val airing = recordedTitle("title_airing")
    private val id = airing.anime.id

    private class Subject(
        val titles: MalAnimeRepository,
        val store: TitleStore,
        val database: KirokuDatabase,
        val preferences: StoredPreferences,
    )

    private fun TestScope.subject(clock: Clock = network.clock): Subject {
        val database = databases.open(this)
        val store = TitleStore(database, dispatcher, network.unexpected::add)
        val preferences = StoredPreferences(File(folder.newFolder(), "title.preferences_pb"), backgroundScope, network.unexpected::add)
        return Subject(
            MalAnimeRepository(MalTitles(network.http), store, clock, preferences, network.unexpected::add),
            store,
            database,
            preferences,
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
    fun `the recorded anime gallery excludes the JPG copy of the WebP cover and keeps provider order in Room`() =
        runTest {
            val subject = subject()
            serve("title_pictures")
            val id = AnimeId(1)
            assertEquals(Outcome.Success(Unit), subject.titles.refresh(id))
            assertTrue(
                network.api
                    .received()
                    .url
                    .queryParameter("fields")
                    .orEmpty()
                    .split(',')
                    .contains("pictures"),
            )
            val pictures = checkNotNull(subject.titles.observeDetails(id).first()).pictures
            val recorded = recordedJson("title_pictures")["pictures"] as JsonArray
            val extras = recorded.filterIndexed { index, _ -> index != 2 }
            assertEquals(extras.map { ((it as JsonObject)["medium"] as JsonPrimitive).content }, pictures.map { it.medium })
            assertEquals(extras.map { ((it as JsonObject)["large"] as JsonPrimitive).content }, pictures.map { it.large })
            assertEquals(setOf("status"), (network.unexpected.single() as RepairedAnswer).fields)
            assertTrue(airing.details.pictures.isEmpty())
        }

    @Test
    fun `anime keeps its complete gallery on a pictures repair and clears it on a clean empty answer`() =
        runTest {
            val repository = subject().titles
            val id = AnimeId(1)
            val page = recordedJson("title_pictures")
            val complete = page.toTitle(Repairs()).details.pictures
            val first = (page.getValue("pictures") as JsonArray).first()
            val partial = page.edited(listOf("pictures"), JsonArray(listOf(first, JsonNull)))

            suspend fun refresh(answer: JsonObject): List<TitlePicture> {
                serve(answer)
                assertEquals(Outcome.Success(Unit), repository.refresh(id))
                return checkNotNull(repository.observeDetails(id).first()).pictures
            }

            assertEquals(complete.take(1), refresh(partial))
            assertEquals(complete, refresh(page))
            val warm = refresh(partial)
            val empty = refresh(page.edited(listOf("pictures"), JsonArray(emptyList())))
            assertEquals(
                listOf(setOf("pictures", "status"), setOf("status"), setOf("pictures", "status"), setOf("status")),
                network.unexpected.map { (it as RepairedAnswer).fields },
            )
            assertTrue(empty.isEmpty())
            assertEquals(complete, warm)
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
    fun `each page refresh reads the current adult setting and updates stored recommendations`() =
        runTest {
            val s = subject()
            val off = recordedTitle("title_adult_nsfw_unset")
            val on = recordedTitle("title_adult_nsfw_true")
            val title = off.anime.id
            for (adult in listOf(false, true, false)) {
                s.preferences.setAdultInSearch(adult)
                serve(if (adult) "title_adult_nsfw_true" else "title_adult_nsfw_unset")
                assertEquals(Outcome.Success(Unit), s.titles.refresh(title))
                assertEquals(
                    if (adult) "true" else null,
                    network.api
                        .received()
                        .url
                        .queryParameter("nsfw"),
                )
                val stored = s.titles.observeDetails(title).first()!!
                assertEquals((if (adult) on.details else off.details).copy(recommendationsIncludeAdult = adult), stored)
                assertEquals(stored.recommendations, stored.recommendationsFor(adult))
                assertNull(stored.recommendationsFor(!adult))
                assertEquals(off.details.related, stored.related)
                assertEquals(off.anime, s.titles.observeAnime(title).first())
            }
        }

    @Test
    fun `an outstanding opted-in response cannot replace the safe recommendations after opting out`() =
        runTest {
            val s = subject()
            val off = recordedTitle("title_adult_nsfw_unset")
            val title = off.anime.id
            serve("title_adult_nsfw_unset")
            assertEquals(Outcome.Success(Unit), s.titles.refresh(title))

            s.preferences.setAdultInSearch(true)
            val recorded = RecordedAnswers.named("title_adult_nsfw_true")
            val arrived = CountDownLatch(1)
            val optedOut = CountDownLatch(1)
            network.answers {
                arrived.countDown()
                optedOut.await(5, TimeUnit.SECONDS)
                answer(recorded.status, recorded.body, "application/json; charset=UTF-8")
            }
            val refresh = async { s.titles.refresh(title) }
            runCurrent()
            withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }
            s.preferences.setAdultInSearch(false)
            optedOut.countDown()

            assertEquals(Outcome.Success(Unit), refresh.await())
            assertEquals(off.details, s.titles.observeDetails(title).first())
        }

    @Test
    fun `a discarded first response keeps title details but establishes no recommendation cache`() =
        runTest {
            val s = subject()
            val adult = recordedTitle("title_adult_nsfw_true")
            val safe = recordedTitle("title_adult_nsfw_unset")
            val title = adult.anime.id
            s.preferences.setAdultInSearch(true)
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recorded = RecordedAnswers.named("title_adult_nsfw_true")
            network.answers {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                answer(recorded.status, recorded.body, "application/json; charset=UTF-8")
            }
            val outstanding = async { s.titles.refresh(title) }
            withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }
            s.preferences.setAdultInSearch(false)
            release.countDown()
            assertEquals(Outcome.Success(Unit), outstanding.await())
            val freshReader = MalAnimeRepository(MalTitles(network.http), s.store, network.clock, s.preferences, network.unexpected::add)
            val stored = freshReader.observeDetails(title).first()!!
            assertEquals(adult.details.copy(recommendations = emptyList(), recommendationsIncludeAdult = null), stored)
            assertEquals(adult.anime, freshReader.observeAnime(title).first())
            assertNull(stored.recommendationsFor(false))
            assertNull(stored.recommendationsFor(true))

            network.answers {
                val response = RecordedAnswers.named("title_adult_nsfw_unset")
                answer(response.status, response.body, "application/json; charset=UTF-8")
            }
            assertEquals(Outcome.Success(Unit), freshReader.refresh(title))
            assertEquals(safe.details.recommendations, freshReader.observeDetails(title).first()?.recommendationsFor(false))
        }

    @Test
    fun `an empty recorded response establishes a valid recommendation cache`() =
        runTest {
            val s = subject()
            val empty = recordedTitle("title_not_yet_aired")
            serve("title_not_yet_aired")
            assertEquals(Outcome.Success(Unit), s.titles.refresh(empty.anime.id))
            assertEquals(
                emptyList<Any>(),
                s.titles
                    .observeDetails(empty.anime.id)
                    .first()
                    ?.recommendationsFor(false),
            )
        }

    @Test
    fun `a filter round trip invalidates a request even before any newer publication`() =
        runTest {
            val s = subject()
            val title = recordedTitle("title_adult_nsfw_unset").anime.id
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recorded = RecordedAnswers.named("title_adult_nsfw_unset")
            network.answers {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                answer(recorded.status, recorded.body, "application/json; charset=UTF-8")
            }
            val old = async { s.titles.refresh(title) }
            withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }
            s.preferences.setAdultInSearch(true)
            s.preferences.setAdultInSearch(false)
            release.countDown()
            assertEquals(Outcome.Success(Unit), old.await())
            assertNotNull(s.titles.observeDetails(title).first())
            assertNull(
                s.titles
                    .observeDetails(title)
                    .first()
                    ?.recommendationsFor(false),
            )
        }

    @Test
    fun `unread first recommendations preserve the title and related fields without claiming an empty result`() =
        runTest {
            val s = subject()
            serve(recordedJson("title_airing").edited(listOf("recommendations", "0", "node", "id")))
            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            assertEquals(
                airing.details.copy(recommendations = emptyList(), recommendationsIncludeAdult = null),
                s.titles.observeDetails(id).first(),
            )
        }

    @Test
    fun `an older response cannot replace a newer same-mode publication`() =
        runTest {
            val s = subject()
            val page = recordedJson("title_adult_nsfw_unset")
            val title = recordedTitle("title_adult_nsfw_unset").anime.id
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dispatched = AtomicInteger()
            network.answers {
                if (dispatched.getAndIncrement() == 0) {
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    answer(200, page.toString(), "application/json; charset=UTF-8")
                } else {
                    answer(
                        200,
                        page.edited(listOf("recommendations"), JsonArray(emptyList())).toString(),
                        "application/json; charset=UTF-8",
                    )
                }
            }
            val old = async { s.titles.refresh(title) }
            withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }
            assertEquals(Outcome.Success(Unit), s.titles.refresh(title))
            val newer = s.titles.observeDetails(title).first()
            assertEquals(emptyList<Any>(), newer?.recommendationsFor(false))
            release.countDown()
            assertEquals(Outcome.Success(Unit), old.await())
            assertEquals(newer, s.titles.observeDetails(title).first())
        }

    @Test
    fun `a readable earlier set fills a gap left by a newer unread response without replacing its details`() =
        runTest {
            val s = subject()
            val older = recordedJson("title_airing")
            val newer =
                older
                    .edited(listOf("synopsis"), JsonPrimitive("A newer synopsis."))
                    .edited(listOf("related_anime"), JsonArray(emptyList()))
                    .edited(listOf("recommendations", "0", "node", "id"))
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dispatched = AtomicInteger()
            network.answers {
                val page =
                    if (dispatched.getAndIncrement() == 0) {
                        arrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        older
                    } else {
                        newer
                    }
                answer(200, page.toString(), "application/json; charset=UTF-8")
            }
            val old = async { s.titles.refresh(id) }
            withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }
            assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
            val before = s.stored()
            assertEquals("A newer synopsis.", before.second?.synopsis)
            assertEquals(emptyList<Any>(), before.second?.related)
            assertNull(before.second?.recommendationsFor(false))
            network.clock.now += Duration.ofSeconds(60)
            release.countDown()
            assertEquals(Outcome.Success(Unit), old.await())
            val after = s.stored()
            assertEquals(before.first, after.first)
            assertEquals(before.third, after.third)
            assertEquals(
                before.second?.copy(recommendationsIncludeAdult = false, recommendations = airing.details.recommendations),
                after.second,
            )
        }

    @Test
    fun `changing the filter waits for a validated recommendation publication to commit`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val clock =
                object : Clock() {
                    override fun instant(): Instant {
                        entered.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                        return network.clock.instant()
                    }

                    override fun getZone(): ZoneId = network.clock.zone

                    override fun withZone(zone: ZoneId): Clock = network.clock.withZone(zone)
                }
            val s = subject(clock)
            val adult = recordedTitle("title_adult_nsfw_true")
            val title = adult.anime.id
            s.preferences.setAdultInSearch(true)
            serve("title_adult_nsfw_true")
            val refresh = async(Dispatchers.IO) { s.titles.refresh(title) }
            entered.await()
            try {
                val change = async { s.preferences.setAdultInSearch(false) }
                runCurrent()
                assertFalse(change.isCompleted)
                assertTrue(s.preferences.observeAdultInSearch().first())
                release.countDown()
                assertEquals(Outcome.Success(Unit), refresh.await())
                assertEquals(Outcome.Success(Unit), change.await())
                assertFalse(s.preferences.observeAdultInSearch().first())
                val stored = s.titles.observeDetails(title).first()!!
                assertEquals(adult.details.recommendations, stored.recommendationsFor(true))
                assertNull(stored.recommendationsFor(false))
            } finally {
                release.countDown()
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

    // S9: an answer that comes after a sign-out's clear is not stored, also when the store is another user's by then.
    @Test
    fun `a page asked before a sign-out is not stored after the sign-out cleared the store`() {
        for (next in listOf(listOf(null), listOf(null, SignInId("b")))) {
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
                withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }
                next.forEach { list.clear(it) }
                cleared.countDown()

                assertEquals(Outcome.Failure(DataError.ListEnded), refresh.await())
                assertEquals(Triple(null, null, null), s.stored())
                assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
                assertEquals(airing.anime, s.titles.observeAnime(id).first())
            }
        }
    }

    @Test
    fun `a guest page request cannot repopulate the cache after signing in and out`() =
        runTest {
            val s = subject()
            val sessions = SessionFixture(folder.newFolder())
            val session = sessions.open(dispatcher = dispatcher)
            val local = RoomLocalList(s.database, backgroundScope, dispatcher, network.unexpected::add)
            val list =
                ListEngine(MalRemoteList(network.http, session), local, session.observeSession(), network.clock, backgroundScope) {
                    network.unexpected += it
                }
            val release = CountDownLatch(1)
            try {
                assertEquals(Session.SignedOut, session.observeSession().first())
                list.observeSyncedAt().first()
                val originalGuest = session.observeOwner().first()
                val recorded = RecordedAnswers.named("title_airing")
                network.answers {
                    check(release.await(5, TimeUnit.SECONDS))
                    answer(recorded.status, recorded.body)
                }

                val request = async { s.titles.refresh(id) }
                val sent = withContext(Dispatchers.IO) { network.api.received() }
                assertEquals("/v2/anime/${id.value}", sent.url.encodedPath)
                assertNull(sent.headers["Authorization"])

                assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access", "refresh")))
                val signedIn = checkNotNull(session.currentSession()?.owner)
                list.observeSyncedAt().first()
                assertEquals(signedIn, local.state().owner)
                assertTrue(s.store.refresh(airing.anime, airing.details, network.clock.now))
                assertEquals(setOf(id), s.titles.observePagesRead().first())

                assertTrue(session.signOutIfCurrent(signedIn) {})
                list.observeSyncedAt().first()
                assertNull(local.state().owner)
                assertFalse(session.isCurrentOwner(originalGuest))
                assertEquals(Triple(null, null, null), s.stored())
                assertFalse(request.isCompleted)

                release.countDown()
                val result = request.await()
                assertTrue(network.unexpected.isEmpty())
                val pages = s.titles.observePagesRead().first()
                assertEquals("the retired guest response must leave the page cache empty", emptySet<AnimeId>(), pages)
                assertEquals(Triple(null, null, null), s.stored())
                assertEquals(Outcome.Failure(DataError.ListEnded), result)
                assertEquals(Outcome.Success(Unit), s.titles.refresh(id))
                assertEquals(Triple(airing.anime, airing.details, network.clock.now), s.stored())
            } finally {
                release.countDown()
                sessions.close()
            }
        }
}
