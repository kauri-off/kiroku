package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.repository.Freshness
import io.github.kauri_off.kiroku.core.repository.ListSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration

// The repository on virtual time: its clock is the test's scheduler, moved further by hand where a test sets it.
@OptIn(ExperimentalCoroutinesApi::class)
class ListAiringTest {
    private val set = Fixtures.reference
    private val title = FixtureAnime.exactToWatch
    private val next = checkNotNull(set.airing.getValue(title.id).nextEpisode)
    private val count = (set.airing.getValue(title.id).aired as AiredCount.Exact).value

    private class SchedulerClock(
        private val scope: TestScope,
        private val start: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        // How far the clock was set from the scheduler's time.
        @Volatile
        var setBy: Duration = Duration.ZERO

        @Volatile
        var reads = 0

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = SchedulerClock(scope, start, zone)

        override fun instant(): Instant {
            reads++
            return start.plusMillis(scope.testScheduler.currentTime).plus(setBy)
        }
    }

    private class Listed(
        entries: List<ListEntry>,
    ) : ListSource {
        val entries = MutableStateFlow(entries)
        val unreadable = MutableStateFlow(false)

        override fun observeEntries(): Flow<List<ListEntry>> =
            combine(entries, unreadable) { entries, unreadable -> if (unreadable) throw DataException(DataError.StorageError) else entries }

        override fun observeEntry(id: AnimeId): Flow<ListEntry?> = entries.map { all -> all.firstOrNull { it.anime.id == id } }

        override fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> =
            entries.map { all -> all.filter { it.anime.id in ids }.associateBy { it.anime.id } }

        override fun observeSyncedAt(): Flow<Instant?> = MutableStateFlow(null)

        override suspend fun sync(): Outcome<Unit> = Outcome.Success(Unit)

        fun put(
            anime: Anime,
            status: ListStatus,
        ) = entries.update { all ->
            all.filter { it.anime.id != anime.id } + ListEntry(anime, ListValues(status, 0, null, null, null), Instant.EPOCH)
        }
    }

    // A store whose observers hear of a write a while after it, as a database's do.
    private class LateObservers(
        private val inner: AiringStore,
    ) : AiringStore by inner {
        override fun observe(): Flow<Map<AnimeId, StoredAiring>> =
            inner
                .observe()
                .withIndex()
                .onEach {
                    if (it.index >
                        0
                    ) {
                        delay(LATE)
                    }
                }.map { it.value }
    }

    private inner class Subject(
        scope: TestScope,
        zone: ZoneId,
        entries: List<ListEntry>,
        at: Instant,
        lateObservers: Boolean,
    ) {
        val clock = SchedulerClock(scope, at, zone)
        val clockSet = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val list = Listed(entries)
        val source = ScriptedAiringSource(set.schedules, clock)
        val store =
            InMemoryAiringStore(
                entries.filter { it.tracked }.associate {
                    it.anime.id to
                        StoredAiring(set.schedules[it.anime.id], at)
                },
            )
        val unexpected = mutableListOf<Throwable>()
        val airing =
            ListAiring(
                source,
                if (lateObservers) LateObservers(store) else store,
                list,
                AiringTime(clock, clockSet),
                scope.backgroundScope,
            ) {
                unexpected += it
            }

        // Each result of [id] an observer was given, with the scheduler's time it was given at.
        fun TestScope.seen(
            id: AnimeId,
            of: Flow<Map<AnimeId, Airing>> = airing.observeAiring(),
        ): Pair<MutableList<Pair<Long, Airing?>>, Job> {
            val seen = mutableListOf<Pair<Long, Airing?>>()
            val job =
                backgroundScope.launch {
                    of.map { it[id] }.distinctUntilChanged().collect { seen += testScheduler.currentTime to it }
                }
            runCurrent()
            return seen to job
        }

        fun TestScope.advanceTo(instant: Instant) {
            advanceTimeBy(Duration.between(clock.instant(), instant).toKotlinDuration())
        }
    }

