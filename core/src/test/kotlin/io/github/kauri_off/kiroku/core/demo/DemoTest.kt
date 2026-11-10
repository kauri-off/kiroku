package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.UserListLoad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

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
            assertEquals(demo.fixtures.schedules, schedules)
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
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.airing.refresh(setOf(visited)))
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
            assertEquals(Session.Expired, demo.session.observeSession().first())
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            assertEquals(Outcome.Failure(DataError.SessionExpired), demo.list.sync())
            assertEquals(Outcome.Success(Unit), demo.anime.refresh(FixtureAnime.exactToWatch.id))
        }

    @Test
    fun `signing out leaves no list and no profile`() =
        runTest {
            val demo = demo()
            assertEquals(Session.SignedIn(Fixtures.reference.user.name), demo.session.observeSession().first())
            assertEquals(Fixtures.reference.user, demo.user.observeOwnProfile().first())
            demo.session.signOut()
            assertEquals(Session.SignedOut, demo.session.observeSession().first())
            assertEquals(emptyList<Any>(), demo.list.observeEntries().first())
            assertNull(demo.user.observeOwnProfile().first())
        }

    @Test
    fun `another user's list is read only and loads by name`() =
        runTest {
            val demo = demo()
            val name = Fixtures.reference.otherUserName.uppercase()
            val loads = demo.user.loadList(name).toList()
            assertTrue(loads.first() is UserListLoad.Loading)
            assertEquals(UserListLoad.Loaded(Fixtures.reference.otherUserEntries), loads.last())

            assertEquals(
                UserListLoad.Failed(DataError.UnknownUser),
                demo.user
                    .loadList("nobody_here")
                    .toList()
                    .last(),
            )
            demo.server.offline.value = true
            assertEquals(
                UserListLoad.Failed(DataError.NoConnection),
                demo.user
                    .loadList(name)
                    .toList()
                    .last(),
            )
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
            assertEquals(
                UserListLoad.Failed(DataError.Refused),
                demo.user
                    .loadList(Fixtures.reference.otherUserName)
                    .toList()
                    .last(),
            )
        }

    @Test
    fun `a private list fails as private`() =
        runTest {
            val demo = demo()
            assertEquals(
                listOf(UserListLoad.Loading(0, null), UserListLoad.Failed(DataError.PrivateList)),
                demo.user.loadList(Fixtures.reference.privateUserName).toList(),
            )
        }

    @Test
    fun `a list loads in steps that report progress`() =
        runTest {
            val demo = demo()
            val entries = Fixtures.reference.entries
            val loads = demo.user.loadList(Fixtures.reference.user.name).toList()
            val steps = loads.filterIsInstance<UserListLoad.Loading>()
            assertTrue(steps.size > 2)
            assertEquals(steps.map { it.loaded }.sorted(), steps.map { it.loaded })
            assertEquals(entries.size, steps.last().loaded)
            assertTrue(steps.all { it.total == entries.size })
            assertEquals(UserListLoad.Loaded(entries), loads.last())
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
}
