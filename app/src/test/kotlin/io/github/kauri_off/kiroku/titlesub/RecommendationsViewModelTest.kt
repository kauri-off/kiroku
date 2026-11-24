package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.title.pageSummary
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val fixture = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val details = fixture.details.getValue(anime.id)
    private val recommended = FixtureAnime.exactCaughtUp

    @Test
    fun `recommendations use the opener immediately and read only their list entries when shown`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val opened = TitleSummary(anime, null, null, fixture.session, pageSummary(details, emptyMap(), emptyMap()))
            val vm = recommendations(graph, opened = opened)
            runCurrent()
            advanceUntilIdle()
            assertEquals(0, graph.readers)
            assertEquals(0, graph.refreshes)
            assertEquals(details.recommendations.map { it.id }, shown(vm).rows.map { it.id })
            assertEquals(recommended, vm.summaryOf(recommended.id)?.anime)

            graph.anime.refresh(anime.id)
            showing(vm.state, vm.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf(anime.id), graph.requested)
            assertEquals(listOf(details.recommendations.map { it.id }.toSet()), graph.entryQueries)
            assertEquals(0, graph.wholeListReads)
            val initial = shown(vm).rows.first { it.id == recommended.id }
            assertEquals(recommended.meanScore, initial.score)
            val entry = fixture.entries.first { it.anime.id == recommended.id }
            assertEquals(entry.values, initial.entry)
            assertEquals(entry, vm.summaryOf(recommended.id)?.entry)

            graph.list.write(ListWrite.MarkNextWatched(recommended))
            runCurrent()
            advanceUntilIdle()
            assertEquals(
                entry.values.watchedEpisodes + 1,
                shown(vm)
                    .rows
                    .first { it.id == recommended.id }
                    .entry
                    ?.watchedEpisodes,
            )
            assertEquals(listOf(anime.id), graph.requested)
            scope.cancel()
        }

    @Test
    fun `failed load offers Retry and a successful page with no recommendations is empty`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            graph.demo.server.failingLoads.value = true
            val title = FixtureAnime.premiereYear
            assertTrue(
                fixture.details
                    .getValue(title.id)
                    .recommendations
                    .isEmpty(),
            )
            val vm = recommendations(graph, title)
            assertEquals(RecommendationsContent.Loading, vm.state.value.content)
            showing(vm.state, vm.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(RecommendationsContent.Failed(DataError.ServerError), vm.state.value.content)

            graph.demo.server.failingLoads.value = false
            vm.onEvent(RecommendationsEvent.Reload)
            runCurrent()
            advanceUntilIdle()
            assertTrue(shown(vm).rows.isEmpty())
            assertEquals(listOf(title.id, title.id), graph.requested)
            assertTrue(graph.entryQueries.isEmpty())
            scope.cancel()
        }

    @Test
    fun `failed refresh keeps cached rows and reports the error once`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val errors = mutableListOf<DataError>()
            graph.anime.refresh(anime.id)
            val vm = recommendations(graph, onRefreshFailed = { errors += it })
            showing(vm.state, vm.shown)
            runCurrent()
            advanceUntilIdle()
            val before = shown(vm).rows

            graph.demo.server.failingLoads.value = true
            vm.onEvent(RecommendationsEvent.Reload)
            runCurrent()
            advanceUntilIdle()
            assertEquals(RecommendationsContent.Shown(before), vm.state.value.content)
            assertEquals(listOf(DataError.ServerError), errors)
            scope.cancel()
        }

    @Test
    fun `a restored stale cache reports an immediate failure once after rows arrive`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val release = CompletableDeferred<Unit>()
            val errors = mutableListOf<DataError>()
            val loads = mutableListOf<AnimeId>()
            val repository =
                object : AnimeRepository by graph.anime {
                    override fun observeDetails(id: AnimeId) = flowOf(details)

                    override fun observeRefreshedAt(id: AnimeId) = flowOf(Instant.EPOCH)

                    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
                        loads += id
                        return Outcome.Failure(DataError.ServerError)
                    }
                }
            val list =
                object : ListSource by graph.list {
                    override fun observeEntriesOf(ids: Set<AnimeId>) = graph.list.observeEntriesOf(ids).onStart { release.await() }
                }
            val vm = recommendations(graph, repository = repository, list = list, onRefreshFailed = { errors += it })
            showing(vm.state, vm.shown)
            runCurrent()
            assertEquals(listOf(anime.id), loads)
            assertEquals(RecommendationsContent.Loading, vm.state.value.content)
            assertTrue(errors.isEmpty())

            release.complete(Unit)
            runCurrent()
            assertEquals(details.recommendations.map { it.id }, shown(vm).rows.map { it.id })
            assertEquals(listOf(DataError.ServerError), errors)

            graph.list.write(ListWrite.MarkNextWatched(recommended))
            runCurrent()
            assertEquals(listOf(DataError.ServerError), errors)
            scope.cancel()
        }

    @Test
    fun `a refreshed empty result stays visible on failure and can load rows again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val cached = MutableStateFlow(details)
            val errors = mutableListOf<DataError>()
            val loads = mutableListOf<AnimeId>()
            var next = CompletableDeferred<Outcome<Unit>>()
            val repository =
                object : AnimeRepository by graph.anime {
                    override fun observeDetails(id: AnimeId) = cached

                    override fun observeRefreshedAt(id: AnimeId) = flowOf(graph.clock.instant())

                    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
                        loads += id
                        return next.await()
                    }
                }
            val vm = recommendations(graph, repository = repository, onRefreshFailed = { errors += it })
            showing(vm.state, vm.shown)
            runCurrent()
            assertFalse(shown(vm).rows.isEmpty())
            assertTrue(loads.isEmpty())

            vm.onEvent(RecommendationsEvent.Reload)
            runCurrent()
            cached.value = details.copy(recommendations = emptyList())
            next.complete(Outcome.Success(Unit))
            runCurrent()
            assertEquals(RecommendationsContent.Shown(emptyList()), vm.state.value.content)

            next = CompletableDeferred()
            vm.onEvent(RecommendationsEvent.Reload)
            runCurrent()
            assertEquals(RecommendationsContent.Shown(emptyList(), refreshing = true), vm.state.value.content)
            next.complete(Outcome.Failure(DataError.ServerError))
            runCurrent()
            assertEquals(RecommendationsContent.Shown(emptyList()), vm.state.value.content)
            assertEquals(listOf(DataError.ServerError), errors)

            next = CompletableDeferred()
            vm.onEvent(RecommendationsEvent.Reload)
            runCurrent()
            cached.value = details
            next.complete(Outcome.Success(Unit))
            runCurrent()
            assertEquals(details.recommendations.map { it.id }, shown(vm).rows.map { it.id })
            assertEquals(listOf(anime.id, anime.id, anime.id), loads)
            assertEquals(listOf(DataError.ServerError), errors)
            scope.cancel()
        }

    private fun shown(vm: RecommendationsViewModel): RecommendationsContent.Shown = vm.state.value.content as RecommendationsContent.Shown

    private fun recommendations(
        graph: TestGraph,
        title: Anime = anime,
        opened: TitleSummary? = null,
        repository: AnimeRepository = graph.anime,
        list: ListSource = graph.list,
        onRefreshFailed: (DataError) -> Unit = {},
    ) = RecommendationsViewModel(
        title.id,
        repository,
        list,
        graph.session,
        graph.connection,
        graph.clock,
        graph.titleLanguage,
        opened,
        onRefreshFailed,
    )
}