    private fun TestScope.subject(
        zone: ZoneId = FixtureTime.zone,
        entries: List<ListEntry> = set.entries,
        at: Instant = set.now,
        lateObservers: Boolean = false,
    ) = Subject(this, zone, entries, at, lateObservers)

    private fun aired(airing: Airing?) = (airing?.aired as? AiredCount.Known)?.value

    private companion object {
        val LATE = 50.milliseconds
    }

    @Test
    fun `A2 every observer gets episode n+1 at the instant it airs and not before, a page's among them`() =
        runTest {
            val s = subject()
            with(s) {
                val (first, _) = seen(title.id)
                val (second, _) = seen(title.id)
                val (page, _) = seen(title.id, airing.observePageAiring(title))
                val airsAt = Duration.between(set.now, next.airsAt).toMillis()

                advanceTimeBy(Duration.ofMillis(airsAt - 1).toKotlinDuration())
                runCurrent()
                for (seen in listOf(first, second, page)) assertEquals(listOf(count), seen.map { aired(it.second) })

                advanceTimeBy(Duration.ofMillis(1).toKotlinDuration())
                runCurrent()
                for (seen in listOf(first, second, page)) {
                    assertEquals(listOf(0L to count, airsAt to count + 1), seen.map { it.first to aired(it.second) })
                }
                assertSame(first.last().second, second.last().second)
                assertSame(first.last().second, page.last().second)
                assertEquals(emptyList<Set<AnimeId>>(), source.calls.toList())
            }
        }

    @Test
    fun `A2 between two air times nothing is computed and nothing wakes, and an air time computes its own title alone`() =
        runTest {
            val s = subject()
            with(s) {
                val all = mutableListOf<Map<AnimeId, Airing>>()
                backgroundScope.launch { airing.observeAiring().collect { all += it } }
                runCurrent()
                val before = all.single()
                val wake = checkNotNull(before.values.mapNotNull { it.changesAt }.minOrNull())
                val reads = clock.reads

                advanceTimeBy(Duration.between(set.now, wake).minusMillis(1).toKotlinDuration())
                runCurrent()
                assertEquals(reads, clock.reads)
                assertEquals(1, all.size)

                advanceTimeBy(Duration.ofMillis(1).toKotlinDuration())
                runCurrent()
                assertEquals(reads + 1, clock.reads)
                assertEquals(2, all.size)
                val changed = before.filterValues { it.changesAt == wake }.keys
                assertTrue(changed.isNotEmpty())
                for ((id, result) in all.last()) {
                    if (id in changed) assertFalse("$id", result == before[id]) else assertSame("$id", before[id], result)
                }
            }
        }

    @Test
    fun `A2 an estimate rises at MAL's slot, and a date MAL names for a premiere is over with its day in Japan, west of it`() =
        slotAndPremiereIn(ZoneId.of("America/Los_Angeles"))

    @Test
    fun `A2 the same on a device east of Japan, so a new time zone moves nothing`() = slotAndPremiereIn(ZoneId.of("Pacific/Auckland"))

    private fun slotAndPremiereIn(zone: ZoneId) =
        runTest {
            val estimated = FixtureAnime.estimatedToWatch
            val dated = FixtureAnime.premiereDate
            val s = subject(zone)
            with(s) {
                val (slots, _) = seen(estimated.id)
                val (premieres, _) = seen(dated.id)
                val slot = checkNotNull(set.airing.getValue(estimated.id).changesAt)
                val day = (dated.startDate as PartialDate.Day).date
                val over = day.plusDays(1).atStartOfDay(JAPAN).toInstant()
                assertEquals(Premiere.OnDay(day), premieres.single().second?.premiere)

                advanceTo(maxOf(slot, over).plusSeconds(1))
                runCurrent()

                val rose = slots.first { aired(it.second) != aired(slots.first().second) }
                assertEquals(Duration.between(set.now, slot).toMillis(), rose.first)
                assertEquals(aired(slots.first().second)!! + 1, aired(rose.second))
                assertEquals(
                    listOf(0L to Premiere.OnDay(day), Duration.between(set.now, over).toMillis() to Premiere.Unannounced),
                    premieres.map { it.first to it.second?.premiere }.distinct(),
                )
            }
        }

