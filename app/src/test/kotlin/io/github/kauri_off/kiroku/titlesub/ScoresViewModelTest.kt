package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// The scores screen asks the service only once it is shown, once, and again only by its Retry after a failure.
@OptIn(ExperimentalCoroutinesApi::class)
class ScoresViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private fun test(body: suspend TestScope.(TestGraph) -> Unit) =
        runTest(dispatcher) {
            val scope = foregroundScope()
            body(TestGraph(Demo(Fixtures.reference, scope)))
            scope.cancel()
        }

    private fun TestGraph.screen(anime: AnimeId = FixtureAnime.exactToWatch.id) = ScoresViewModel(anime, scores, session)

    @Test
    fun `nothing is asked until the screen is shown, then the title's distribution once, also when shown again`() =
        test { graph ->
            val scores = graph.screen()
            advanceUntilIdle()
            assertEquals(0, graph.scoreAsks)

            val shown = showing(scores.state)
            runCurrent()
            advanceUntilIdle()
            assertEquals(
                ScoresState.Shown(Fixtures.reference.scores.getValue(FixtureAnime.exactToWatch.id), Fixtures.reference.session),
                scores.state.value,
            )
            shown.cancel()
            advanceUntilIdle()
            showing(scores.state)
            runCurrent()
            scores.onEvent(ScoresEvent.Retry)
            advanceUntilIdle()

            assertEquals(1, graph.scoreAsks)
        }

    @Test
    fun `a failure shows with its error, and its Retry asks again`() =
        test { graph ->
            graph.demo.scoreSource.failure = DataError.NoConnection
            val scores = graph.screen()
            showing(scores.state)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ScoresState.Failed(DataError.NoConnection, Fixtures.reference.session), scores.state.value)

            graph.demo.scoreSource.failure = null
            scores.onEvent(ScoresEvent.Retry)
            advanceUntilIdle()

            assertTrue(scores.state.value is ScoresState.Shown)
            assertEquals(2, graph.scoreAsks)
        }

    @Test
    fun `a title the service has no record of is not known, and nobody scored is a distribution without votes`() =
        test { graph ->
            val unknown = graph.screen(FixtureAnime.premiereUnannounced.id)
            val unscored = graph.screen(FixtureAnime.premiereTimeSoon.id)
            showing(unknown.state, unscored.state)
            runCurrent()
            advanceUntilIdle()

            assertEquals(ScoresState.NotKnown(Fixtures.reference.session), unknown.state.value)
            assertEquals(0, (unscored.state.value as ScoresState.Shown).distribution.total)
        }

    @Test
    fun `a title's answer is kept for the process, so its screen opened again asks the service nothing`() =
        test { graph ->
            showing(graph.screen().state)
            runCurrent()
            advanceUntilIdle()
            val again = graph.screen()
            showing(again.state)
            runCurrent()
            advanceUntilIdle()

            assertTrue(again.state.value is ScoresState.Shown)
            assertEquals(listOf(FixtureAnime.exactToWatch.id), graph.demo.scoreSource.requests)
        }
}
