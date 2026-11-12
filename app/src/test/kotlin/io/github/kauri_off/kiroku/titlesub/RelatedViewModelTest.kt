package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelatedViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private fun related(
        graph: TestGraph,
        id: AnimeId = FixtureAnime.premiereYear.id,
        opened: TitleSummary? = null,
    ) = RelatedViewModel(id, graph.anime, graph.list, graph.airing, graph.session, graph.titleLanguage, opened)

    @Test
    fun `a list that cannot be read fails the screen, and Retry shows it and follows the list again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { listUnreadable = true }
            val related = related(graph)
            showing(related.state)
            advanceUntilIdle()
            assertEquals(DataError.StorageError, (related.state.value as RelatedState.Failed).error)

            graph.listUnreadable = false
            related.onEvent(RelatedEvent.Retry)
            advanceUntilIdle()
            assertEquals(12, watched(related))
            graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertEquals(13, watched(related))
            scope.cancel()
        }

    @Test
    fun `both sub-screens read their sources only while shown, and show what changed meanwhile when shown again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val related = related(graph)
            val details = details(graph)
            val screens = listOf(showing(related.state), showing(details.state))
            advanceUntilIdle()
            assertTrue(graph.readers > 0)
            screens.forEach { it.cancel() }
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            assertEquals(0, graph.readers)
            graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            showing(related.state)
            showing(details.state)
            runCurrent()
            assertTrue(graph.readers > 0)
            assertEquals(13, watched(related))
            assertTrue(details.state.value is DetailsState.Shown)
            scope.cancel()
        }

    @Test
    fun `both sub-screens read nothing before first shown, and refresh once, not again when shown again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val related = related(graph)
            val details = details(graph)
            advanceUntilIdle()
            assertEquals(0, graph.readers)
            assertEquals(0, graph.refreshes)
            var screens = listOf(showing(related.state), showing(details.state))
            runCurrent()
            assertEquals(2, graph.refreshes)
            advanceUntilIdle()
            screens.forEach { it.cancel() }
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            assertEquals(0, graph.readers)
            screens = listOf(showing(related.state), showing(details.state))
            runCurrent()
            advanceUntilIdle()
            assertTrue(graph.readers > 0)
            assertEquals(2, graph.refreshes)
            screens.forEach { it.cancel() }
            scope.cancel()
        }

    // Opened from the title page, a sub-screen has what the page showed, however late the store answers.
    @Test
    fun `both sub-screens opened with the page's summary show it at once, before the store answers`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val id = FixtureAnime.premiereYear.id
            val shown = related(graph)
            showing(shown.state)
            advanceUntilIdle()
            val entries =
                graph.list
                    .observeEntries()
                    .first()
                    .associateBy { it.anime.id }
            val summary =
                TitleSummary(
                    graph.anime.observeAnime(id).first() ?: entries.getValue(id).anime,
                    graph.anime.observeDetails(id).first(),
                    entries,
                    graph.airing.observeAiring().first(),
                    graph.session.observeSession().first(),
                )
            graph.readsHeld = true
            val related = related(graph, opened = summary)
            val details = details(graph, opened = summary)
            assertEquals(shown.state.value, related.state.value)
            assertTrue(details.state.value is DetailsState.Shown)
            showing(related.state)
            showing(details.state)
            runCurrent()
            assertEquals(shown.state.value, related.state.value)
            assertTrue(details.state.value is DetailsState.Shown)
            graph.readsHeld = false
            scope.cancel()
        }

    private fun details(
        graph: TestGraph,
        opened: TitleSummary? = null,
    ) = DetailsViewModel(FixtureAnime.premiereYear.id, graph.anime, graph.session, graph.titleLanguage, graph.clock, opened)

    private fun watched(related: RelatedViewModel) =
        (related.state.value as RelatedState.Shown)
            .groups
            .flatMap { it.rows }
            .single { it.id == FixtureAnime.exactToWatch.id }
            .entry
            ?.watchedEpisodes

    private companion object {
        const val STOPPED_AFTER_MILLIS = 5_001L
    }
}
