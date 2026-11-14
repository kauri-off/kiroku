package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListRecord
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.Year

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TitleStoreTest {
    @get:Rule
    val databases = TestDatabases()

    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val entry = checkNotNull(fixtures.entryFor(anime.id))
    private val details = fixtures.details.getValue(anime.id)
    private val fetchedAt = Instant.parse("2026-01-11T21:40:00.123456789Z")

    // The title as MAL has it later: every value of its summary another one.
    private val refreshed =
        anime.copy(
            titles = anime.titles.copy(romaji = "Renamed", synonyms = listOf("Renewed")),
            airingStatus = AiringStatus.Finished,
            malEpisodes = 25,
            meanScore = 9.01,
            rank = 7,
        )

    private class Subject(
        val database: KirokuDatabase,
        val titles: TitleStore,
        val list: RoomLocalList,
        val unexpected: List<Throwable>,
    )

    private fun TestScope.subject(): Subject {
        val database = databases.open(this)
        val unexpected = mutableListOf<Throwable>()
        val titles = TitleStore(database, dispatcher) { unexpected += it }
        return Subject(database, titles, RoomLocalList(database, backgroundScope, dispatcher) { unexpected += it }, unexpected)
    }

    private suspend fun TestScope.listed(): Subject =
        subject().also {
            it.list.clear(SignInId("a"))
            it.list.download(
                fixtures.entries.map { entry -> entry.anime },
                fixtures.entries.associate { entry -> entry.anime.id to StoredEntry.settled(entry.record) },
                fetchedAt,
            )
        }

    private fun <T> TestScope.collected(flow: Flow<T>): List<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun edit(watched: Int) =
        StoredEntry(ListRecord(entry.values.copy(watchedEpisodes = watched), fetchedAt), entry.record, unconfirmed = true)

    @Test
    fun `a title refreshed and then edited on the list keeps the refreshed data, and an edit keeps its values through a refresh`() =
        runTest {
            val s = listed()

            s.titles.refresh(refreshed, details, fetchedAt)
            s.list.write(mapOf(anime.id to edit(13)))
            s.list.add(anime, edit(14))
            assertEquals(StoredTitle(refreshed, edit(14)), s.list.read(anime.id))
            assertEquals(ListEntry(refreshed, edit(14).shown!!.values, fetchedAt), s.list.observeEntry(anime.id).first())
            assertEquals(refreshed, s.titles.observeAnime(anime.id).first())

            s.titles.refresh(anime, details, fetchedAt.plusSeconds(60))
            assertEquals(StoredTitle(anime, edit(14)), s.list.read(anime.id))
            assertEquals(edit(14), s.list.readAll()[anime.id])
            s.list.write(mapOf(anime.id to null))
            assertEquals(StoredDetails(details, fetchedAt.plusSeconds(60)), s.titles.observeDetails(anime.id).first())
        }

    @Test
    fun `a download brings a title's summary and leaves its details and their time alone`() =
        runTest {
            val s = listed()
            s.titles.refresh(anime, details, fetchedAt)
            val seen = collected(s.titles.observeDetails(anime.id))

            s.list.download(listOf(refreshed.copy(genres = null)), emptyMap(), fetchedAt.plusSeconds(3600))
            runCurrent()

            assertEquals(refreshed, s.titles.observeAnime(anime.id).first())
            assertEquals(listOf(StoredDetails(details, fetchedAt)), seen)
        }

    @Test
    fun `details come back as MAL gave them, every title's, with the titles they name in MAL's order`() =
        runTest {
            val s = subject()
            assertNull(s.titles.observeDetails(anime.id).first())
            assertNull(s.titles.observeAnime(anime.id).first())

            for (title in fixtures.anime) s.titles.refresh(title, fixtures.details.getValue(title.id), fetchedAt)

            for (title in fixtures.anime) {
                assertEquals(title, s.titles.observeAnime(title.id).first())
                assertEquals(StoredDetails(fixtures.details.getValue(title.id), fetchedAt), s.titles.observeDetails(title.id).first())
            }
            assertTrue(details.related.size > 10 && details.recommendations.size > 1)
            assertTrue(
                details.related.map { it.relation }.let {
                    it != it.sortedBy(RelationType.known::indexOf) ||
                        it.size != it.toSet().size
                },
            )
        }

    @Test
    fun `details with every value and with none come back as they are, also when the same title is named twice`() =
        runTest {
            val s = subject()
            val other = FixtureAnime.film
            val whole =
                AnimeDetails(
                    id = anime.id,
                    synopsis = "A synopsis.",
                    endDate = PartialDate.OnlyYear(Year.of(2026)),
                    season = AnimeSeason(2025, Season.Fall),
                    source = Source.Unrecognized("radio_drama"),
                    studios = listOf("One", "Two, \"quoted\""),
                    rating = AgeRating.Unrecognized("pg_14"),
                    statistics = TitleStatistics(StatusCounts(5, 4, 3, 2, 1), members = 15),
                    related =
                        listOf(
                            RelatedAnime(other, RelationType.Unrecognized("crossover")),
                            RelatedAnime(FixtureAnime.prequel, RelationType.Prequel),
                            RelatedAnime(other, RelationType.Other),
                        ),
                    recommendations = listOf(other, FixtureAnime.prequel, other),
                )
            val bare = AnimeDetails(other.id, null, null, null, null, emptyList(), null, null, emptyList(), emptyList())

            s.titles.refresh(anime, whole, fetchedAt)
            s.titles.refresh(other, bare, Instant.EPOCH)

            assertEquals(StoredDetails(whole, fetchedAt), s.titles.observeDetails(anime.id).first())
            assertEquals(StoredDetails(bare, Instant.EPOCH), s.titles.observeDetails(other.id).first())
        }

    @Test
    fun `a refresh keeps each value it does not bring, replaces the lists, and says when it was made`() =
        runTest {
            val s = subject()
            s.titles.refresh(anime, details, fetchedAt)
            assertTrue(
                listOf(details.synopsis, details.endDate, details.season, details.source, details.rating, details.statistics).all {
                    it !=
                        null
                },
            )
            val newer =
                details.copy(
                    synopsis = null,
                    endDate = null,
                    season = null,
                    source = null,
                    rating = null,
                    statistics = null,
                    studios = emptyList(),
                    related = details.related.take(2).reversed(),
                    recommendations = emptyList(),
                )

            s.titles.refresh(refreshed.copy(genres = null, posterUrl = null), newer, fetchedAt.plusSeconds(60))

            assertEquals(refreshed, s.titles.observeAnime(anime.id).first())
            assertEquals(
                StoredDetails(
                    details.copy(studios = emptyList(), related = newer.related, recommendations = emptyList()),
                    fetchedAt.plusSeconds(60),
                ),
                s.titles.observeDetails(anime.id).first(),
            )

            val changed = details.copy(synopsis = "Another.", statistics = TitleStatistics(StatusCounts(1, 1, 1, 1, 1), 5))
            s.titles.refresh(anime, changed, fetchedAt)
            assertEquals(StoredDetails(changed, fetchedAt), s.titles.observeDetails(anime.id).first())
        }

    @Test
    fun `a title a page only names is stored as the page has it, and never changes a title already stored`() =
        runTest {
            val s = listed()
            val stored = FixtureAnime.exactCaughtUp
            val guessed =
                stored.copy(
                    titles = stored.titles.copy(romaji = "Guessed"),
                    airingStatus = AiringStatus.NotYetAired,
                    genres = null,
                )
            val unknown = FixtureAnime.relatedSpinOff.copy(meanScore = null, rank = null, malEpisodes = null, genres = null)
            val naming =
                details.copy(
                    related = listOf(RelatedAnime(guessed, RelationType.Sequel), RelatedAnime(unknown, RelationType.SpinOff)),
                    recommendations = listOf(guessed),
                )

            s.titles.refresh(anime, naming, fetchedAt)
            s.titles.addUnknown(listOf(guessed, anime.copy(malEpisodes = 99)))

            assertEquals(stored, s.list.read(stored.id)?.anime)
            assertEquals(anime, s.titles.observeAnime(anime.id).first())
            assertEquals(StoredTitle(unknown, entry = null), s.list.read(unknown.id))
            val named =
                s.titles
                    .observeDetails(anime.id)
                    .first()
                    ?.details
            assertEquals(listOf(RelatedAnime(stored, RelationType.Sequel), RelatedAnime(unknown, RelationType.SpinOff)), named?.related)
            assertEquals(listOf(stored), named?.recommendations)

            val full = FixtureAnime.relatedSpinOff
            s.titles.refresh(
                full,
                fixtures.details.getValue(anime.id).copy(id = full.id, related = emptyList(), recommendations = emptyList()),
                fetchedAt,
            )
            assertEquals(
                full,
                s.titles
                    .observeDetails(anime.id)
                    .first()
                    ?.details
                    ?.related
                    ?.last()
                    ?.anime,
            )
        }

    @Test
    fun `a refresh that brings the summary already stored writes no title, so the list is not read again`() =
        runTest {
            val s = listed()
            s.titles.refresh(anime, details, fetchedAt)
            val lists = collected(s.list.observeEntries())
            val written = written(s.database)
            val reads = databases.statements.count { LISTED_QUERY in it }

            s.titles.refresh(anime, details, fetchedAt.plusSeconds(60))
            runCurrent()
            assertEquals(listOf(setOf("title_details")), written)
            assertEquals(reads, databases.statements.count { LISTED_QUERY in it })

            s.titles.refresh(refreshed, details.copy(related = emptyList()), fetchedAt.plusSeconds(120))
            runCurrent()
            assertEquals(setOf("title", "title_details", "title_relation"), written.last())
            assertEquals(2, lists.size)
            assertEquals(refreshed, lists.last().single { it.anime.id == anime.id }.anime)
        }

    @Test
    fun `a refresh that fails part-way changes nothing`() =
        runTest {
            val s = listed()
            s.titles.refresh(anime, details, fetchedAt)
            val unknown = FixtureAnime.film.copy(id = AnimeId(424_242))
            val newer = details.copy(synopsis = "Another.", related = listOf(RelatedAnime(unknown, RelationType.Sequel)))

            s.database.breakWrites("title_recommendation")
            assertTrue(
                runCatching { s.titles.refresh(refreshed, newer.copy(recommendations = emptyList()), fetchedAt.plusSeconds(60)) }.isFailure,
            )
            s.database.repair("title_recommendation")

            assertEquals(anime, s.titles.observeAnime(anime.id).first())
            assertEquals(StoredDetails(details, fetchedAt), s.titles.observeDetails(anime.id).first())
            assertNull(s.titles.observeAnime(unknown.id).first())
        }

    @Test
    fun `one title is observed by itself, its summary and its details, and hears nothing of another's change`() =
        runTest {
            val s = listed()
            s.titles.refresh(anime, details, fetchedAt)
            val summaries = collected(s.titles.observeAnime(anime.id))
            val pages = collected(s.titles.observeDetails(anime.id))
            val other = FixtureAnime.film

            s.titles.refresh(other.copy(rank = 1), fixtures.details.getValue(other.id), fetchedAt)
            s.list.write(mapOf(anime.id to edit(13)))
            runCurrent()
            assertEquals(listOf<Anime?>(anime), summaries)
            assertEquals(listOf<StoredDetails?>(StoredDetails(details, fetchedAt)), pages)

            s.titles.refresh(refreshed, details, fetchedAt)
            runCurrent()
            assertEquals(listOf<Anime?>(anime, refreshed), summaries)
            assertEquals(1, pages.size)
        }

    @Test
    fun `a clear that ends or begins a sign-in's list removes every title and what its page holds`() =
        runTest {
            val s = listed()
            for (title in fixtures.anime) s.titles.refresh(title, fixtures.details.getValue(title.id), fetchedAt)
            assertTrue(TABLES.all { s.database.rows(it) > 0 })

            s.list.clear(null)
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1L), TABLES.map { s.database.rows(it) })
            assertNull(s.titles.observeAnime(anime.id).first())
            assertNull(s.titles.observeDetails(anime.id).first())

            s.titles.refresh(anime, details, fetchedAt)
            s.list.clear(SignInId("b"))
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1L), TABLES.map { s.database.rows(it) })

            s.titles.refresh(anime, details, fetchedAt)
            s.list.clear(null)
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1L), TABLES.map { s.database.rows(it) })
        }

    @Test
    fun `a clear of nobody's empty list, as every start signed out makes, keeps the titles opened since the sign-out`() =
        runTest {
            val s = subject()
            s.list.clear(null)
            s.titles.refresh(anime, details, fetchedAt)

            s.list.clear(null)
            assertEquals(StoredDetails(details, fetchedAt), s.titles.observeDetails(anime.id).first())

            s.list.add(anime, StoredEntry.settled(entry.record))
            s.list.clear(null)
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1L), TABLES.map { s.database.rows(it) })
        }

    @Test
    fun `a title a page names whose row cannot be read is left out of the page and reported by its type`() =
        runTest {
            val s = subject()
            s.titles.refresh(anime, details, fetchedAt)
            val related = details.related[1]
            val recommended = details.recommendations[0]
            val ids = "${related.anime.id.value}, ${recommended.id.value}"

            s.database.execute("UPDATE title SET airing_status = 'broken' WHERE id IN ($ids)")

            val shown = s.titles.observeDetails(anime.id).first()
            assertEquals(
                StoredDetails(
                    details.copy(
                        related = details.related - related,
                        recommendations =
                            details.recommendations - recommended,
                    ),
                    fetchedAt,
                ),
                shown,
            )
            assertEquals(listOf("java.lang.IllegalStateException", "java.lang.IllegalStateException"), s.unexpected.map { it.message })
            assertTrue(s.unexpected.all { it is UnreadableRow })
            assertTrue(runCatching { s.titles.observeAnime(related.anime.id).first() }.isFailure)
        }

    @Test
    fun `a title's details must be its own`() =
        runTest {
            val s = subject()
            assertTrue(
                runCatching { s.titles.refresh(FixtureAnime.film, details, fetchedAt) }.exceptionOrNull() is IllegalArgumentException,
            )
        }
}
