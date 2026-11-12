package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

// A frame of a swipe or a tab tap moves the pager and draws the tab row from it; no page or row is composed again,
// though the view model hears of the new tab while the page still moves.
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ListFrameWorkTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose))

    private val scope = CoroutineScope(SupervisorJob() + main)

    // Read anew for every build, so pages built again would show a new moment and be composed again.
    private val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone, step = Duration.ofMillis(1))
    private val graph = TestGraph(Demo(Fixtures.reference, scope, clock = clock))

    private val counts = mutableMapOf<String, Int>()
    private var counting = false

    private val tracer =
        object : CompositionTracer {
            override fun isTraceInProgress() = counting

            override fun traceEventStart(
                key: Int,
                dirty1: Int,
                dirty2: Int,
                info: String,
            ) {
                counts.merge(info.substringBefore(" ("), 1, Int::plus)
            }

            override fun traceEventEnd() = Unit
        }

    @After
    fun stop() {
        Composer.setTracer(null)
        scope.cancel()
    }

    private fun settle() =
        repeat(SETTLE_ROUNDS) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }

    private fun frame(): Map<String, Int> {
        counts.clear()
        counting = true
        main.scheduler.runCurrent()
        compose.mainClock.advanceTimeByFrame()
        main.scheduler.runCurrent()
        counting = false
        return counts.toMap()
    }

    // The composables a frame composed that belong to a page: its rows or the page itself.
    private fun Map<String, Int>.pageWork() = filterKeys { name -> pageComposables.any { name == it || name.startsWith("$it.") } }

    private fun assertNoPageWork(
        phase: String,
        frames: List<Map<String, Int>>,
    ) = frames.forEachIndexed { index, frame -> assertEquals("$phase, frame $index", emptyMap<String, Int>(), frame.pageWork()) }

    private fun app() {
        Composer.setTracer(tracer)
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        settle()
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `a drag and its settle compose no page and no row, and only the selection flip composes the tab row`() {
        app()
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.05f, 0f))
        }
        val drag =
            (1..DRAG_FRAMES).map {
                compose.onRoot().performTouchInput { moveBy(Offset(-width * DRAG_STEP, 0f)) }
                frame()
            }
        assertNoPageWork("drag", drag)
        assertEquals("only the frame the selection flips in composes: $drag", 1, drag.count { it.isNotEmpty() })
        compose.onRoot().performTouchInput { up() }
        val settle = (1..SETTLE_FRAMES).map { frame() }
        assertNoPageWork("settle", settle)
        compose.mainClock.advanceTimeBy(SETTLED)
        settle()
        compose.onNode(hasText("Planned") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `a tap on a tab composes no page and no row while its page slides in`() {
        app()
        for (tab in listOf("Planned", "Dropped", "Completed", "Watching")) {
            compose.onNode(hasText(tab) and isSelectable()).performClick()
            assertNoPageWork("tap on $tab", (1..SETTLE_FRAMES).map { frame() })
            compose.onNode(hasText(tab) and isSelectable()).assertIsSelected()
        }
    }

    private companion object {
        const val DRAG_FRAMES = 10
        const val DRAG_STEP = 0.06f
        const val SETTLE_FRAMES = 30
        const val SETTLED = 2_000L
        const val SETTLE_ROUNDS = 5

        val pageComposables =
            listOf(
                "io.github.kauri_off.kiroku.design.component.AnimeRow",
                "io.github.kauri_off.kiroku.list.Page",
                "io.github.kauri_off.kiroku.list.Rows",
            )
    }
}
