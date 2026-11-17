package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.notify.Announcement
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationAlarm
import io.github.kauri_off.kiroku.core.notify.InMemoryShownNotifications
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import io.github.kauri_off.kiroku.core.user.UserListState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

@OptIn(ExperimentalCoroutinesApi::class)
class DemoTest {
    private class DeviceClock(
        var instant: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        override fun instant(): Instant = instant

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = DeviceClock(instant, zone)
    }

    private fun TestScope.demo() = Demo(Fixtures.reference, backgroundScope)

    private fun Demo.airedAt(id: AnimeId): Int {
        val now = clock.instant()
        return fixtures.schedules
            .getValue(id)
            .episodes
            .filter { !it.airsAt.isAfter(now) }
            .maxOf { it.number }
    }

    private fun TestScope.failures(demo: Demo): List<WriteFailure> =
        mutableListOf<WriteFailure>().also { seen ->
            backgroundScope.launch { demo.list.observeWriteFailures().collect { seen += it } }
            runCurrent()
        }

    private fun TestScope.settle() {
        advanceTimeBy(1.minutes)
        runCurrent()
    }

    @Test
    fun `the demo starts on the device's week, with the weekday and time of week of the fixtures`() =
        runTest {
            val device = DeviceClock(FixtureTime.reference.plus(Duration.ofDays(7 * 43 + 3)), ZoneId.of("Europe/Berlin"))
            val demo = Demo.startingAt(device, backgroundScope)
            val shifted = Duration.between(FixtureTime.reference, demo.clock.instant())
            assertEquals(Duration.ofDays(7 * 43), shifted)
            assertEquals(device.zone, demo.clock.zone)
            assertEquals(
                Fixtures.reference.entries
                    .first()
                    .updatedAt
                    .plus(shifted),
                demo.list
                    .observeEntries()
                    .first()
                    .first()
                    .updatedAt,
            )
        }

    @Test
    fun `the schedules line up with the running demo clock`() =
        runTest {
            val device = DeviceClock(FixtureTime.reference.plus(Duration.ofDays(7 * 43 + 3)), FixtureTime.zone)
            val demo = Demo.startingAt(device, backgroundScope)
            val id = FixtureAnime.exactToWatch.id
            val schedules = demo.airing.observeSchedules().first()
            assertEquals(demo.fixtures.schedules.filterKeys { id -> demo.fixtures.entryFor(id)?.tracked == true }, schedules)
            val expected =
                (
                    Fixtures.reference.airing
                        .getValue(id)
                        .aired as AiredCount.Known
                ).value
            assertEquals(expected, demo.airedAt(id))

            device.instant = device.instant.plus(Duration.ofDays(7))

            assertEquals(expected + 1, demo.airedAt(id))
        }

