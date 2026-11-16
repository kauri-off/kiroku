package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

// What the engine's contract cannot see of the airing store in the database.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomAiringStoreTest {
    @get:Rule
    val databases = TestDatabases()

    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val other = FixtureAnime.unknownTotal
    private val fetchedAt = Instant.parse("2026-01-11T21:40:00.123456789Z")

    private class Subject(
        val database: KirokuDatabase,
        val store: RoomAiringStore,
        val unexpected: List<Throwable>,
    )

    private fun TestScope.subject(): Subject {
        val database = databases.open(this)
        val unexpected = mutableListOf<Throwable>()
        return Subject(database, RoomAiringStore(database, dispatcher) { unexpected += it }, unexpected)
    }

    private fun stored(anime: AnimeId) = StoredAiring(fixtures.schedules.getValue(anime), fetchedAt)

    @Test
    fun `a write that fails part-way leaves every title as it was`() =
        runTest {
            val s = subject()
            val before = mapOf(anime.id to stored(anime.id), other.id to stored(other.id))
            s.store.write(before)
            s.database.breakWrites(AiringEpisodeRow.TABLE, "INSERT")

            val failed =
                runCatching {
                    s.store.write(
                        mapOf(
                            anime.id to null,
                            other.id to StoredAiring(AiringSchedule(listOf(ScheduledEpisode(1, fixtures.now)), 3), fixtures.now),
                        ),
                    )
                }

            assertTrue(failed.isFailure)
            assertEquals(before, s.store.read())
        }

    @Test
    fun `a title whose rows cannot be read is left out and reported, and every other title is read`() =
        runTest {
            val s = subject()
            s.store.write(mapOf(anime.id to stored(anime.id), other.id to stored(other.id), AnimeId(7) to StoredAiring(null, fetchedAt)))
            val seen = mutableListOf<Map<AnimeId, StoredAiring>>()
            backgroundScope.launch { s.store.observe().collect { seen += it } }
            runCurrent()

            s.database.execute("UPDATE airing_episode SET number = 0 WHERE title_id = ${anime.id.value} AND number = 3")
            s.database.execute("INSERT INTO airing_episode (title_id, number, airs_at) VALUES (7, 1, 0)")
            runCurrent()

            val readable = mapOf(other.id to stored(other.id))
            assertEquals(readable, s.store.read())
            assertEquals(readable, seen.last())
            assertTrue(s.unexpected.isNotEmpty() && s.unexpected.all { it is UnreadableRow })
        }

    @Test
    fun `a clear empties both tables, a title whose rows cannot be read included`() =
        runTest {
            val s = subject()
            s.store.write(mapOf(anime.id to stored(anime.id), other.id to stored(other.id)))
            s.database.execute("UPDATE airing_episode SET number = 0 WHERE title_id = ${anime.id.value} AND number = 3")
            assertEquals(setOf(other.id), s.store.read().keys)

            s.store.clear()
            assertEquals(0L, s.database.rows("airing"))
            assertEquals(0L, s.database.rows("airing_episode"))
        }

    @Test
    fun `a schedule comes back to the nanosecond, with its total, and without one`() =
        runTest {
            val s = subject()
            val episodes =
                listOf(
                    ScheduledEpisode(1, Instant.parse("1969-12-31T23:59:59.999999999Z")),
                    ScheduledEpisode(2, Instant.parse("2026-01-17T14:30:00.000000001Z")),
                    ScheduledEpisode(1000, Instant.parse("2200-01-01T00:00:00Z")),
                )
            val given =
                mapOf(
                    AnimeId(1) to StoredAiring(AiringSchedule(episodes, 1000), fetchedAt),
                    AnimeId(2) to StoredAiring(AiringSchedule(episodes, null), fetchedAt.plusNanos(1)),
                )

            s.store.write(given)

            assertEquals(given, s.store.read())
        }

    @Test
    fun `more titles than a statement takes are written and forgotten in one call`() =
        runTest {
            val s = subject()
            val many = (1L..2 * MAX_VARIABLES + 1).associate { AnimeId(it) to stored(other.id) }

            s.store.write(many)
            assertEquals(many, s.store.read())

            s.store.write(many.mapValues { null })
            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.store.read())
            assertEquals(0L, s.database.rows(AiringEpisodeRow.TABLE))
        }

    @Test
    fun `the list's writes and a title's refresh leave airing data as it is, and airing writes leave them`() =
        runTest {
            val s = subject()
            val list = RoomLocalList(s.database, backgroundScope, dispatcher) { throw it }
            val titles = TitleStore(s.database, dispatcher) { throw it }
            val entry = checkNotNull(fixtures.entryFor(anime.id))
            val airing = mapOf(anime.id to stored(anime.id), AnimeId(7) to StoredAiring(null, fetchedAt))
            s.store.write(airing)

            list.clear(SignInId("a"))
            list.download(listOf(anime), mapOf(anime.id to StoredEntry.settled(entry.record)), fixtures.now)
            titles.refresh(anime, fixtures.details.getValue(anime.id), fixtures.now)
            list.write(mapOf(anime.id to null))
            list.clear(null)
            assertEquals(airing, s.store.read())

            list.clear(SignInId("a"))
            list.download(listOf(anime), mapOf(anime.id to StoredEntry.settled(entry.record)), fixtures.now)
            titles.refresh(anime, fixtures.details.getValue(anime.id), fixtures.now)
            s.store.write(mapOf(anime.id to null, AnimeId(7) to null))
            assertEquals(listOf(entry), list.observeEntries().first())
            assertEquals(fixtures.details.getValue(anime.id), titles.observeDetails(anime.id).first()?.details)
        }
}
