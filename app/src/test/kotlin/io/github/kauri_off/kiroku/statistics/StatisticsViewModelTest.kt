package io.github.kauri_off.kiroku.statistics

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoUserLists
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.profile.OpenedProfiles
import io.github.kauri_off.kiroku.profile.ProfileContent
import io.github.kauri_off.kiroku.profile.ProfileOwner
import io.github.kauri_off.kiroku.profile.ProfileViewModel
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val fixtures = Fixtures.reference
    private val otherName = fixtures.otherUserName

    // One whole read of the public user's list: a request a part.
    private val wholeRead = (fixtures.otherUserList.size + DemoUserLists.PART_SIZE - 1) / DemoUserLists.PART_SIZE

    private fun TestScope.graph() = TestGraph(Demo(fixtures, foregroundScope()))

    private fun TestGraph.statistics(
        name: String?,
        page: StatisticsPage = StatisticsPage.Genres,
    ) = StatisticsViewModel(page, name, session, list, user, connection, background)

    private fun TestScope.profile(
        graph: TestGraph,
        name: String,
    ) = with(graph) {
        ProfileViewModel(
            ProfileOwner.Other(name),
            session,
            list,
            user,
            connection,
            clock,
            background,
            SavedStateHandle(),
            ShellMessages(foregroundScope(), list),
            OpenedProfiles(preferences),
        )
    }

    private val TestGraph.requests get() = demo.userLists.requests

    @Test
    fun `opened from another user's profile, the statistics are that user's, from the read the profile made`() =
        runTest(dispatcher) {
            val graph = graph()
            val profile = profile(graph, otherName)
            val profileShown = showing(profile.state, profile.shown)
            runCurrent()
            advanceUntilIdle()
            assertTrue(profile.state.value.content is ProfileContent.Shown)
            assertEquals(wholeRead, graph.requests.size)
            profileShown.cancel()
            advanceTimeBy(10.seconds)

            for (page in StatisticsPage.entries) {
                val statistics = graph.statistics(otherName, page)
                showing(statistics.state, statistics.shown)
                runCurrent()
                advanceUntilIdle()
                assertEquals(
                    StatisticsState(page, otherName, StatisticsContent.Shown(statisticsOf(fixtures.otherUserList))),
                    statistics.state.value,
                )
            }
            assertEquals(wholeRead, graph.requests.size)
        }

    @Test
    fun `another user's statistics are never shown over a list not yet whole, and nothing is read before the page is shown`() =
        runTest(dispatcher) {
            val graph = graph()
            graph.demo.server.latency = 10.milliseconds
            val statistics = graph.statistics(otherName)
            val seen = mutableListOf<StatisticsContent>()
            backgroundScope.launch { statistics.state.collect { seen += it.content } }
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf<StatisticsContent>(StatisticsContent.Loading), seen.distinct())
            assertTrue(graph.requests.isEmpty())

            showing(statistics.shown)
            runCurrent()
            advanceUntilIdle()
            runCurrent()
            val reading = seen.filterIsInstance<StatisticsContent.Reading>().map { it.progress }.distinct()
            assertEquals((0 until wholeRead).map { UserListProgress(it * DemoUserLists.PART_SIZE, null) }, reading)
            assertEquals(1, seen.count { it is StatisticsContent.Shown })
            assertEquals(StatisticsContent.Shown(statisticsOf(fixtures.otherUserList)), seen.last())
            assertEquals(wholeRead, graph.requests.size)
        }

    @Test
    fun `the own statistics are the calculator's over the stored list and follow an edit, and signed out they say so`() =
        runTest(dispatcher) {
            val graph = graph()
            val own = graph.statistics(null, StatisticsPage.Scores)
            showing(own.state, own.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(StatisticsContent.Shown(statisticsOf(graph.list.observeEntries().first())), own.state.value.content)

            val unscored =
                graph.list
                    .observeEntries()
                    .first()
                    .first { it.values.score == null && it.status == ListStatus.Completed }
            graph.list.write(ListWrite.Save(unscored.anime, unscored.values.copy(score = 9)))
            runCurrent()
            advanceUntilIdle()
            val shown = own.state.value.content as StatisticsContent.Shown
            assertEquals(statisticsOf(graph.list.observeEntries().first()), shown.statistics)
            assertEquals(fixtures.userStatistics.scored + 1, shown.statistics.scored)
            assertTrue(graph.requests.isEmpty())

            graph.session.signOut()
            runCurrent()
            advanceUntilIdle()
            assertEquals(StatisticsContent.SignedOut, own.state.value.content)
        }

    @Test
    fun `a read that fails is the error with Retry, which reads the list again, and the private user is their own state`() =
        runTest(dispatcher) {
            val graph = graph()
            graph.demo.server.failingLoads.value = true
            val statistics = graph.statistics(otherName)
            showing(statistics.state, statistics.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(StatisticsContent.Failed(DataError.ServerError), statistics.state.value.content)
            graph.demo.server.failingLoads.value = false
            statistics.onEvent(StatisticsEvent.Retry)
            advanceUntilIdle()
            assertEquals(StatisticsContent.Shown(statisticsOf(fixtures.otherUserList)), statistics.state.value.content)

            val private = graph.statistics(fixtures.privateUserName)
            showing(private.state, private.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(StatisticsContent.Failed(DataError.PrivateList), private.state.value.content)
        }

    // A signed-in user's list before its first download: an empty store, never synced, until [sync] stores the fixtures'.
    private class NeverSynced(
        var answer: Outcome<Unit>,
    ) : ListSource {
        val entries = MutableStateFlow<List<ListEntry>>(emptyList())
        val syncedAt = MutableStateFlow<Instant?>(null)
        var syncs = 0

        override fun observeEntries(): Flow<List<ListEntry>> = entries

        override fun observeEntry(id: AnimeId): Flow<ListEntry?> = entries.map { all -> all.firstOrNull { it.anime.id == id } }

        override fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> =
            entries.map { all -> all.filter { it.anime.id in ids }.associateBy { it.anime.id } }

        override fun observeSyncedAt(): Flow<Instant?> = syncedAt

        override suspend fun sync(): Outcome<Unit> {
            syncs++
            delay(10.milliseconds)
            if (answer is Outcome.Success) {
                entries.value = Fixtures.reference.entries
                syncedAt.value = Fixtures.reference.now
            }
            return answer
        }
    }

    @Test
    fun `before the first download the own page waits for it, and a download that fails is the error with Retry`() =
        runTest(dispatcher) {
            val graph = graph()
            val list = NeverSynced(Outcome.Failure(DataError.ServerError))
            val own = StatisticsViewModel(StatisticsPage.Genres, null, graph.session, list, graph.user, graph.connection, graph.background)
            showing(own.state)
            runCurrent()
            advanceUntilIdle()
            assertEquals(StatisticsContent.Loading, own.state.value.content)
            assertEquals(0, list.syncs)

            showing(own.shown)
            runCurrent()
            advanceTimeBy(5.milliseconds)
            runCurrent()
            assertEquals(StatisticsContent.Loading, own.state.value.content)
            advanceUntilIdle()
            assertEquals(StatisticsContent.Failed(DataError.ServerError), own.state.value.content)
            assertEquals(1, list.syncs)

            list.answer = Outcome.Success(Unit)
            own.onEvent(StatisticsEvent.Retry)
            advanceUntilIdle()
            assertEquals(StatisticsContent.Shown(statisticsOf(fixtures.entries)), own.state.value.content)
            assertEquals(2, list.syncs)
        }
}