    @Test
    fun `offline the list stays, a sync fails and only visited title pages have details`() =
        runTest {
            val demo = demo()
            val visited = FixtureAnime.exactToWatch.id
            assertEquals(Outcome.Success(Unit), demo.anime.refresh(visited))
            demo.server.offline.value = true

            assertEquals(Outcome.Failure(DataError.NoConnection), demo.list.sync())
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            assertNotNull(demo.anime.observeDetails(visited).first())
            val unvisited = FixtureAnime.notOnListAiring.id
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.anime.refresh(unvisited))
            assertNull(demo.anime.observeDetails(unvisited).first())
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.search.search("a", 0, false))
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.airing.refresh())
        }

    @Test
    fun `failing loads give an error with no content and keep cached content`() =
        runTest {
            val demo = demo()
            demo.server.failingLoads.value = true
            val id = FixtureAnime.exactToWatch.id
            assertEquals(Outcome.Failure(DataError.ServerError), demo.anime.refresh(id))
            assertNull(demo.anime.observeDetails(id).first())
            assertEquals(Outcome.Failure(DataError.ServerError), demo.list.sync())
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())

            demo.server.failingLoads.value = false
            assertEquals(Outcome.Success(Unit), demo.anime.refresh(id))
            assertEquals(Fixtures.reference.details.getValue(id), demo.anime.observeDetails(id).first())
        }

    @Test
    fun `refused writes fail as refused while reads still work`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            demo.server.refusingWrites.value = true
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(listOf(DataError.Refused), failures.map { it.error })
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            assertEquals(Outcome.Success(Unit), demo.list.sync())
            assertEquals(Outcome.Success(Unit), demo.anime.refresh(FixtureAnime.exactToWatch.id))
        }

    @Test
    fun `a store that cannot be read ends the list with a storage error, and its faults are not unexpected`() =
        runTest {
            val demo = demo()
            val ended = async { runCatching { demo.list.observeEntries().collect {} }.exceptionOrNull() }
            runCurrent()
            demo.store.unreadable.value = true
            settle()
            assertEquals(DataError.StorageError, (ended.await() as DataException).error)
            assertEquals(Outcome.Failure(DataError.StorageError), demo.list.sync())

            demo.store.unreadable.value = false
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
        }

    @Test
    fun `a store whose writes fail reports the edit as failed and keeps the list readable`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            demo.store.failingWrites.value = true
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(listOf(DataError.StorageError), failures.map { it.error })
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
        }

    @Test
    fun `a latency set while the demo runs applies to the next answer`() =
        runTest {
            val demo = demo()
            demo.server.latency = 3.seconds
            val began = testScheduler.currentTime
            demo.anime.refresh(FixtureAnime.exactToWatch.id)
            assertEquals(3.seconds.inWholeMilliseconds, testScheduler.currentTime - began)
        }

    @Test
    fun `reset brings back the list of the fixtures, signed in, with every failure off`() =
        runTest {
            val demo = demo()
            val id = FixtureAnime.exactToWatch.id
            demo.anime.refresh(id)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            demo.list.write(ListWrite.Remove(FixtureAnime.plannedFinished))
            settle()
            demo.server.offline.value = true
            demo.server.sessionExpired.value = true
            demo.server.refusingWrites.value = true
            demo.server.latency = 3.seconds
            demo.store.failingWrites.value = true
            demo.session.signOut()

            assertEquals(Outcome.Success(Unit), demo.reset())

            assertEquals(
                Fixtures.reference.entries.toSet(),
                demo.list
                    .observeEntries()
                    .first()
                    .toSet(),
            )
            assertEquals(Fixtures.reference.entries.toSet(), demo.server.entries().toSet())
            val session = demo.session.observeSession().first()
            assertTrue(session is Session.SignedIn && session != Fixtures.reference.session)
            assertNull(demo.anime.observeDetails(id).first())
            assertEquals(kotlin.time.Duration.ZERO, demo.server.latency)
            val failures = failures(demo)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `reset brings back the airing data the demo started with, a schedule AniList moved and a title it forgot included`() =
        runTest {
            val demo = demo()
            val started = demo.airing.observeSchedules().first()
            val id = FixtureAnime.exactToWatch.id
            val moved =
                started.getValue(id).let { schedule ->
                    schedule.copy(episodes = schedule.episodes.map { it.copy(airsAt = it.airsAt.plus(Duration.ofDays(7))) })
                }
            demo.airingSource.schedules = demo.fixtures.schedules + (id to moved)
            assertEquals(Outcome.Success(Unit), demo.airing.refresh())
            demo.list.write(ListWrite.Remove(FixtureAnime.exactCaughtUp))
            settle()
            assertEquals(started - FixtureAnime.exactCaughtUp.id + (id to moved), demo.airing.observeSchedules().first())

            val asked = demo.airingSource.requests.size
            assertEquals(Outcome.Success(Unit), demo.reset())
            settle()
            assertEquals(started, demo.airing.observeSchedules().first())
            assertEquals(emptyList<Set<AnimeId>>(), demo.airingSource.requests.drop(asked))
        }

    @Test
    fun `an Undo from before a sign-out is refused once the demo is signed in again`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            val anime = FixtureAnime.exactToWatch
            val change = checkNotNull(demo.list.write(ListWrite.MarkNextWatched(anime)))
            settle()
            demo.session.signOut()
            assertEquals(Outcome.Success(Unit), demo.reset())
            val sent = demo.server.requests

            assertNull(demo.list.write(change.undo!!))
            settle()

            assertEquals(listOf(DataError.ListEnded), failures.map { it.error })
            assertEquals(sent, demo.server.requests)
            assertEquals(Fixtures.reference.entryFor(anime.id), demo.list.observeEntry(anime.id).first())
        }

    @Test
    fun `waiting for saves lets an edit on its way be answered under the failures it was made in`() =
        runTest {
            val demo = Demo(Fixtures.reference, backgroundScope, 3.seconds)
            val failures = failures(demo)
            demo.server.offline.value = true
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()
            assertTrue(demo.saving)

            val settled = async { demo.awaitSaves() }
            advanceTimeBy(2.seconds)
            assertFalse(settled.isCompleted)
            settle()
            assertTrue(settled.await())
            assertFalse(demo.saving)
            assertEquals(listOf(DataError.NoConnection), failures.map { it.error })
        }

    @Test
    fun `waiting for saves gives up on an edit a failing store left unconfirmed, and reset still brings the list back`() =
        runTest {
            val demo = Demo(Fixtures.reference, backgroundScope, 3.seconds)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()
            demo.store.failingWrites.value = true
            settle()
            demo.store.failingWrites.value = false
            assertTrue(demo.saving)

            assertFalse(demo.awaitSaves())
            assertEquals(Outcome.Success(Unit), demo.reset())
            assertFalse(demo.saving)
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
        }

    @Test
    fun `a title MAL does not know is not found`() =
        runTest {
            val demo = demo()
            assertEquals(Outcome.Failure(DataError.NotFound), demo.anime.refresh(AnimeId(1)))
            assertNull(demo.anime.observeAnime(AnimeId(1)).first())
        }

    @Test
    fun `an expired session is shown, keeps the list and refuses writes`() =
        runTest {
            val demo = demo()
            demo.server.sessionExpired.value = true
            assertEquals(Session.Expired(Fixtures.reference.session.signIn), demo.session.observeSession().first())
            val failures = failures(demo)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            assertEquals(Outcome.Failure(DataError.SessionExpired), demo.list.sync())
            assertEquals(listOf(DataError.SessionExpired), failures.map { it.error })
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(0, demo.server.listDownloads)
            assertEquals(Outcome.Success(Unit), demo.anime.refresh(FixtureAnime.exactToWatch.id))
        }

    @Test
    fun `signing out leaves no list and no profile`() =
        runTest {
            val demo = demo()
            assertEquals(Fixtures.reference.session, demo.session.observeSession().first())
            assertEquals(Fixtures.reference.user, demo.user.observeOwnProfile().first())
            demo.session.signOut()
            assertEquals(Session.SignedOut, demo.session.observeSession().first())
            assertEquals(emptyList<Any>(), demo.list.observeEntries().first())
            assertNull(demo.user.observeOwnProfile().first())
        }

    @Test
    fun `the public user's list of several thousand entries is read in parts, once, and held`() =
        runTest {
            val demo = demo()
            val fixtures = Fixtures.reference
            demo.server.latency = 1.seconds
            val seen = mutableListOf<UserListState>()
            backgroundScope.launch { demo.user.observeList(fixtures.otherUserName.uppercase()).collect { seen += it } }
            settle()
            val list = fixtures.otherUserList
            assertTrue(list.size > 4_000)
            assertEquals(UserListState.Whole(list, null), seen.last())
            val parts = (list.size + DemoUserLists.PART_SIZE - 1) / DemoUserLists.PART_SIZE
            assertEquals(parts, demo.userLists.requests.size)
            val steps = seen.filterIsInstance<UserListState.Reading>()
            assertEquals(List(parts) { it * DemoUserLists.PART_SIZE }, steps.map { it.entries.size })
            assertTrue(steps.all { it.total == null && it.user == null })

            assertEquals(seen.last(), demo.user.observeList(fixtures.otherUserName).first())
            assertEquals(parts, demo.userLists.requests.size)
            assertTrue(demo.user.sameUser(fixtures.otherUserName, fixtures.otherUserName.uppercase()))
            assertFalse(demo.user.sameUser(fixtures.otherUserName, fixtures.privateUserName))
        }

    @Test
    fun `the demo has no other user, and offline nobody's list is read`() =
        runTest {
            val demo = demo()
            val fixtures = Fixtures.reference
            for (name in listOf("nobody_here", fixtures.user.name)) {
                assertEquals(
                    name,
                    UserListState.None(DataError.UnknownUser),
                    demo.user.observeList(name).first { it !is UserListState.Reading },
                )
            }
            demo.server.offline.value = true
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.user.refreshList(fixtures.otherUserName))
            assertEquals(
                UserListState.None(DataError.NoConnection),
                demo.user.observeList(fixtures.otherUserName).first { it !is UserListState.Reading },
            )
        }

    @Test
    fun `signing out and a reset each forget other users' lists`() =
        runTest {
            val demo = demo()
            val name = Fixtures.reference.otherUserName
            for (forget in listOf<suspend () -> Unit>({ demo.session.signOut() }, { demo.reset() })) {
                val before = demo.userLists.requests.size
                assertEquals(Outcome.Success(Unit), demo.user.refreshList(name))
                assertTrue(demo.user.observeList(name).first() is UserListState.Whole)
                val read = demo.userLists.requests.size
                assertTrue(read > before)
                forget()
                assertEquals(read, demo.userLists.requests.size)
                assertTrue(demo.user.observeList(name).first { it !is UserListState.Reading } is UserListState.Whole)
                assertEquals(2 * read - before, demo.userLists.requests.size)
            }
        }

    @Test
    fun `search finds titles by any of their names and pages through them`() =
        runTest {
            val demo = demo()
            val found = (demo.search.search("kagetosho", 0, false) as Outcome.Success).value
            assertEquals(listOf(FixtureAnime.exactToWatch), found.items)
            assertNull(found.nextOffset)

            val first = (demo.search.browse(BrowseList.TopRated, 0, false) as Outcome.Success).value
            val second =
                (demo.search.browse(BrowseList.TopRated, checkNotNull(first.nextOffset), false) as Outcome.Success)
                    .value
            val ranks = (first.items + second.items).map { checkNotNull(it.item.rank) }
            assertEquals(ranks.sorted(), ranks)
            assertEquals(ranks, (first.items + second.items).map { it.position })
        }

    @Test
    fun `a query shorter than three characters or a negative offset is refused as invalid`() =
        runTest {
            val demo = demo()
            for (query in listOf("", "   ", "ka", " ka ")) {
                assertEquals(Outcome.Failure(DataError.InvalidRequest), demo.search.search(query, 0, false))
            }
            assertEquals(Outcome.Failure(DataError.InvalidRequest), demo.search.search("kagetosho", -1, false))
            assertEquals(Outcome.Failure(DataError.InvalidRequest), demo.search.browse(BrowseList.TopRated, -1, false))
        }

    @Test
    fun `refused requests fail as refused`() =
        runTest {
            val demo = demo()
            demo.server.refusing.value = true
            assertEquals(Outcome.Failure(DataError.Refused), demo.search.search("kagetosho", 0, false))
            assertEquals(Outcome.Failure(DataError.Refused), demo.anime.refresh(FixtureAnime.exactToWatch.id))
            assertEquals(Outcome.Failure(DataError.Refused), demo.user.refreshList(Fixtures.reference.otherUserName))
        }

    @Test
    fun `a private list says so and is asked once`() =
        runTest {
            val demo = demo()
            val name = Fixtures.reference.privateUserName
            repeat(2) {
                assertEquals(UserListState.None(DataError.PrivateList), demo.user.observeList(name).first { it !is UserListState.Reading })
            }
            assertEquals(listOf(name), demo.userLists.requests)
        }

    @Test
    fun `the demo stamps edits with the clock it is given`() =
        runTest {
            val clock = Clock.fixed(FixtureTime.reference.plus(Duration.ofHours(5)), FixtureTime.zone)
            val demo = Demo(Fixtures.reference, backgroundScope, clock = clock)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(
                clock.instant(),
                demo.list
                    .observeEntry(FixtureAnime.exactToWatch.id)
                    .first()
                    ?.updatedAt,
            )
            assertEquals(clock.instant(), demo.server.entry(FixtureAnime.exactToWatch.id)?.updatedAt)
        }

    @Test
    fun `the scenario's offline is the demo's connection`() =
        runTest {
            val demo = demo()
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { demo.connection.observeOnline().collect { seen += it } }
            runCurrent()
            demo.server.offline.value = true
            runCurrent()
            demo.server.offline.value = false
            runCurrent()
            assertEquals(listOf(true, false, true), seen)
        }

    @Test
    fun `a title's details carry the time they were fetched, and a failed refresh keeps it`() =
        runTest {
            val clock = DeviceClock(FixtureTime.reference, FixtureTime.zone)
            val demo = Demo(Fixtures.reference, backgroundScope, clock = clock)
            val id = FixtureAnime.exactToWatch.id
            assertNull(demo.anime.observeRefreshedAt(id).first())
            demo.anime.refresh(id)
            assertEquals(FixtureTime.reference, demo.anime.observeRefreshedAt(id).first())
            clock.instant = FixtureTime.reference.plus(Duration.ofHours(1))
            demo.server.offline.value = true
            demo.anime.refresh(id)
            assertEquals(FixtureTime.reference, demo.anime.observeRefreshedAt(id).first())
            demo.server.offline.value = false
            demo.anime.refresh(id)
            assertEquals(clock.instant, demo.anime.observeRefreshedAt(id).first())
        }

    @Test
    fun `A2 the demo's aired count rises as its clock passes an air time, with no request to its server`() =
        runTest {
            val scheduler = testScheduler
            val clock =
                object : Clock() {
                    override fun getZone() = FixtureTime.zone

                    override fun withZone(zone: ZoneId) = this

                    override fun instant(): Instant = FixtureTime.reference.plusMillis(scheduler.currentTime)
                }
            val demo = Demo(Fixtures.reference, backgroundScope, clock = clock, clockRuns = true)
            val id = FixtureAnime.exactToWatch.id
            val before = Fixtures.reference.airing.getValue(id)
            val count = (before.aired as AiredCount.Exact).value
            val airsIn = Duration.between(FixtureTime.reference, before.nextEpisode!!.airsAt).toMillis()
            val seen = mutableListOf<Pair<Long, AiredCount?>>()
            backgroundScope.launch {
                demo.airing
                    .observeAiring()
                    .map { it[id]?.aired }
                    .distinctUntilChanged()
                    .collect { seen += scheduler.currentTime to it }
            }
            runCurrent()
            assertEquals(Fixtures.reference.airing.filterKeys { demo.fixtures.entryFor(it) != null }, demo.airing.observeAiring().first())

            advanceTimeBy(Duration.ofMillis(airsIn - 1).toKotlinDuration())
            runCurrent()
            assertEquals(listOf(0L to AiredCount.Exact(count)), seen)
            advanceTimeBy(Duration.ofMillis(1).toKotlinDuration())
            runCurrent()

            assertEquals(listOf(0L to AiredCount.Exact(count), airsIn to AiredCount.Exact(count + 1)), seen)
            assertEquals(emptyList<Set<AnimeId>>(), demo.airingSource.requests)
            assertEquals(0, demo.server.listDownloads)
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
        }

    @Test
    fun `the demo's notifications run on the real rules, from their first use, over the alarm and the notifications it is given`() =
        runTest {
            val clock = DeviceClock(FixtureTime.reference, FixtureTime.zone)
            val alarm = InMemoryNotificationAlarm()
            val shown = InMemoryShownNotifications()
            val runs = mutableListOf<RunReport>()
            val demo = Demo(Fixtures.reference, backgroundScope, clock = clock, alarm = alarm, shown = shown, runs = { runs += it })
            demo.list.observeEntries().first()
            runCurrent()
            assertEquals("nothing runs before its first use", emptyList<RunReport>(), runs)

            assertEquals(RunEnd.Done, demo.notifications.run(RunTrigger.AppOpened).end)
            assertEquals("nothing from before", emptyMap<AnimeId, Announcement>(), shown.notifications.value)
            val next =
                demo.notifications
                    .observeComing()
                    .first()
                    .first()
            assertEquals(next.notifyAt, alarm.at.value)
            assertEquals(ListStatus.Watching, demo.fixtures.entryFor(next.anime.id)?.status)

            clock.instant = next.notifyAt
            alarm.gone()
            val report = demo.notifications.run(RunTrigger.Alarm)
            assertEquals(listOf(next.anime.id to next.episode), report.posted.map { it.anime.id to it.episode })
            assertEquals(Recheck.Answered, report.recheck)
            assertEquals(listOf(setOf(next.anime.id)), demo.airingSource.requests)
            assertEquals(setOf(next.anime.id), shown.notifications.value.keys)
            assertEquals(report.posted, demo.notifications.observeSent().first())

            // A change of the list is followed by a run, and so is a sign-out.
            demo.list.write(ListWrite.SetWatched(next.anime, next.episode))
            settle()
            assertEquals(emptyMap<AnimeId, Announcement>(), shown.notifications.value)
            demo.session.signOut()
            settle()
            assertNull(alarm.at.value)
            assertEquals(emptyList<SentNotification>(), demo.notifications.observeSent().first())
            assertEquals(RunEnd.SignedOut, runs.last().end)
        }

    @Test
    fun `the demo's episode times are the fixtures', asked for behind its server's failures and kept in memory`() =
        runTest {
            val demo = demo()
            val tracked = demo.fixtures.schedules.filterKeys { id -> demo.fixtures.entryFor(id)?.tracked == true }
            val page = FixtureAnime.notOnListAiring
            demo.server.failingLoads.value = true
            assertEquals(Outcome.Failure(DataError.ServerError), demo.airing.refresh())
            assertEquals(Outcome.Failure(DataError.ServerError), demo.airing.refresh(page))
            assertEquals(tracked, demo.airing.observeSchedules().first())

            demo.server.failingLoads.value = false
            assertEquals(Outcome.Success(Unit), demo.airing.refresh())
            assertEquals(Outcome.Success(Unit), demo.airing.refresh(page))

            assertEquals(
                listOf(tracked.keys, setOf(page.id), tracked.keys, setOf(page.id)),
                demo.airingSource.requests.map {
                    it.intersect(
                        tracked.keys + page.id,
                    )
                },
            )
            assertEquals(tracked, demo.airing.observeSchedules().first())
            assertEquals(Fixtures.reference.airing[page.id], demo.airing.observePageAiring(page).first()[page.id])
            assertNull(demo.airing.observeAiring().first()[page.id])
        }
}