    @Test
    fun `a clock set back is followed, at once when it is told and else when the wait ends`() =
        runTest {
            val s = subject(entries = listOfNotNull(set.entryFor(title.id)))
            with(s) {
                val (seen, _) = seen(title.id)
                advanceTo(next.airsAt)
                runCurrent()
                assertEquals(listOf(count, count + 1), seen.map { aired(it.second) })

                clock.setBy = Duration.ofHours(-2)
                clockSet.emit(Unit)
                runCurrent()
                assertEquals(listOf(count, count + 1, count), seen.map { aired(it.second) })

                advanceTimeBy(Duration.ofHours(2).minusMillis(1).toKotlinDuration())
                runCurrent()
                assertEquals(3, seen.size)
                advanceTimeBy(Duration.ofMillis(1).toKotlinDuration())
                runCurrent()
                assertEquals(listOf(count, count + 1, count, count + 1), seen.map { aired(it.second) })

                // Set back with nobody telling: the wait under way ends before its instant, and the results follow the clock then.
                val wait = Duration.between(clock.instant(), checkNotNull(seen.last().second?.changesAt))
                clock.setBy = clock.setBy.minus(wait).minusHours(1)
                advanceTimeBy(wait.minusMillis(1).toKotlinDuration())
                runCurrent()
                assertEquals(4, seen.size)
                advanceTimeBy(Duration.ofMillis(1).toKotlinDuration())
                runCurrent()
                assertEquals(listOf(count, count + 1, count, count + 1, count), seen.map { aired(it.second) })
                advanceTimeBy(Duration.ofHours(1).toKotlinDuration())
                runCurrent()
                assertEquals(listOf(count, count + 1, count, count + 1, count, count + 1), seen.map { aired(it.second) })
            }
        }

    @Test
    fun `a clock set forward past an air time is followed when it is told`() =
        runTest {
            val s = subject()
            with(s) {
                val (seen, _) = seen(title.id)
                clock.setBy = Duration.between(set.now, next.airsAt)
                runCurrent()
                assertEquals(1, seen.size)

                clockSet.emit(Unit)
                runCurrent()
                assertEquals(listOf(0L to count, 0L to count + 1), seen.map { it.first to aired(it.second) })
            }
        }

    @Test
    fun `a schedule that changes and a title that joins the list are given at once, with no wait`() =
        runTest {
            val s = subject()
            with(s) {
                val added = FixtureAnime.notOnListAiring
                val (seen, _) = seen(title.id)
                val (joined, _) = seen(added.id)
                assertNull(joined.single().second)
                val moved =
                    set.schedules.getValue(title.id).let { all ->
                        all.copy(episodes = all.episodes.filter { it.number <= count - 1 })
                    }

                store.write(mapOf(title.id to StoredAiring(moved, set.now)))
                list.put(added, ListStatus.Dropped)
                runCurrent()

                assertEquals(listOf(0L to count, 0L to count - 1), seen.map { it.first to aired(it.second) })
                assertEquals(airingAt(added, null, set.now), joined.last().second)
                assertEquals(0L, testScheduler.currentTime)
            }
        }

    @Test
    fun `with nobody observing nothing waits, and an observer that comes later is given the count of its moment`() =
        runTest {
            val s = subject()
            with(s) {
                val (seen, job) = seen(title.id)
                assertEquals(count, aired(seen.single().second))
                job.cancel()
                runCurrent()
                val reads = clock.reads

                advanceTo(next.airsAt.plusSeconds(1))
                runCurrent()
                assertEquals(reads + 1, clock.reads)

                assertEquals(count + 1, aired(airing.observeAiring().first()[title.id]))
            }
        }

