package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelatedViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    @Test
    fun `a list that cannot be read fails the screen, and Retry shows it and follows the list again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { listUnreadable = true }
            val related = RelatedViewModel(graph, FixtureAnime.premiereYear.id)
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

    private fun watched(related: RelatedViewModel) =
        (related.state.value as RelatedState.Shown)
            .groups
            .flatMap { it.rows }
            .single { it.id == FixtureAnime.exactToWatch.id }
            .entry
            ?.watchedEpisodes
}
