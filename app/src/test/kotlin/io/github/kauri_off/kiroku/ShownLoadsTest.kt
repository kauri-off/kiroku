package io.github.kauri_off.kiroku

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.list.ListEvent
import io.github.kauri_off.kiroku.list.ListViewModel
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.title.TitleEvent
import io.github.kauri_off.kiroku.title.TitleViewModel
import io.github.kauri_off.kiroku.titlesub.DetailsEvent
import io.github.kauri_off.kiroku.titlesub.DetailsViewModel
import io.github.kauri_off.kiroku.titlesub.RelatedEvent
import io.github.kauri_off.kiroku.titlesub.RelatedViewModel
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// ARCHITECTURE 7.1 for every screen that loads by itself: what showing it, hiding it and the connection ask of MAL.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class ShownLoadsTest(
    private val screen: Screen,
) {
    class Opened(
        val model: ViewModel,
        val state: Flow<*>,
        val shown: StateFlow<Unit>,
        val retry: () -> Unit,
        val onSelectTabs: () -> Unit = {},
    )

    class Screen(
        private val name: String,
        // What this screen asks MAL for, counted.
        val loads: (TestGraph) -> Int,
        // Every request it makes, with the title page's walk along its chain after its own load.
        val asked: (TestGraph) -> Int = loads,
        val open: (TestGraph, CoroutineScope) -> Opened,
    ) {
        override fun toString() = name
    }

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private inner class Subject(
        val graph: TestGraph,
        val clock: MovingClock,
        val opened: Opened,
        private val test: TestScope,
    ) {
        private var collecting: Job? = null

        val loads: Int get() = screen.loads(graph)
        val asked: Int get() = screen.asked(graph)

        fun show() {
            collecting = test.showing(opened.state, opened.shown)
            test.runCurrent()
        }

        fun hide() {
            collecting?.cancel()
            test.runCurrent()
        }

        fun age(by: Duration) {
            clock.instant += by
        }
    }

    // Bounded, so that loads repeated without end fail a test rather than hang it.
    private fun TestScope.settle() {
        advanceTimeBy(Duration.ofMinutes(1).toMillis())
        runCurrent()
    }

    private fun test(body: suspend TestScope.(Subject) -> Unit) =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
            val graph = TestGraph(Demo(Fixtures.reference, scope, LATENCY, clock))
            val subject = Subject(graph, clock, screen.open(graph, scope), this)
            try {
                settle()
                body(subject)
            } finally {
                subject.hide()
                subject.opened.model.viewModelScope
                    .cancel()
                scope.cancel()
            }
        }

    @Test
    fun `a fresh screen shown again past its stop timeout asks nothing, and a stale one loads once`() =
        test { s ->
            s.show()
            settle()
            assertEquals("the first show of a screen never loaded", 1, s.loads)
            val asked = s.asked
            s.hide()
            advanceTimeBy(STOPPED_AFTER)
            s.age(Duration.ofMinutes(1))
            s.show()
            settle()
            assertEquals("fresh", 1, s.loads)
            assertEquals("fresh, nothing at all", asked, s.asked)
            s.hide()
            advanceTimeBy(STOPPED_AFTER)
            s.age(STALE)
            s.show()
            settle()
            assertEquals("stale", 2, s.loads)
            advanceTimeBy(Duration.ofHours(1).toMillis())
            runCurrent()
            assertEquals("once", 2, s.loads)
        }

    @Test
    fun `a stale screen shown again within its stop timeout, as after a rotation, asks nothing`() =
        test { s ->
            s.show()
            settle()
            val asked = s.asked
            s.hide()
            advanceTimeBy(ROTATION)
            s.age(STALE)
            s.show()
            settle()
            assertEquals(1, s.loads)
            assertEquals(asked, s.asked)
        }

    // The List's pages beside the shown one are no screens of their own.
    @Test
    fun `nothing is asked before the screen is shown, and its state drawn alone, as under a back gesture, asks nothing`() =
        test { s ->
            val drawn = showing(s.opened.state)
            runCurrent()
            s.opened.onSelectTabs()
            settle()
            assertTrue(s.graph.readers > 0)
            assertEquals(0, s.asked)
            s.show()
            settle()
            assertEquals(1, s.loads)
            val asked = s.asked
            s.hide()
            advanceTimeBy(STOPPED_AFTER)
            s.age(STALE)
            settle()
            assertEquals("drawn alone, stale", asked, s.asked)
            s.show()
            settle()
            s.opened.onSelectTabs()
            settle()
            assertEquals(2, s.loads)
            drawn.cancel()
        }

    @Test
    fun `a load that failed for the connection is repeated once when it returns, and not again while it stays`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.show()
            settle()
            assertEquals(1, s.loads)
            s.graph.connectionSays.value = true
            settle()
            assertEquals("the connection returned", 2, s.loads)
            s.hide()
            advanceTimeBy(ROTATION)
            s.show()
            advanceTimeBy(Duration.ofHours(1).toMillis())
            runCurrent()
            assertEquals("the connection stays, though the load failed for it again", 2, s.loads)
            s.graph.connectionSays.value = false
            settle()
            s.graph.connectionSays.value = true
            settle()
            assertEquals("it returned once more", 3, s.loads)
        }

    @Test
    fun `a Retry under way when the connection returns is repeated once if it fails for the connection`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.graph.connectionSays.value = false
            s.show()
            settle()
            s.opened.retry()
            runCurrent()
            s.graph.connectionSays.value = true
            settle()
            assertEquals(3, s.loads)
        }

    // The List's engine runs syncs one after another (W6), so there a newer sync always ends after an older one.
    @Test
    fun `an older load ending after a newer one does not stand for the screen`() =
        test { s ->
            assumeTrue(screen.toString() != "List")
            s.graph.demo.server.latency = 3.seconds
            s.show()
            advanceTimeBy(500)
            s.graph.demo.server.latency = LATENCY
            s.opened.retry()
            advanceTimeBy(500)
            s.graph.demo.server.offline.value = true
            settle()
            s.graph.demo.server.offline.value = false
            settle()
            assertEquals("the older load's failure owed no repeat", 2, s.loads)
        }

    @Test
    fun `a load that failed for another reason is not repeated when the connection returns`() =
        test { s ->
            s.graph.demo.server.failingLoads.value = true
            s.graph.connectionSays.value = false
            s.show()
            settle()
            assertEquals(1, s.loads)
            s.graph.connectionSays.value = true
            settle()
            assertEquals(1, s.loads)
        }

    @Test
    fun `a screen that is not shown asks nothing when the connection returns, and loads once when shown again`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.show()
            settle()
            s.hide()
            s.graph.demo.server.offline.value = false
            settle()
            assertEquals(1, s.loads)
            s.show()
            settle()
            assertEquals(2, s.loads)
        }

    companion object {
        private const val ROTATION = 4_000L
        private val LATENCY = 100.milliseconds
        private const val STOPPED_AFTER = 5_001L

        // Older than any screen's freshness.
        private val STALE = Duration.ofDays(1)

        private val title = FixtureAnime.exactToWatch.id

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens(): List<Screen> =
            listOf(
                Screen("List", TestGraph::syncs) { graph, scope ->
                    val list =
                        ListViewModel(
                            graph.list,
                            graph.anime,
                            graph.airing,
                            graph.session,
                            graph.connection,
                            graph.preferences,
                            graph.clock,
                            graph.titleLanguage,
                            SavedStateHandle(),
                            ShellMessages(scope, graph.list),
                            graph.background,
                        )
                    Opened(list, list.state, list.shown, {
                        list.onEvent(ListEvent.Retry)
                    }) { ListStatus.entries.forEach { list.onEvent(ListEvent.SelectTab(it)) } }
                },
                // Its loads are its own title's; its walk asks for the chain's other titles after the first.
                Screen("Title page", { graph -> graph.requested.count { it == title } }, asked = TestGraph::refreshes) { graph, scope ->
                    val page =
                        TitleViewModel(
                            title,
                            graph.anime,
                            graph.list,
                            graph.airing,
                            graph.scores,
                            graph.session,
                            graph.connection,
                            graph.clock,
                            graph.titleLanguage,
                            SavedStateHandle(),
                            ShellMessages(scope, graph.list),
                        )
                    Opened(page, page.state, page.shown, { page.onEvent(TitleEvent.Retry) })
                },
                Screen("Related", TestGraph::refreshes) { graph, _ ->
                    val related =
                        RelatedViewModel(
                            title,
                            graph.anime,
                            graph.list,
                            graph.airing,
                            graph.session,
                            graph.connection,
                            graph.clock,
                            graph.titleLanguage,
                        )
                    Opened(related, related.state, related.shown, { related.onEvent(RelatedEvent.Retry) })
                },
                Screen("Details", TestGraph::refreshes) { graph, _ ->
                    val details = DetailsViewModel(title, graph.anime, graph.session, graph.connection, graph.titleLanguage, graph.clock)
                    Opened(details, details.state, details.shown, { details.onEvent(DetailsEvent.Retry) })
                },
            )
    }
}