    @Test
    fun `reading asks the source nothing, however stale the stored data`() =
        runTest {
            val s = subject()
            with(s) {
                advanceTimeBy(Freshness.airing + 1.days)
                seen(title.id)
                seen(title.id, airing.observePageAiring(title))
                seen(title.id, airing.observePageAiring(FixtureAnime.notOnListAiring))
                airing.observeAiring().first()
                airing.observeSchedules().first()
                advanceTimeBy(1.days)
                runCurrent()

                assertEquals(emptyList<Set<AnimeId>>(), source.calls.toList())
                assertEquals(Outcome.Success(Unit), airing.refreshStale())
                assertEquals(1, source.calls.size)
            }
        }

    @Test
    fun `a refresh returns once the observers have what it brought, so a read that follows holds the list and the data then`() =
        runTest {
            val s = subject(entries = emptyList(), lateObservers = true)
            with(s) {
                backgroundScope.launch { airing.observeAiring().collect {} }
                runCurrent()
                assertEquals(emptyMap<AnimeId, Airing>(), airing.observeAiring().first())

                list.entries.value = set.entries
                assertEquals(Outcome.Success(Unit), airing.refreshStale())
                val read = airing.observeAiring().first()

                for (anime in listOf(title, FixtureAnime.exactCaughtUp, FixtureAnime.premiereTimeSoon)) {
                    assertEquals(anime.titles.romaji, airingAt(anime, set.schedules[anime.id], clock.instant()), read[anime.id])
                }

                val moved = set.schedules.getValue(title.id).let { all -> all.copy(episodes = all.episodes.filter { it.number < count }) }
                source.schedules += title.id to moved
                assertEquals(Outcome.Success(Unit), airing.refresh())
                assertEquals(airingAt(title, moved, clock.instant()), airing.observeAiring().first()[title.id])
            }
        }

    @Test
    fun `A12 a title that is not tracked has an exact result on its page alone, and a tracked one the list's own`() =
        runTest {
            val s = subject()
            with(s) {
                val page = FixtureAnime.notOnListAiring
                val (ofPage, _) = seen(page.id, airing.observePageAiring(page))
                val (ofList, _) = seen(page.id)
                val (others, _) = seen(title.id, airing.observePageAiring(page))
                assertEquals(airingAt(page, null, set.now), ofPage.single().second)

                assertEquals(Outcome.Success(Unit), airing.refreshStale(page))
                runCurrent()

                assertEquals(airingAt(page, set.schedules[page.id], set.now), ofPage.last().second)
                assertTrue(ofPage.last().second?.aired is AiredCount.Exact)
                assertEquals(listOf<Airing?>(null), ofList.map { it.second })
                assertSame(airing.observeAiring().first()[title.id], others.last().second)
                assertSame(airing.observeAiring().first()[title.id], airing.observePageAiring(title).first()[title.id])
            }
        }

    // A notification reads the stored schedules, and at most the list's results.
    @Test
    fun `A12 a schedule fetched for a page alone is in nothing a notification reads, also once its title is tracked`() =
        runTest {
            val s = subject()
            with(s) {
                val page = FixtureAnime.notOnListAiring
                val (ofPage, _) = seen(page.id, airing.observePageAiring(page))
                val (ofList, _) = seen(page.id)
                assertEquals(Outcome.Success(Unit), airing.refresh(page))
                source.failure = DataError.NoConnection

                list.put(page, ListStatus.Watching)
                runCurrent()

                assertEquals(listOf(setOf(page.id), setOf(page.id)), source.calls.toList())
                assertEquals(set.schedules[page.id]?.episodes?.first { it.airsAt > set.now }, ofPage.last().second?.nextEpisode)
                val listed = checkNotNull(ofList.last().second)
                assertFalse(listed.aired is AiredCount.Exact)
                assertNull(listed.nextEpisode)
                assertEquals(Inexact.NoSchedule, listed.inexact)
                assertFalse(page.id in airing.observeSchedules().first())
                assertFalse(page.id in store.read())
            }
        }

