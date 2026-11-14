package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListRecord
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredList
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.Year
import java.time.YearMonth

// What the engine's contract cannot see of the store in the database.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomLocalListTest {
    @get:Rule
    val databases = TestDatabases()

    private val fixtures = Fixtures.reference
    private val owner = SignInId("a")
    private val syncedAt = Instant.parse("2026-01-11T21:40:00.123456789Z")

    private class Subject(
        val database: KirokuDatabase,
        val store: RoomLocalList,
        val unexpected: List<Throwable>,
    )

    private fun TestScope.subject(): Subject {
        val database = databases.open(this)
        val unexpected = mutableListOf<Throwable>()
        return Subject(database, RoomLocalList(database, backgroundScope, dispatcher) { unexpected += it }, unexpected)
    }

    private suspend fun TestScope.seeded(entries: List<ListEntry> = fixtures.entries): Subject =
        subject().also {
            it.store.clear(owner)
            it.store.download(entries.map { entry -> entry.anime }, entries.settled(), syncedAt)
        }

    private fun List<ListEntry>.settled() = associate { it.anime.id to StoredEntry.settled(it.record) }

    private fun TestScope.lists(subject: Subject): List<List<ListEntry>> {
        val seen = mutableListOf<List<ListEntry>>()
        backgroundScope.launch { subject.store.observeEntries().collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun ListEntry.watched(count: Int) =
        StoredEntry.settled(ListRecord(values.copy(watchedEpisodes = count), updatedAt.plusNanos(1)))

    private fun large(size: Int): List<ListEntry> =
        List(size) { at ->
            val entry = fixtures.entries[at % fixtures.entries.size]
            entry.copy(anime = entry.anime.copy(id = AnimeId(1_000_000L + at)))
        }

    @Test
    fun `with 3000 entries, a write of one emits a list whose other entries are the same objects`() =
        runTest {
            val entries = large(3000)
            val s = seeded(entries)
            val seen = lists(s)
            val edited = entries[1234]

            s.store.write(mapOf(edited.anime.id to edited.watched(99)))
            runCurrent()

            assertEquals(2, seen.size)
            val (before, after) = seen
            assertEquals(entries.sortedBy { it.anime.id.value }, before)
            assertEquals(3000, after.size)
            for ((old, new) in before.zip(after)) {
                if (old.anime.id == edited.anime.id) assertEquals(99, new.values.watchedEpisodes) else assertSame(old, new)
            }
            assertSame(after, s.store.observeEntries().first())
        }

    @Test
    fun `a title's data that a download changes makes a new entry of that title alone`() =
        runTest {
            val s = seeded()
            val seen = lists(s)
            val renewed = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.Finished, malEpisodes = 13)

            s.store.download(fixtures.entries.map { if (it.anime.id == renewed.id) renewed else it.anime }, emptyMap(), syncedAt)
            runCurrent()

            assertEquals(2, seen.size)
            for ((old, new) in seen[0].zip(seen[1])) {
                if (old.anime.id == renewed.id) assertEquals(old.copy(anime = renewed), new) else assertSame(old, new)
            }
        }

    @Test
    fun `a download that changes nothing writes no row of a title or of an entry`() =
        runTest {
            val s = seeded()
            val seen = lists(s)
            val written = written(s.database)
            val reads = databases.statements.count { LISTED_QUERY in it }

            s.store.download(fixtures.entries.map { it.anime }, fixtures.entries.settled(), syncedAt.plusSeconds(60))
            runCurrent()
            assertEquals(listOf(setOf("list_state")), written)
            s.store.download(fixtures.entries.map { it.anime }, emptyMap(), syncedAt.plusSeconds(60))
            s.store.write(fixtures.entries.take(3).settled())
            s.store.add(
                FixtureAnime.exactToWatch,
                StoredEntry.settled(checkNotNull(fixtures.entryFor(FixtureAnime.exactToWatch.id)).record),
            )
            s.store.restore(emptyMap())
            runCurrent()

            assertEquals(listOf(setOf("list_state"), setOf("list_state")), written)
            assertEquals(1, seen.size)
            assertEquals(reads, databases.statements.count { LISTED_QUERY in it })

            s.store.download(listOf(FixtureAnime.exactToWatch.copy(rank = 1)), emptyMap(), syncedAt.plusSeconds(60))
            runCurrent()
            assertEquals(setOf("title", "list_state"), written.last())
            assertEquals(2, seen.size)
        }

    @Test
    fun `a change is read once for all the list's collectors, and a collector never starts with an older list`() =
        runTest {
            val s = seeded()
            val collectors = List(3) { mutableListOf<List<ListEntry>>() }
            val jobs = collectors.map { seen -> launch { s.store.observeEntries().collect { seen += it } } }
            runCurrent()
            val edited = fixtures.entries.first()
            val reads = { databases.statements.count { LISTED_QUERY in it } }
            val before = reads()

            s.store.write(mapOf(edited.anime.id to edited.watched(99)))
            runCurrent()

            assertEquals(before + 1, reads())
            for (seen in collectors) assertEquals(2, seen.size)
            for (seen in collectors) assertSame(collectors[0][1], seen[1])

            s.store.write(mapOf(edited.anime.id to edited.watched(100)))
            assertEquals(
                100,
                s.store
                    .observeEntries()
                    .first()
                    .single { it.anime.id == edited.anime.id }
                    .values.watchedEpisodes,
            )
            jobs.forEach(Job::cancel)
            s.store.write(mapOf(edited.anime.id to edited.watched(101)))
            assertEquals(
                101,
                s.store
                    .observeEntries()
                    .first()
                    .single { it.anime.id == edited.anime.id }
                    .values.watchedEpisodes,
            )
            runCurrent()
            val idle = reads()
            s.store.write(mapOf(edited.anime.id to edited.watched(102)))
            runCurrent()
            assertEquals(idle, reads())
        }

    // The collector joins at each of forty moments of three writes, alone and beside another collector.
    @Test
    fun `a collector that joins while the list changes misses no change and never goes back to an older list`() =
        runTest {
            val s = seeded()
            val edited = fixtures.entries.first()
            var count = 100
            for (round in 0 until 80) {
                val beside = if (round % 2 == 0) null else launch { s.store.observeEntries().collect {} }
                runCurrent()
                val seen = mutableListOf<Int>()
                val writes = launch { repeat(3) { s.store.write(mapOf(edited.anime.id to edited.watched(++count))) } }
                repeat(round / 2) { yield() }
                val joined =
                    launch {
                        s.store.observeEntries().collect { list ->
                            seen +=
                                list.single { it.anime.id == edited.anime.id }.values.watchedEpisodes
                        }
                    }
                writes.join()
                runCurrent()

                assertEquals("round $round", seen.sorted(), seen)
                assertEquals("round $round", count, seen.last())
                joined.cancel()
                beside?.cancel()
            }
        }

    @Test
    fun `one collector of the list sees an unchanged entry as the same object from its first list to its last`() =
        runTest {
            val s = seeded()
            val seen = lists(s)
            val edited = fixtures.entries.first()

            for (count in 20..22) {
                s.store.write(mapOf(edited.anime.id to edited.watched(count)))
                runCurrent()
            }

            assertEquals(4, seen.size)
            for ((old, new) in seen.first().zip(seen.last())) if (old.anime.id != edited.anime.id) assertSame(old, new)
        }

    @Test
    fun `a row that cannot be read is left out of the list and reported once by its type, and fails only what asks for it`() =
        runTest {
            val s = seeded()
            val anime = FixtureAnime.exactToWatch
            val other = fixtures.entries.last()
            val seen = lists(s)
            val ended = mutableListOf<Throwable>()
            backgroundScope.launch { runCatching { s.store.observeEntry(anime.id).collect {} }.onFailure { ended += it } }
            runCurrent()

            s.database.execute("UPDATE title SET airing_status = 'broken' WHERE id = ${anime.id.value}")
            runCurrent()
            s.store.write(mapOf(other.anime.id to other.watched(99)))
            runCurrent()

            val ids = fixtures.entries.map { it.anime.id }.sortedBy { it.value }
            assertEquals(ids - anime.id, seen.last().map { it.anime.id })
            assertEquals(seen.last(), s.store.observeEntries().first())
            assertEquals(listOf("java.lang.IllegalStateException"), s.unexpected.map { it.message })
            assertTrue(s.unexpected.all { it is UnreadableRow && it.cause == null && it.stackTrace.isNotEmpty() })
            assertEquals(1, ended.size)
            assertTrue(runCatching { s.store.observeEntry(anime.id).first() }.isFailure)
            assertTrue(runCatching { s.store.read(anime.id) }.isFailure)
            assertEquals(fixtures.entries.size, s.store.readAll().size)

            s.database.execute("UPDATE title SET airing_status = 'airing' WHERE id = ${anime.id.value}")
            assertEquals(
                ids,
                s.store
                    .observeEntries()
                    .first()
                    .map { it.anime.id },
            )
            assertNotNull(s.store.observeEntry(anime.id).first())
            assertEquals(1, s.unexpected.size)
        }

    @Test
    fun `an entry that cannot be read is left out of what a sync reads and reported, and the next download writes it anew`() =
        runTest {
            val s = seeded()
            val broken = fixtures.entries.first()

            s.database.execute("UPDATE list_entry SET confirmed_status = 'broken' WHERE title_id = ${broken.anime.id.value}")

            assertEquals(
                fixtures.entries.map { it.anime.id }.sortedBy { it.value } - broken.anime.id,
                s.store
                    .readAll()
                    .keys
                    .toList(),
            )
            assertEquals(listOf("java.lang.IllegalStateException"), s.unexpected.map { it.message })
            s.store.download(fixtures.entries.map { it.anime }, mapOf(broken.anime.id to StoredEntry.settled(broken.record)), syncedAt)
            assertEquals(fixtures.entries.settled(), s.store.readAll())
        }

    @Test
    fun `a query that fails ends every collector with its failure, and a new collection reads again`() =
        runTest {
            val s = seeded()
            val anime = FixtureAnime.exactToWatch
            val edited = checkNotNull(fixtures.entryFor(anime.id))
            val ended = mutableListOf<Throwable>()
            backgroundScope.launch { runCatching { s.store.observeEntries().collect {} }.onFailure { ended += it } }
            backgroundScope.launch { runCatching { s.store.observeEntry(anime.id).collect {} }.onFailure { ended += it } }
            runCurrent()

            s.database.execute("ALTER TABLE list_entry RENAME COLUMN shown_watched TO lost")
            runCurrent()
            s.database.execute("UPDATE title SET rank = 1 WHERE id = ${anime.id.value}")
            runCurrent()

            assertEquals(2, ended.size)
            assertTrue(runCatching { s.store.observeEntries().first() }.isFailure)
            assertTrue(runCatching { s.store.readAll() }.isFailure)
            assertTrue(runCatching { s.store.write(mapOf(anime.id to edited.watched(99))) }.isFailure)

            s.database.execute("ALTER TABLE list_entry RENAME COLUMN lost TO shown_watched")
            assertEquals(
                fixtures.entries.size,
                s.store
                    .observeEntries()
                    .first()
                    .size,
            )
        }

    @Test
    fun `a closed database ends a collection and a call with a failure`() =
        runTest {
            val s = seeded()
            s.database.close()

            assertTrue(runCatching { s.store.observeEntries().first() }.isFailure)
            assertTrue(runCatching { s.store.observeEntry(FixtureAnime.exactToWatch.id).first() }.isFailure)
            assertTrue(runCatching { s.store.state() }.isFailure)
            assertTrue(runCatching { s.store.clear(null) }.isFailure)
        }

    @Test
    fun `a call that fails part-way changes nothing`() =
        runTest {
            val s = seeded()
            val new = FixtureAnime.notOnListAiring
            val edited = fixtures.entries.first()
            val renewed = edited.anime.copy(rank = 1)
            val before = s.store.readAll()

            s.database.breakWrites("list_state")
            val later = syncedAt.plusSeconds(60)
            assertTrue(
                runCatching { s.store.download(listOf(renewed, new), mapOf(edited.anime.id to edited.watched(99)), later) }.isFailure,
            )
            assertTrue(runCatching { s.store.restore(mapOf(edited.anime.id to null)) }.isFailure)
            assertTrue(runCatching { s.store.clear(SignInId("b")) }.isFailure)
            s.database.repair("list_state")
            s.database.breakWrites("list_entry", "INSERT", "UPDATE")
            assertTrue(runCatching { s.store.add(new, edited.watched(1)) }.isFailure)
            assertTrue(
                runCatching { s.store.write(mapOf(fixtures.entries[1].anime.id to null, edited.anime.id to edited.watched(99))) }.isFailure,
            )
            s.database.repair("list_entry", "INSERT", "UPDATE")

            assertEquals(before, s.store.readAll())
            assertEquals(StoredList(owner, syncedAt), s.store.state())
            assertEquals(edited.anime, s.store.read(edited.anime.id)?.anime)
            assertNull(s.store.read(new.id))
            s.store.add(new, edited.watched(1))
            assertEquals(StoredTitle(new, edited.watched(1)), s.store.read(new.id))
        }

    @Test
    fun `an entry comes back as it was written, to the nanosecond and with the precision of its dates`() =
        runTest {
            val s = seeded()
            val shown =
                ListRecord(
                    ListValues(ListStatus.OnHold, 7, 10, PartialDate.OnlyYear(Year.of(2019)), PartialDate.Month(YearMonth.of(2020, 2))),
                    Instant.parse("2026-01-12T08:00:00.000000001Z"),
                )
            val confirmed = ListRecord(ListValues(ListStatus.Planned, 0, null, null, null), Instant.parse("1969-12-31T23:59:59.5Z"))
            val entries =
                mapOf(
                    fixtures.entries[0].anime.id to StoredEntry(shown, confirmed, unconfirmed = true),
                    fixtures.entries[1].anime.id to StoredEntry(shown, null, unconfirmed = true),
                    fixtures.entries[2].anime.id to StoredEntry(null, confirmed, unconfirmed = true),
                    fixtures.entries[3].anime.id to StoredEntry(null, null, unconfirmed = true),
                )

            s.store.write(entries)

            assertEquals(entries, s.store.readAll().filterKeys { it in entries })
            for ((id, entry) in entries) assertEquals(StoredTitle(fixtures.anime(id), entry), s.store.read(id))
            assertEquals(
                ListEntry(fixtures.entries[0].anime, shown.values, shown.updatedAt),
                s.store.observeEntry(fixtures.entries[0].anime.id).first(),
            )
            for (status in ListStatus.entries) {
                val record = shown.copy(values = shown.values.copy(status = status))
                s.store.write(mapOf(fixtures.entries[0].anime.id to StoredEntry.settled(record)))
                assertEquals(StoredEntry.settled(record), s.store.read(fixtures.entries[0].anime.id)?.entry)
            }
        }

    @Test
    fun `an entry with nothing shown is kept and is not on the list, and one written as absent is forgotten`() =
        runTest {
            val s = seeded()
            val hidden = fixtures.entries[2]
            val gone = fixtures.entries[3]

            s.store.write(mapOf(hidden.anime.id to StoredEntry(null, null, unconfirmed = true), gone.anime.id to null))

            val shown = s.store.observeEntries().first()
            assertEquals(fixtures.entries.size - 2, shown.size)
            assertNull(s.store.observeEntry(hidden.anime.id).first())
            assertEquals(StoredTitle(hidden.anime, StoredEntry(null, null, unconfirmed = true)), s.store.read(hidden.anime.id))
            assertEquals(StoredTitle(gone.anime, entry = null), s.store.read(gone.anime.id))
            assertTrue(hidden.anime.id in s.store.readAll() && gone.anime.id !in s.store.readAll())
        }

    @Test
    fun `the list is in the order of its titles, and an entry that leaves and returns has its place`() =
        runTest {
            val s = subject()
            s.store.clear(owner)
            s.store.download(fixtures.entries.reversed().map { it.anime }, fixtures.entries.shuffled().settled(), syncedAt)
            val before = s.store.observeEntries().first()
            val edited = fixtures.entries[5]
            assertEquals(fixtures.entries.sortedBy { it.anime.id.value }, before)

            s.store.write(mapOf(edited.anime.id to StoredEntry(null, edited.record, unconfirmed = true)))
            assertEquals(before - edited, s.store.observeEntries().first())
            s.store.write(mapOf(edited.anime.id to StoredEntry.settled(edited.record)))
            assertEquals(before, s.store.observeEntries().first())
            s.store.write(mapOf(edited.anime.id to null))
            s.store.add(edited.anime, StoredEntry.settled(edited.record))
            assertEquals(before, s.store.observeEntries().first())
            assertEquals(
                before.map { it.anime.id },
                s.store
                    .readAll()
                    .keys
                    .toList(),
            )
        }

    @Test
    fun `an entry of a title the store does not know is not kept, and the entries written with it are`() =
        runTest {
            val s = seeded()
            val unknown = FixtureAnime.notOnListAiring
            val edited = fixtures.entries.first()

            s.store.write(mapOf(unknown.id to edited.watched(1), edited.anime.id to edited.watched(99)))
            s.store.restore(mapOf(unknown.id to edited.watched(1)))

            assertNull(s.store.read(unknown.id))
            assertTrue(unknown.id !in s.store.readAll())
            assertEquals(edited.watched(99), s.store.read(edited.anime.id)?.entry)
        }

    @Test
    fun `a new database holds nobody's list, and the owner and the time come back as written`() =
        runTest {
            val s = subject()
            assertEquals(StoredList(owner = null, syncedAt = null), s.store.state())
            assertEquals(emptyList<ListEntry>(), s.store.observeEntries().first())

            s.store.clear(owner)
            assertEquals(StoredList(owner, syncedAt = null), s.store.state())
            s.store.download(emptyList(), emptyMap(), syncedAt)
            assertEquals(StoredList(owner, syncedAt), s.store.state())
            s.store.restore(emptyMap())
            assertEquals(StoredList(owner, syncedAt = null), s.store.state())
        }

    @Test
    fun `what is kept of the entries made is what the list holds, and nothing after a clear`() =
        runTest {
            val s = seeded()
            val gone = fixtures.entries[4]
            val first = s.store.observeEntries().first()

            s.store.write(mapOf(gone.anime.id to null))
            assertEquals(
                first.size - 1,
                s.store
                    .observeEntries()
                    .first()
                    .size,
            )
            s.store.write(mapOf(gone.anime.id to StoredEntry.settled(gone.record)))
            val second = s.store.observeEntries().first()
            assertEquals(first, second)
            for ((old, new) in first.zip(second)) assertEquals(old.anime.id != gone.anime.id, old === new)

            s.store.clear(owner)
            s.store.download(fixtures.entries.map { it.anime }, fixtures.entries.settled(), syncedAt)
            val third = s.store.observeEntries().first()
            assertEquals(first, third)
            for ((old, new) in second.zip(third)) assertTrue(old !== new)
        }

    @Test
    fun `clear forgets every entry, the time, the owner and every title`() =
        runTest {
            val s = seeded()
            val seen = lists(s)

            s.store.clear(SignInId("b"))
            runCurrent()

            assertEquals(StoredList(SignInId("b"), syncedAt = null), s.store.state())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), s.store.readAll())
            assertEquals(emptyList<ListEntry>(), seen.last())
            for (entry in fixtures.entries) assertNull(s.store.read(entry.anime.id))
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1L), TABLES.map { s.database.rows(it) })
            s.store.clear(null)
            assertEquals(StoredList(owner = null, syncedAt = null), s.store.state())
        }

    @Test
    fun `a download keeps what it does not bring of a title, and an entry added with a title leaves a stored one alone`() =
        runTest {
            val s = seeded()
            val stored = FixtureAnime.exactToWatch
            val entry = checkNotNull(fixtures.entryFor(stored.id))
            val thin: Anime =
                stored.copy(
                    titles = stored.titles.copy(romaji = "Renamed"),
                    airingStatus = AiringStatus.Finished,
                    genres = null,
                    meanScore = null,
                    broadcast = null,
                )
            assertNotNull(stored.genres)

            s.store.add(thin, entry.watched(13))
            assertEquals(stored, s.store.read(stored.id)?.anime)

            s.store.download(listOf(thin), emptyMap(), syncedAt)
            val merged = stored.copy(titles = thin.titles, airingStatus = AiringStatus.Finished)
            assertEquals(StoredTitle(merged, entry.watched(13)), s.store.read(stored.id))
            assertEquals(
                merged,
                s.store
                    .observeEntry(stored.id)
                    .first()
                    ?.anime,
            )
        }
}
