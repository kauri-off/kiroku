package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.demo.DemoServer
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.Freshness
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// What the airing engine must do over any store and any source; a pair of ports passes it by subclassing.
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AiringEngineContract {
    // A new, empty store.
    protected abstract fun store(scope: TestScope): AiringStore

    // What the engine asks: the scripted source itself, or a client under test in front of it.
    protected open fun source(scripted: ScriptedAiringSource): AiringSource = scripted

    private val fixtures = Fixtures.reference
    private val a = Session.SignedIn(SignInId("a"))

    private val tracked =
        with(FixtureAnime) {
            setOf(
                exactToWatch,
                exactCaughtUp,
                exactCaughtUpLater,
                exactOnBreak,
                estimatedToWatch,
                estimatedCaughtUp,
                airedUnknown,
                unknownTotal,
                longTitle,
                premiereTimeSoon,
                premiereTimeLater,
                premiereDate,
                premiereMonth,
                premiereYear,
                premiereUnannounced,
                plannedAiring,
                onHoldAiring,
            ).mapTo(mutableSetOf()) { it.id }
        }

    private class MovingClock(
        @Volatile var now: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        @Volatile
        var throwing = false

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MovingClock(now, zone)

        override fun instant(): Instant = check(!throwing) { "The engine failed" }.let { now }

        fun advance(by: Duration) {
            now += by.toJavaDuration()
        }
    }

    // A store as a database is: a query emits after every write, changed or not; it fails as told to and keeps its writes.
    private class DatabaseLike(
        private val inner: AiringStore,
    ) : AiringStore {
        @Volatile
        var unreadable = false

        @Volatile
        var failingWrites = false

        // Only a write that forgets a title fails.
        @Volatile
        var failingForgets = false

        @Volatile
        var writesToFail = 0

        val writes = CopyOnWriteArrayList<Map<AnimeId, StoredAiring?>>()

        @Volatile
        var subscriptions = 0

        private val written = MutableStateFlow(0)

        override fun observe(): Flow<Map<AnimeId, StoredAiring>> =
            flow {
                subscriptions++
                fault()
                emitAll(combine(inner.observe(), written) { stored, _ -> stored })
            }

        override suspend fun read(): Map<AnimeId, StoredAiring> {
            fault()
            return inner.read()
        }

        override suspend fun write(titles: Map<AnimeId, StoredAiring?>) {
            fault()
            if (failingWrites || (failingForgets && null in titles.values)) throw IOException("The disk is full")
            if (writesToFail > 0) {
                writesToFail--
                throw IOException("The write was aborted")
            }
            writes += titles
            inner.write(titles)
            written.update { it + 1 }
        }

        private fun fault() {
            if (unreadable) throw IOException("The store cannot be opened")
        }
    }

    // The list, which cannot be read while told so.
    private class FaultyList(
        private val inner: ListSource,
    ) : ListSource by inner {
        val unreadable = MutableStateFlow(false)

        // Runs once, when the list was next read and before the reader has it.
        @Volatile
        var afterRead: (suspend () -> Unit)? = null

        override fun observeEntries(): Flow<List<ListEntry>> =
            combine(inner.observeEntries(), unreadable) { entries, unreadable ->
                if (unreadable) throw DataException(DataError.StorageError)
                afterRead?.let {
                    afterRead = null
                    it()
                }
                entries
            }
    }

    private inner class Subject(
        scope: TestScope,
        engineScope: CoroutineScope,
        entries: List<ListEntry>,
        schedules: Map<AnimeId, AiringSchedule>,
        val clock: MovingClock,
        sourceAhead: Duration,
        val store: DatabaseLike,
    ) {
        val unexpected = CopyOnWriteArrayList<Throwable>()
        val session = MutableStateFlow<Session?>(a)
        val server = DemoServer(entries, clock, Duration.ZERO)
        val listStore = InMemoryLocalList()
        val list: ListRepository =
            ListEngine(server, listStore, session.filterNotNull(), clock, scope.backgroundScope) { unexpected += it }
        val shownList = FaultyList(list)
        val source = ScriptedAiringSource(schedules, Clock.offset(clock, sourceAhead.toJavaDuration()))
        val engine = AiringEngine(source(source), store, shownList, clock, engineScope) { unexpected += it }

        suspend fun stored(): Map<AnimeId, StoredAiring> = store.read()

        suspend fun stored(anime: Anime): StoredAiring? = stored()[anime.id]

        suspend fun schedule(anime: Anime): AiringSchedule = checkNotNull(stored(anime)?.schedule)

        suspend fun save(
            anime: Anime,
            status: ListStatus,
        ) = list.write(ListWrite.Save(anime, ListValues(status, 0, null, null, null)))
    }

    // Everything starts with the fixtures; [seeded], the airing store holds every tracked title as fetched just now.
    private suspend fun TestScope.subject(
        entries: List<ListEntry> = fixtures.entries,
        schedules: Map<AnimeId, AiringSchedule> = fixtures.schedules,
        seeded: Boolean = true,
        synced: Boolean = true,
        now: Instant = fixtures.now,
        sourceAhead: Duration = Duration.ZERO,
        scope: CoroutineScope = backgroundScope,
    ): Subject {
        val clock = MovingClock(now, fixtures.clock.zone)
        val store = store(this)
        if (seeded) {
            store.write(entries.filter { it.tracked }.associate { it.anime.id to StoredAiring(schedules[it.anime.id], now) })
        }
        val subject = Subject(this, scope, entries, schedules, clock, sourceAhead, DatabaseLike(store))
        subject.listStore.clear(a.signIn)
        val at = now.takeIf { synced }
        subject.listStore.download(entries.map { it.anime }, entries.associate { it.anime.id to StoredEntry.settled(it.record) }, at)
        runCurrent()
        return subject
    }

    private fun entriesOf(vararg anime: Anime): List<ListEntry> = anime.map { checkNotNull(fixtures.entryFor(it.id)) }

    private fun AiringSchedule.episode(number: Int): ScheduledEpisode? = episodes.singleOrNull { it.number == number }

    private fun AiringSchedule.moved(
        number: Int,
        by: Duration,
    ) = copy(episodes = episodes.map { if (it.number == number) it.copy(airsAt = it.airsAt + by.toJavaDuration()) else it })

    private fun AiringSchedule.at(
        number: Int,
        airsAt: Instant,
    ) = copy(episodes = episodes.map { if (it.number == number) it.copy(airsAt = airsAt) else it })

    private fun AiringSchedule.without(vararg numbers: Int) = copy(episodes = episodes.filter { it.number !in numbers })

    private fun TestScope.childScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    @Test
    fun `the store gives back what it was given, forgets a title given as null and tells its observers of both`() =
        runTest {
            val store = store(this)
            val seen = mutableListOf<Map<AnimeId, StoredAiring>>()
            backgroundScope.launch { store.observe().collect { seen += it } }
            runCurrent()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), seen.last())
            val known = StoredAiring(fixtures.schedules.getValue(FixtureAnime.unknownTotal.id), fixtures.now)
            val bare = StoredAiring(AiringSchedule(emptyList(), 12), fixtures.now.plusSeconds(1))
            val unknown = StoredAiring(null, fixtures.now.plusSeconds(2))
            val given = mapOf(AnimeId(1) to known, AnimeId(2) to bare, AnimeId(3) to unknown)

            store.write(given)
            runCurrent()
            assertEquals(given, store.read())
            assertEquals(given, seen.last())

            store.write(mapOf(AnimeId(1) to null, AnimeId(2) to unknown, AnimeId(4) to null))
            runCurrent()
            assertEquals(mapOf(AnimeId(2) to unknown, AnimeId(3) to unknown), store.read())
            assertEquals(store.read(), seen.last())
        }

    @Test
    fun `a start with nothing stored asks nothing by itself, and a refresh of what is stale asks for every tracked title at once`() =
        runTest {
            val s = subject(seeded = false, synced = false)
            advanceTimeBy(1.days)
            runCurrent()
            assertEquals("the list as first read", emptyList<Set<AnimeId>>(), s.source.calls.toList())
            s.engine.observeSchedules().first()
            runCurrent()
            assertEquals("a read", emptyList<Set<AnimeId>>(), s.source.calls.toList())

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())

            assertEquals(listOf(tracked), s.source.calls.toList())
            assertEquals(tracked, s.stored().keys)
            assertEquals(fixtures.schedules.filterKeys { it in tracked }, s.engine.observeSchedules().first())
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `a title the source does not know and one without episode times are stored as fetched, and not asked for again`() =
        runTest {
            val bare = AiringSchedule(emptyList(), 12)
            val s = subject(schedules = fixtures.schedules + (FixtureAnime.estimatedToWatch.id to bare), seeded = false)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())

            assertEquals(StoredAiring(null, fixtures.now), s.stored(FixtureAnime.airedUnknown))
            assertEquals(StoredAiring(bare, fixtures.now), s.stored(FixtureAnime.estimatedToWatch))
            val shown = s.engine.observeSchedules().first()
            assertFalse(FixtureAnime.airedUnknown.id in shown)
            assertEquals(bare, shown[FixtureAnime.estimatedToWatch.id])
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(1, s.source.calls.size)
        }

    @Test
    fun `a schedule whose numbering the source doubts is stored with its doubt, and is not among the schedules a notification reads`() =
        runTest {
            val anime = FixtureAnime.exactToWatch
            val plain = fixtures.schedules.getValue(anime.id)
            val doubted = plain.copy(doubtedOver = 20)
            val s = subject(schedules = fixtures.schedules + (anime.id to doubted), seeded = false)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())

            assertEquals(StoredAiring(doubted, fixtures.now), s.stored(anime))
            assertEquals(doubted, s.engine.observeStored().first()[anime.id])
            assertEquals(fixtures.schedules.filterKeys { it in tracked && it != anime.id }, s.engine.observeSchedules().first())

            s.source.schedules += anime.id to plain
            assertEquals(Outcome.Success(Unit), s.engine.refresh())
            assertEquals(plain, s.engine.observeSchedules().first()[anime.id])
        }

    @Test
    fun `A9 an episode aired on time keeps its time, a moved one has its new time, a removed one is gone, in the past too`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val before = s.schedule(anime)
            assertTrue(before.episode(14)!!.airsAt < s.clock.now && before.episode(15)!!.airsAt > s.clock.now)
            s.source.schedules += anime.id to before.without(3, 24).moved(15, 1.days)

            assertEquals(Outcome.Success(Unit), s.engine.refresh())

            val after = s.schedule(anime)
            assertEquals(before.episode(14), after.episode(14))
            assertEquals(before.episode(15)!!.airsAt.plusSeconds(86_400), after.episode(15)!!.airsAt)
            assertNull(after.episode(3))
            assertNull(after.episode(24))
            assertEquals(before.without(3, 24).moved(15, 1.days), after)
        }

    @Test
    fun `A9 an answer that is complete from an instant keeps the earlier episodes it left out and replaces the rest by number`() =
        runTest {
            val anime = FixtureAnime.exactToWatch
            val before = fixtures.schedules.getValue(anime.id)
            val s = subject(now = before.episode(14)!!.airsAt.plusSeconds(86_400))
            val early = s.clock.now.minusSeconds(3600)
            val late = s.clock.now.plusSeconds(3600)
            // At the source episode 3 is gone, 13 was put off, 14 is gone, 15 aired early, 16 moved and 17 is gone.
            s.source.schedules += anime.id to
                before
                    .without(3, 14, 17)
                    .at(13, late)
                    .at(15, early)
                    .moved(16, 1.days)
            s.source.partial = true

            assertEquals(Outcome.Success(Unit), s.engine.refresh())

            val after = s.schedule(anime)
            assertEquals((1..12).map { before.episode(it) }, (1..12).map { after.episode(it) })
            assertEquals(ScheduledEpisode(13, late), after.episode(13))
            assertNull(after.episode(14))
            assertEquals(ScheduledEpisode(15, early), after.episode(15))
            assertNull(after.episode(17))
            assertEquals(
                before
                    .without(14, 17)
                    .at(13, late)
                    .at(15, early)
                    .moved(16, 1.days),
                after,
            )
        }

    @Test
    fun `A9 a postponed title's emptied schedule empties the stored one, an episode that aired a day ago included`() =
        runTest {
            val anime = FixtureAnime.exactToWatch
            val fixture = fixtures.schedules.getValue(anime.id)
            val recent = fixture.without(*(1..13).toList().toIntArray())
            val now = fixture.episode(14)!!.airsAt.plusSeconds(86_400)
            val s = subject(schedules = fixtures.schedules + (anime.id to recent), now = now)
            assertEquals(recent, s.schedule(anime))
            s.source.schedules += anime.id to AiringSchedule(emptyList(), 24)
            s.source.partial = true

            assertEquals(Outcome.Success(Unit), s.engine.refresh())

            assertEquals(AiringSchedule(emptyList(), 24), s.schedule(anime))
        }

    @Test
    fun `A9 an episode whose time lies between the device's clock and the source's is neither lost nor stored twice`() =
        runTest {
            val anime = FixtureAnime.exactToWatch
            val fixture = fixtures.schedules.getValue(anime.id)
            for ((sourceAhead, airsIn) in listOf(5.minutes to 2.minutes, (-5).minutes to (-2).minutes)) {
                // Episode 15 airs between the two clocks: to one of them it has aired, to the other it has not.
                val shift = java.time.Duration.between(fixture.episode(15)!!.airsAt, fixtures.now + airsIn.toJavaDuration())
                val schedule = fixture.copy(episodes = fixture.episodes.map { it.copy(airsAt = it.airsAt + shift) })
                val s = subject(schedules = fixtures.schedules + (anime.id to schedule), sourceAhead = sourceAhead)
                s.source.partial = true

                assertEquals(Outcome.Success(Unit), s.engine.refresh())

                assertEquals("the source is ahead by $sourceAhead", schedule, s.schedule(anime))
            }
        }

    @Test
    fun `A9 an answer that is complete only from after the instant asked for is refused, and reported`() =
        runTest {
            val anime = FixtureAnime.exactToWatch
            val s = subject()
            val before = s.stored(anime)
            s.clock.advance(20.minutes)
            s.source.schedules += anime.id to s.schedule(anime).without(15)
            s.source.answering = { answers ->
                val late =
                    FetchedAiring(
                        s.source.schedules.getValue(anime.id),
                        s.source.asked
                            .last()
                            .plusMillis(1),
                    )
                answers + (anime.id to Outcome.Success(late))
            }

            assertEquals(Outcome.Failure(DataError.ServerError), s.engine.refresh())

            assertEquals(before, s.stored(anime))
            assertEquals(tracked - anime.id, s.stored().filterValues { it.fetchedAt == s.clock.now }.keys)
            assertEquals(1, s.unexpected.size)
        }

    @Test
    fun `the source is handed each title as the list has it, a page's as the page has it, and the earliest announceable instant`() =
        runTest {
            val s = subject(seeded = false)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(
                fixtures.entries
                    .map { it.anime }
                    .filter { it.id in tracked }
                    .toSet(),
                s.source.handed.toSet(),
            )
            assertEquals(tracked.size, s.source.handed.size)
            assertEquals(listOf(s.clock.now - announceableFor.toJavaDuration()), s.source.asked.toList())

            val page = FixtureAnime.notOnListAiring.copy(meanScore = 1.5)
            val listed = FixtureAnime.exactToWatch
            s.clock.advance(1.minutes + 999.milliseconds)
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))
            assertEquals(Outcome.Success(Unit), s.engine.refresh(listed.copy(meanScore = 1.5)))

            assertEquals(listOf(page, listed), s.source.handed.takeLast(2))
            assertEquals((s.clock.now - announceableFor.toJavaDuration()).minusMillis(999), s.source.asked.last())
        }

    @Test
    fun `an episode number given twice is stored once, at its earliest time, and the episodes in the order of their numbers`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val (one, two, three) = s.schedule(anime).episodes
            val rerun = two.copy(airsAt = two.airsAt.plusSeconds(3600))
            s.source.schedules += anime.id to AiringSchedule(listOf(three, rerun, one, two), 24)

            assertEquals(Outcome.Success(Unit), s.engine.refresh())

            assertEquals(AiringSchedule(listOf(one, two, three), 24), s.schedule(anime))
        }

    @Test
    fun `A10 of three titles refreshed with one failing, only the other two change`() =
        runTest {
            val failing = FixtureAnime.exactCaughtUp
            val others = listOf(FixtureAnime.exactToWatch, FixtureAnime.plannedAiring)
            val s = subject(entries = entriesOf(failing, *others.toTypedArray()))
            val before = s.stored()
            s.clock.advance(20.minutes)
            for (anime in others + failing) s.source.schedules += anime.id to s.schedule(anime).moved(20, 1.days).without(2)
            s.source.failing = mapOf(failing.id to DataError.ServerError)

            assertEquals(Outcome.Failure(DataError.ServerError), s.engine.refresh())

            assertEquals(listOf(before.keys), s.source.calls.toList())
            assertEquals(before[failing.id], s.stored(failing))
            for (anime in others) assertEquals(StoredAiring(s.source.schedules[anime.id], s.clock.now), s.stored(anime))

            s.source.failing = emptyMap()
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(setOf(failing.id), s.source.calls.last())
            assertEquals(StoredAiring(s.source.schedules[failing.id], s.clock.now), s.stored(failing))
        }

    @Test
    fun `A10 a title left out of the answer keeps its data, and a title that was not asked for is not stored`() =
        runTest {
            val left = FixtureAnime.exactCaughtUp
            val stranger = FixtureAnime.notOnListAiring
            val s = subject()
            val before = s.stored()
            s.clock.advance(20.minutes)
            s.source.schedules += left.id to s.schedule(left).without(2)
            s.source.answering =
                { it - left.id + (stranger.id to Outcome.Success(FetchedAiring(fixtures.schedules.getValue(stranger.id)))) }

            assertEquals(Outcome.Failure(DataError.ServerError), s.engine.refresh())

            assertEquals(1, s.unexpected.size)
            assertEquals(before[left.id], s.stored(left))
            assertEquals(tracked, s.stored().keys)
            assertNull(s.engine.observePageSchedule(stranger.id).first())
            assertEquals(tracked - left.id, s.stored().filterValues { it.fetchedAt == s.clock.now }.keys)
        }

    @Test
    fun `section 2_3 a source that fails, refuses or throws is an outcome and changes nothing stored`() =
        runTest {
            val s = subject()
            val before = s.stored()
            s.clock.advance(20.minutes)
            s.source.schedules = emptyMap()

            for (error in listOf(DataError.NoConnection, DataError.Refused, DataError.ServerError)) {
                s.source.failure = error
                assertEquals(Outcome.Failure(error), s.engine.refresh())
            }
            s.source.failure = null
            s.source.throwing = IOException("The network is gone")
            assertEquals(Outcome.Failure(DataError.NoConnection), s.engine.refresh())
            assertEquals(emptyList<Throwable>(), s.unexpected)
            val bug = IllegalStateException("The client failed")
            s.source.throwing = bug
            assertEquals(Outcome.Failure(DataError.ServerError), s.engine.refresh())

            assertEquals(listOf<Throwable>(bug), s.unexpected)
            assertEquals(before, s.stored())
            assertEquals(emptyList<Map<AnimeId, StoredAiring?>>(), s.store.writes)
            assertEquals(fixtures.schedules.filterKeys { it in tracked }, s.engine.observeSchedules().first())
        }

    @Test
    fun `A11 a title added to the list, or moved back from Dropped, is asked for at once and alone`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val back = FixtureAnime.droppedEstimated
            val schedule = AiringSchedule(fixtures.schedules.getValue(added.id).episodes, 12)
            val s = subject(schedules = fixtures.schedules + (back.id to schedule))

            s.save(added, ListStatus.Planned)
            runCurrent()
            assertEquals(listOf(setOf(added.id)), s.source.calls.toList())
            assertEquals(StoredAiring(fixtures.schedules[added.id], s.clock.now), s.stored(added))

            s.save(back, ListStatus.Watching)
            runCurrent()
            assertEquals(listOf(setOf(added.id), setOf(back.id)), s.source.calls.toList())
            assertEquals(StoredAiring(schedule, s.clock.now), s.stored(back))
        }

    @Test
    fun `N3 a refresh of named titles asks for the tracked ones among them and for no other, and stores what it is answered`() =
        runTest {
            val due = FixtureAnime.exactToWatch
            val s = subject()
            val before = s.stored()
            val moved =
                fixtures.schedules.getValue(due.id).let {
                    it.copy(
                        episodes =
                            it.episodes.map { e ->
                                e.copy(airsAt = e.airsAt.plusSeconds(60))
                            },
                    )
                }
            s.source.schedules = fixtures.schedules + (due.id to moved)
            s.clock.advance(1.minutes)
            val named =
                setOf(
                    due,
                    FixtureAnime.droppedEstimated,
                    FixtureAnime.completedUnscored,
                    FixtureAnime.notOnListAiring,
                ).map { it.id }

            assertEquals(Outcome.Success(Unit), s.engine.refresh(named.toSet()))

            assertEquals(listOf(setOf(due.id)), s.source.calls.toList())
            assertEquals(StoredAiring(moved, s.clock.now), s.stored(due))
            assertEquals(before - due.id, s.stored() - due.id)
            assertEquals(Outcome.Success(Unit), s.engine.refresh(named.toSet() - due.id))
            assertEquals(Outcome.Success(Unit), s.engine.refresh(emptySet<AnimeId>()))
            assertEquals(1, s.source.calls.size)
            s.source.failure = DataError.NoConnection
            assertEquals(Outcome.Failure(DataError.NoConnection), s.engine.refresh(setOf(due.id)))
            assertEquals(StoredAiring(moved, s.clock.now), s.stored(due))
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `A11 a new title whose fetch failed is asked for again at the next refresh, and not before`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val s = subject()
            s.source.failure = DataError.NoConnection

            s.save(added, ListStatus.Watching)
            runCurrent()
            assertEquals(listOf(setOf(added.id)), s.source.calls.toList())
            assertNull(s.stored(added))

            s.list.write(ListWrite.MarkNextWatched(added))
            s.save(FixtureAnime.exactOnBreak, ListStatus.OnHold)
            s.save(FixtureAnime.exactCaughtUp, ListStatus.Dropped)
            runCurrent()
            assertNull(s.stored(FixtureAnime.exactCaughtUp))
            assertEquals(1, s.source.calls.size)

            s.source.failure = null
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(listOf(setOf(added.id), setOf(added.id)), s.source.calls.toList())
            assertEquals(StoredAiring(fixtures.schedules[added.id], s.clock.now), s.stored(added))
        }

    @Test
    fun `a refresh that read the list before a title was added does not undo the addition`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val s = subject()
            s.shownList.afterRead = {
                s.save(added, ListStatus.Watching)
                repeat(50) { yield() }
            }

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            runCurrent()

            assertEquals(StoredAiring(fixtures.schedules[added.id], s.clock.now), s.stored(added))
            assertEquals(listOf(setOf(added.id)), s.source.calls.toList())
        }

    @Test
    fun `a sync that adds one tracked title while the others are fresh asks the source for that title alone, once`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val s = subject()
            s.clock.advance(1.minutes)
            s.server.editOnWebsite(added, ListValues(ListStatus.Watching, 2, null, null, null))

            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()

            assertEquals(listOf(setOf(added.id)), s.source.calls.toList())
            assertEquals(StoredAiring(fixtures.schedules[added.id], s.clock.now), s.stored(added))
        }

    @Test
    fun `a sync that adds one tracked title while the others are stale asks for it in the next call, once, as for each of them`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val s = subject()
            s.clock.advance(Freshness.airing + 1.minutes)
            s.server.editOnWebsite(added, ListValues(ListStatus.Watching, 2, null, null, null))

            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()

            assertTrue(added.id in s.source.calls.first())
            assertEquals(
                (tracked + added.id).sortedBy { it.value },
                s.source.calls
                    .flatten()
                    .sortedBy { it.value },
            )
            assertEquals(StoredAiring(fixtures.schedules[added.id], s.clock.now), s.stored(added))
        }

    @Test
    fun `a list change that adds a title and removes another asks for the one at once, though forgetting the other fails`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val removed = FixtureAnime.exactToWatch
            val s = subject()
            s.store.failingForgets = true
            s.clock.advance(1.minutes)
            s.server.editOnWebsite(added, ListValues(ListStatus.Watching, 2, null, null, null))
            s.server.editOnWebsite(removed, null)

            assertEquals(Outcome.Success(Unit), s.list.sync())
            advanceTimeBy(1.minutes)
            runCurrent()

            assertEquals(listOf(setOf(added.id)), s.source.calls.toList())
            assertEquals(StoredAiring(fixtures.schedules[added.id], s.clock.now), s.stored(added))
            assertEquals(tracked + added.id, s.stored().keys)

            s.store.failingForgets = false
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(tracked + added.id - removed.id, s.stored().keys)
            assertEquals(1, s.source.calls.size)
        }

    @Test
    fun `a list sync is followed by a refresh of every tracked title whose data is stale, in one call`() =
        runTest {
            val s = subject()
            s.clock.advance(Freshness.airing - 1.milliseconds)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())

            s.clock.advance(1.milliseconds)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()

            assertEquals(listOf(tracked), s.source.calls.toList())
            assertEquals(setOf(s.clock.now), s.stored().values.mapTo(mutableSetOf()) { it.fetchedAt })
        }

    @Test
    fun `reading asks the source nothing, however stale the data and however many read`() =
        runTest {
            val s = subject()
            s.clock.advance(Freshness.airing + 1.days)
            repeat(3) {
                s.engine.observeSchedules().first()
                s.engine.observePageSchedule(FixtureAnime.exactToWatch.id).first()
                s.engine.observePageSchedule(FixtureAnime.notOnListAiring.id).first()
            }
            backgroundScope.launch { s.engine.observeSchedules().collect {} }
            advanceTimeBy(1.days)
            runCurrent()
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(listOf(tracked), s.source.calls.toList())
        }

    @Test
    fun `the time of a sync that ended before the list was followed asks nothing`() =
        runTest {
            val s = subject()
            s.clock.advance(Freshness.airing + 1.minutes)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()
            assertEquals(listOf(tracked), s.source.calls.toList())

            s.clock.advance(Freshness.airing + 1.minutes)
            val late = AiringEngine(source(s.source), s.store, s.shownList, s.clock, backgroundScope) { s.unexpected += it }
            late.observeSchedules().first()
            runCurrent()

            assertEquals(1, s.source.calls.size)
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `a refresh that changes no schedule gives an observer nothing new`() =
        runTest {
            val s = subject()
            val seen = mutableListOf<Map<AnimeId, AiringSchedule>>()
            backgroundScope.launch { s.engine.observeSchedules().collect { seen += it } }
            runCurrent()
            s.clock.advance(20.minutes)

            assertEquals(Outcome.Success(Unit), s.engine.refresh())
            runCurrent()
            assertEquals(1, s.store.writes.size)
            assertEquals(1, seen.size)

            s.source.schedules += FixtureAnime.exactToWatch.id to s.schedule(FixtureAnime.exactToWatch).moved(15, 1.days)
            assertEquals(Outcome.Success(Unit), s.engine.refresh())
            runCurrent()
            assertEquals(2, seen.size)
            assertEquals(s.source.schedules[FixtureAnime.exactToWatch.id], seen.last()[FixtureAnime.exactToWatch.id])
        }

    @Test
    fun `one call to the source at a time, and a title that becomes tracked meanwhile is asked for next, alone`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val s = subject()
            s.source.latency = 1.seconds
            s.clock.advance(20.minutes)
            val refresh = async { s.engine.refresh() }
            runCurrent()
            assertEquals(listOf(tracked), s.source.calls.toList())

            s.save(added, ListStatus.Watching)
            runCurrent()
            assertEquals(1, s.source.calls.size)
            assertNull(s.stored(added))

            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(Outcome.Success(Unit), refresh.await())
            assertEquals(listOf(tracked, setOf(added.id)), s.source.calls.toList())
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(fixtures.schedules[added.id], s.schedule(added))
            assertEquals(1, s.source.mostAtOnce)
        }

    @Test
    fun `refreshes asked while one runs share its answers, so no title is asked for twice at once`() =
        runTest {
            val s = subject()
            s.source.latency = 1.seconds
            s.clock.advance(20.minutes)

            val first = async { s.engine.refresh() }
            runCurrent()
            val others =
                listOf(
                    async { s.engine.refresh() },
                    async { s.engine.refreshStale() },
                    async { s.engine.refresh(FixtureAnime.exactToWatch) },
                )
            assertEquals(Outcome.Success(Unit), s.list.sync())
            advanceTimeBy(5.seconds)
            runCurrent()

            for (refresh in others + first) assertEquals(Outcome.Success(Unit), refresh.await())
            assertEquals(listOf(tracked), s.source.calls.toList())
        }

    @Test
    fun `A13 a title MAL has marked finished keeps its data while its last episode can be announced, and is not asked for`() =
        runTest {
            val anime = FixtureAnime.exactCaughtUp
            val last =
                fixtures.schedules
                    .getValue(anime.id)
                    .episodes
                    .maxOf { it.airsAt }
            val s = subject(now = last.plusSeconds(3600))
            val before = checkNotNull(s.stored(anime))
            s.server.retitle(anime.copy(airingStatus = AiringStatus.Finished))

            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()
            assertEquals(before, s.stored(anime))

            s.clock.now = last + announceableFor.toJavaDuration() - java.time.Duration.ofMillis(1)
            assertEquals(Outcome.Success(Unit), s.engine.refresh())
            assertEquals(before, s.stored(anime))
            assertEquals(before.schedule, s.engine.observeSchedules().first()[anime.id])
            assertEquals(listOf(tracked - anime.id), s.source.calls.toList())

            s.clock.now = last + announceableFor.toJavaDuration()
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertNull(s.stored(anime))
        }

    @Test
    fun `A13 a finished title the user completes or drops is forgotten at once, and one with no episode time is not kept`() =
        runTest {
            val completed = FixtureAnime.exactCaughtUp
            val dropped = FixtureAnime.exactToWatch
            val bare = FixtureAnime.estimatedToWatch
            val schedules = fixtures.schedules + (bare.id to AiringSchedule(emptyList(), 12))
            val s = subject(schedules = schedules)
            for (anime in listOf(completed, dropped, bare)) s.server.retitle(anime.copy(airingStatus = AiringStatus.Finished))
            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()
            assertEquals(tracked - bare.id, s.stored().keys)

            s.save(completed, ListStatus.Completed)
            s.save(dropped, ListStatus.Dropped)
            runCurrent()

            assertEquals(tracked - bare.id - completed.id - dropped.id, s.stored().keys)
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())
        }

    @Test
    fun `a title that leaves the tracked set unfinished is forgotten at once`() =
        runTest {
            val s = subject()

            s.save(FixtureAnime.exactToWatch, ListStatus.Completed)
            s.save(FixtureAnime.exactCaughtUp, ListStatus.Dropped)
            s.list.write(ListWrite.Remove(FixtureAnime.plannedAiring))
            s.save(FixtureAnime.exactOnBreak, ListStatus.OnHold)
            runCurrent()

            val left = setOf(FixtureAnime.exactToWatch.id, FixtureAnime.exactCaughtUp.id, FixtureAnime.plannedAiring.id)
            assertEquals(tracked - left, s.stored().keys)
            assertEquals(fixtures.schedules.filterKeys { it in tracked - left }, s.engine.observeSchedules().first())
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())
        }

    @Test
    fun `S9 a sign-out forgets every title, and nothing is asked for signed out`() =
        runTest {
            val s = subject()
            assertEquals(tracked, s.stored().keys)

            s.session.value = Session.SignedOut
            runCurrent()

            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.stored())
            s.clock.advance(1.days)
            assertEquals(Outcome.Success(Unit), s.engine.refresh())
            assertEquals(emptyMap<AnimeId, AiringSchedule>(), s.engine.observeSchedules().first())
            runCurrent()
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())
            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.stored())
        }

    @Test
    fun `S9 an answer that arrives after the sign-out is not stored`() =
        runTest {
            val s = subject()
            s.source.latency = 1.seconds
            s.clock.advance(20.minutes)
            val refresh = async { s.engine.refresh() }
            runCurrent()
            assertEquals(1, s.source.calls.size)

            s.session.value = Session.SignedOut
            runCurrent()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.stored())
            advanceTimeBy(1.seconds)
            runCurrent()

            refresh.await()
            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.stored())
        }

    @Test
    fun `S9 a sign-out whose forgetting fails stores no answer that arrives after it, and forgets at the next use`() =
        runTest {
            val s = subject()
            val before = s.stored()
            s.source.latency = 1.seconds
            s.clock.advance(20.minutes)
            s.source.schedules += FixtureAnime.exactToWatch.id to s.schedule(FixtureAnime.exactToWatch).without(15)
            val refresh = async { s.engine.refresh() }
            runCurrent()
            assertEquals(1, s.source.calls.size)
            s.store.failingForgets = true

            s.session.value = Session.SignedOut
            advanceTimeBy(1.minutes)
            runCurrent()

            refresh.await()
            assertEquals(emptyList<Map<AnimeId, StoredAiring?>>(), s.store.writes)
            assertEquals(before, s.stored())
            assertEquals(1, s.unexpected.size)

            s.store.failingForgets = false
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.stored())
            assertEquals(1, s.source.calls.size)
        }

    @Test
    fun `S9 a forgetting that fails is tried again in a row, with no use of the engine between`() =
        runTest {
            val s = subject()
            s.store.writesToFail = 1

            s.session.value = Session.SignedOut
            runCurrent()
            assertEquals(tracked, s.stored().keys)
            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(emptyMap<AnimeId, StoredAiring>(), s.stored())
            assertEquals(1, s.unexpected.size)
        }

    @Test
    fun `S6 the refresh goes on while the session is expired`() =
        runTest {
            val s = subject()
            s.session.value = Session.Expired(a.signIn)
            runCurrent()
            assertEquals(tracked, s.stored().keys)
            s.clock.advance(20.minutes)
            s.source.schedules += FixtureAnime.exactToWatch.id to s.schedule(FixtureAnime.exactToWatch).moved(15, 1.days)

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            runCurrent()

            assertEquals(listOf(tracked), s.source.calls.toList())
            assertEquals(StoredAiring(s.source.schedules[FixtureAnime.exactToWatch.id], s.clock.now), s.stored(FixtureAnime.exactToWatch))
        }

    @Test
    fun `A12 the airing fetched for a page reaches no store, and is observed for that title alone`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val offList = FixtureAnime.droppedEstimated
            val schedule = AiringSchedule(fixtures.schedules.getValue(page.id).episodes, 12)
            val s = subject(schedules = fixtures.schedules + (offList.id to schedule))
            assertNull(s.engine.observePageSchedule(page.id).first())

            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))
            assertEquals(Outcome.Success(Unit), s.engine.refresh(offList))
            runCurrent()

            assertEquals(listOf(setOf(page.id), setOf(offList.id)), s.source.calls.toList())
            assertEquals(fixtures.schedules[page.id], s.engine.observePageSchedule(page.id).first())
            assertEquals(schedule, s.engine.observePageSchedule(offList.id).first())
            assertEquals(emptyList<Map<AnimeId, StoredAiring?>>(), s.store.writes)
            assertEquals(tracked, s.stored().keys)
            assertEquals(fixtures.schedules.filterKeys { it in tracked }, s.engine.observeSchedules().first())
        }

    @Test
    fun `A12 a finished title's page asks the source nothing, and a tracked title's page refreshes what is stored`() =
        runTest {
            val s = subject()
            assertEquals(Outcome.Success(Unit), s.engine.refresh(FixtureAnime.prequel))
            assertEquals(Outcome.Success(Unit), s.engine.refresh(FixtureAnime.relatedSummary))
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())

            val anime = FixtureAnime.exactToWatch
            s.clock.advance(1.minutes)
            s.source.schedules += anime.id to s.schedule(anime).moved(15, 1.days)
            assertEquals(Outcome.Success(Unit), s.engine.refresh(anime))

            assertEquals(listOf(setOf(anime.id)), s.source.calls.toList())
            assertEquals(StoredAiring(s.source.schedules[anime.id], s.clock.now), s.stored(anime))
            assertEquals(s.source.schedules[anime.id], s.engine.observePageSchedule(anime.id).first())
        }

    @Test
    fun `A12 a page that is shown asks for its title only when what is held of it is stale, and a pull whatever its age`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val listed = FixtureAnime.exactToWatch
            val s = subject()

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(listed))
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(FixtureAnime.prequel))
            assertEquals(listOf(setOf(page.id)), s.source.calls.toList())

            s.clock.advance(Freshness.airing - 1.milliseconds)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(listed))
            assertEquals(1, s.source.calls.size)
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))
            assertEquals(listOf(setOf(page.id), setOf(page.id)), s.source.calls.toList())

            s.clock.advance(1.milliseconds)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(listed))
            assertEquals(listOf(setOf(page.id), setOf(page.id), setOf(listed.id)), s.source.calls.toList())
            assertEquals(s.clock.now, s.stored(listed)?.fetchedAt)

            s.clock.advance(Freshness.airing)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))
            assertEquals(4, s.source.calls.size)
            assertEquals(tracked, s.stored().keys)
        }

    @Test
    fun `A12 a page whose title the source does not know is not asked for again while that answer is fresh, and a failed one is`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val s = subject()
            s.source.failure = DataError.NoConnection
            assertEquals(Outcome.Failure(DataError.NoConnection), s.engine.refreshStale(page))
            s.source.failure = null
            s.source.schedules -= page.id

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))

            assertEquals(listOf(setOf(page.id), setOf(page.id)), s.source.calls.toList())
            assertNull(s.engine.observePageSchedule(page.id).first())
        }

    @Test
    fun `A12 a page keeps what was fetched for it when its title becomes tracked and the fetch that follows fails`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val s = subject()
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))
            s.source.failure = DataError.NoConnection

            s.save(page, ListStatus.Watching)
            runCurrent()

            assertEquals(2, s.source.calls.size)
            assertNull(s.stored(page))
            assertEquals(fixtures.schedules[page.id], s.engine.observePageSchedule(page.id).first())
            assertFalse(page.id in s.engine.observeSchedules().first())

            s.source.failure = null
            s.store.failingWrites = true
            assertEquals(Outcome.Failure(DataError.StorageError), s.engine.refresh(page))
            assertEquals(fixtures.schedules[page.id], s.engine.observePageSchedule(page.id).first())
            s.store.failingWrites = false
            s.source.schedules += page.id to fixtures.schedules.getValue(page.id).moved(8, 1.days)
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(s.source.schedules[page.id], s.engine.observePageSchedule(page.id).first())
            assertEquals(s.source.schedules[page.id], s.engine.observeSchedules().first()[page.id])
            assertEquals(1, s.unexpected.size)
        }

    @Test
    fun `A12 what a page fetched goes once its title is stored, so a title that leaves the list again shows nothing`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val s = subject()
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))

            s.save(page, ListStatus.Watching)
            runCurrent()
            assertEquals(fixtures.schedules[page.id], s.schedule(page))
            s.list.write(ListWrite.Remove(page))
            runCurrent()

            assertNull(s.stored(page))
            assertNull(s.engine.observePageSchedule(page.id).first())
        }

    @Test
    fun `A12 a page that asked nothing leaves no mark, so a later answer for the list that comes too late is dropped`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val s = subject()
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale(page))
            s.source.schedules += page.id to fixtures.schedules.getValue(page.id).moved(8, 1.days)
            s.source.latency = 1.seconds

            s.save(page, ListStatus.Watching)
            runCurrent()
            s.list.write(ListWrite.Remove(page))
            advanceTimeBy(2.seconds)
            runCurrent()

            assertEquals(listOf(setOf(page.id), setOf(page.id)), s.source.calls.toList())
            assertNull(s.stored(page))
            assertEquals(fixtures.schedules[page.id], s.engine.observePageSchedule(page.id).first())
        }

    @Test
    fun `A12 what a page fetched goes when the source no longer knows the title`() =
        runTest {
            val page = FixtureAnime.notOnListAiring
            val s = subject()
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))
            assertEquals(fixtures.schedules[page.id], s.engine.observePageSchedule(page.id).first())

            s.source.schedules -= page.id
            assertEquals(Outcome.Success(Unit), s.engine.refresh(page))

            assertNull(s.engine.observePageSchedule(page.id).first())
        }

    @Test
    fun `a store that fails a write fails that refresh alone, and is reported once in a row`() =
        runTest {
            val s = subject()
            val before = s.stored()
            s.clock.advance(20.minutes)
            s.store.failingWrites = true

            assertEquals(Outcome.Failure(DataError.StorageError), s.engine.refresh())
            assertEquals(Outcome.Failure(DataError.StorageError), s.engine.refresh())
            assertEquals(before, s.stored())
            assertEquals(1, s.unexpected.size)

            s.store.failingWrites = false
            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(setOf(s.clock.now), s.stored().values.mapTo(mutableSetOf()) { it.fetchedAt })
            assertEquals(1, s.unexpected.size)
        }

    @Test
    fun `a store that cannot be read ends an observer's flow as a storage error after a few tries, and a new one reads again`() =
        runTest {
            val s = subject()
            s.store.unreadable = true
            val subscriptions = s.store.subscriptions
            var error: DataError? = null
            val observer =
                launch {
                    try {
                        s.engine.observeSchedules().collect { }
                    } catch (e: DataException) {
                        error = e.error
                    }
                }
            advanceTimeBy(1.minutes)
            runCurrent()

            assertTrue(observer.isCompleted)
            assertEquals(DataError.StorageError, error)
            assertEquals(subscriptions + 3, s.store.subscriptions)
            assertEquals(Outcome.Failure(DataError.StorageError), s.engine.refresh())
            assertEquals(1, s.unexpected.size)
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())

            s.store.unreadable = false
            assertEquals(fixtures.schedules.filterKeys { it in tracked }, s.engine.observeSchedules().first())
        }

    @Test
    fun `while the list cannot be read nothing is forgotten or asked for, and it is followed again at the next use`() =
        runTest {
            val added = FixtureAnime.notOnListAiring
            val s = subject()
            s.shownList.unreadable.value = true
            runCurrent()
            s.clock.advance(20.minutes)

            assertEquals(Outcome.Failure(DataError.StorageError), s.engine.refresh())
            assertEquals(tracked, s.stored().keys)
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())

            s.shownList.unreadable.value = false
            s.save(FixtureAnime.exactToWatch, ListStatus.Dropped)
            runCurrent()
            assertEquals(tracked, s.stored().keys)
            assertEquals(emptyList<Set<AnimeId>>(), s.source.calls.toList())

            assertEquals(Outcome.Success(Unit), s.engine.refreshStale())
            assertEquals(tracked - FixtureAnime.exactToWatch.id, s.stored().keys)
            assertEquals(listOf(tracked - FixtureAnime.exactToWatch.id), s.source.calls.toList())

            s.save(added, ListStatus.Watching)
            runCurrent()
            assertEquals(setOf(added.id), s.source.calls.last())
            assertEquals(2, s.source.calls.size)
            assertEquals(fixtures.schedules[added.id], s.schedule(added))
        }

    @Test
    fun `the engine's own failure fails the one call and is reported`() =
        runTest {
            val s = subject()
            val before = s.stored()
            s.clock.advance(20.minutes)
            s.clock.throwing = true

            assertEquals(Outcome.Failure(DataError.ServerError), s.engine.refresh())
            assertEquals(1, s.unexpected.size)

            s.clock.throwing = false
            assertEquals(before, s.stored())
            assertEquals(Outcome.Success(Unit), s.engine.refresh())
            assertEquals(setOf(s.clock.now), s.stored().values.mapTo(mutableSetOf()) { it.fetchedAt })
        }

    // The scope's end reaches its children one at a time; the engine's may run its end before a refresh of the same scope hears of it.
    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `a refresh in the engine's own scope ends cancelled with it, also when the engine has stopped first`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            s.source.latency = 1.seconds
            s.clock.advance(20.minutes)
            lateinit var refresh: Deferred<Outcome<Unit>>
            var raced = false
            process.launch { awaitCancellation() }.invokeOnCompletion(onCancelling = true) {
                testScheduler.runCurrent()
                raced = refresh.isCompleted
            }
            refresh = process.async { s.engine.refresh() }
            runCurrent()

            process.cancel()
            runCurrent()

            assertTrue("the refresh ended before the scope's end reached it", raced)
            assertTrue(refresh.isCancelled)
            assertTrue("${refresh.getCompletionExceptionOrNull()}", refresh.getCompletionExceptionOrNull() is CancellationException)
            assertSame(null, s.unexpected.firstOrNull())
        }

    @Test
    fun `after its scope has ended the engine refuses a refresh, and one that was waiting ends with it`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            s.source.latency = 1.seconds
            s.clock.advance(20.minutes)
            var waiting: Throwable? = null
            val refresh =
                launch {
                    try {
                        s.engine.refresh()
                    } catch (e: IllegalStateException) {
                        waiting = e
                    }
                }
            runCurrent()

            process.cancel()
            runCurrent()

            assertTrue(refresh.isCompleted)
            assertTrue("$waiting", waiting is IllegalStateException && waiting !is CancellationException)
            val refused = runCatching { s.engine.refresh() }.exceptionOrNull()
            assertTrue(refused is IllegalStateException)
            assertSame(null, s.unexpected.firstOrNull())
        }
}