    @Test
    fun `a schedule whose numbering the source doubts is shown as an estimate and is in nothing a notification reads`() =
        runTest {
            val s = subject()
            with(s) {
                val doubted = set.schedules.getValue(title.id).copy(doubtedOver = count + 3)
                source.schedules += title.id to doubted
                val (seen, _) = seen(title.id)
                assertEquals(Outcome.Success(Unit), airing.refresh())

                val shown = checkNotNull(seen.last().second)
                assertEquals(AiredCount.Estimated(count + 3), shown.aired)
                assertEquals(Inexact.NumberingDoubted, shown.inexact)
                assertEquals(shown, airing.observePageAiring(title).first()[title.id])
                assertEquals(doubted, store.read()[title.id]?.schedule)
                assertFalse(title.id in airing.observeSchedules().first())
                assertTrue(FixtureAnime.exactCaughtUp.id in airing.observeSchedules().first())
            }
        }

    @Test
    fun `a list that cannot be read ends every observer's flow with the error, and an observer that comes later reads again`() =
        runTest {
            val s = subject()
            with(s) {
                val ended = mutableListOf<Throwable?>()
                repeat(2) {
                    backgroundScope.launch {
                        ended += runCatching { airing.observeAiring().collect {} }.exceptionOrNull()
                    }
                }
                backgroundScope.launch { ended += runCatching { airing.observePageAiring(title).collect {} }.exceptionOrNull() }
                runCurrent()
                assertEquals(emptyList<Throwable?>(), ended)

                list.unreadable.value = true
                runCurrent()
                assertEquals(List(3) { DataError.StorageError }, ended.map { (it as? DataException)?.error })

                list.unreadable.value = false
                assertEquals(count, aired(airing.observeAiring().first()[title.id]))
                assertEquals(emptyList<Throwable>(), unexpected)
            }
        }

    @Test
    fun `a clock that does not run with the dispatcher is waited on by nothing, and moves the results when it is said to be set`() =
        runTest {
            var now = set.now
            val clock =
                object : Clock() {
                    override fun getZone() = FixtureTime.zone

                    override fun withZone(zone: ZoneId) = this

                    override fun instant() = now
                }
            val clockSet = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var waits = 0
            val time =
                AiringTime(clock, clockSet) {
                    waits++
                    delay(Long.MAX_VALUE)
                }
            val store = InMemoryAiringStore(mapOf(title.id to StoredAiring(set.schedules[title.id], set.now)))
            val airing =
                ListAiring(ScriptedAiringSource(set.schedules, clock), store, Listed(set.entries), time, backgroundScope) { throw it }
            val seen = mutableListOf<Int?>()
            backgroundScope.launch { airing.observeAiring().collect { seen += aired(it[title.id]) } }
            runCurrent()

            advanceTimeBy(365.days)
            runCurrent()
            assertEquals(listOf(count), seen)
            assertEquals(1, waits)

            now = next.airsAt
            clockSet.emit(Unit)
            runCurrent()
            assertEquals(listOf(count, count + 1), seen)
        }

    @Test
    fun `the results are the calculator's for every title of the list that can still air, and for no other`() =
        runTest {
            val s = subject()
            with(s) {
                val results = airing.observeAiring().first()
                val unfinished = set.entries.map { it.anime }.filter { it.airingStatus.effective != AiringStatus.Finished }
                assertEquals(unfinished.map { it.id }.toSet(), results.keys)
                for (anime in unfinished) {
                    val stored = set.schedules[anime.id].takeIf { set.entryFor(anime.id)?.tracked == true }
                    assertEquals(anime.titles.romaji, airingAt(anime, stored, set.now), results[anime.id])
                }
                assertEquals(emptyList<Set<AnimeId>>(), source.calls.toList())
            }
        }
}
