package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Related and Details over a title store that cannot be read: the error state with Retry, never a crash.
@OptIn(ExperimentalCoroutinesApi::class)
class SubScreenStoreTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val id = FixtureAnime.premiereYear.id

    private val opened = TitleSummary(FixtureAnime.premiereYear, null, null, null)

    @Test
    fun `Related fails on a title store that cannot be read, and Retry reads it again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { titlesUnreadable.value = true }
            val related =
                RelatedViewModel(
                    id,
                    graph.anime,
                    graph.list,
                    graph.airing,
                    graph.session,
                    graph.connection,
                    graph.clock,
                    graph.titleLanguage,
                    opened,
                )
            showing(related.state, related.shown)
            advanceUntilIdle()
            assertEquals(DataError.StorageError, (related.state.value as RelatedState.Failed).error)

            graph.titlesUnreadable.value = false
            related.onEvent(RelatedEvent.Retry)
            advanceUntilIdle()
            assertTrue(related.state.value is RelatedState.Shown)

            graph.titlesUnreadable.value = true
            advanceUntilIdle()
            assertEquals(DataError.StorageError, (related.state.value as RelatedState.Failed).error)
            scope.cancel()
        }

    @Test
    fun `Details fails on a title store that cannot be read, and Retry reads it again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { titlesUnreadable.value = true }
            val details =
                DetailsViewModel(id, graph.anime, graph.session, graph.connection, graph.titleLanguage, graph.clock, opened)
            showing(details.state, details.shown)
            advanceUntilIdle()
            assertEquals(DataError.StorageError, (details.state.value as DetailsState.Failed).error)

            graph.titlesUnreadable.value = false
            details.onEvent(DetailsEvent.Retry)
            advanceUntilIdle()
            assertTrue(details.state.value is DetailsState.Shown)
            scope.cancel()
        }
}
