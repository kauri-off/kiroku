package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

// A swipe or a tap moves the pager and the tab row only, though the view model hears of the new tab mid-slide.
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

    private var pagesComposed = 0

    // Pages composed inside a scroll of the pager, which remeasures it at once: the crash's precondition on main.
    private var composedInScroll = 0

    private val tracer =
        object : CompositionTracer {
            override fun isTraceInProgress() = true

            override fun traceEventStart(
                key: Int,
                dirty1: Int,
                dirty2: Int,
                info: String,
            ) {
                val name = info.substringBefore(" (")
                if (counting) counts.merge(name, 1, Int::plus)
                if (name != PAGE) return
                pagesComposed++
                if (Throwable().stackTrace.any { it.methodName == "forceRemeasure" }) composedInScroll++
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

    // The frames from the first that composes a page until every page is composed, as the pages each composed.
    private fun firstFrames(): List<Int> {
        Composer.setTracer(tracer)
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        val pages = mutableListOf<Int>()
        repeat(FIRST_FRAMES) {
            val composed = frame()[PAGE] ?: 0
            if (pages.isNotEmpty() || composed > 0) pages += composed
        }
        return pages.dropLastWhile { it == 0 }
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

    @Test
    fun `the List's first frame composes the shown page and its neighbour, and each later frame one more page`() {
        val pages = firstFrames()
        assertEquals("pages composed per frame: $pages", listOf(2, 1, 1, 1), pages.filter { it > 0 })
        assertEquals("pages composed per frame: $pages", 2, pages.first())
    }

    @Test
    fun `a tap before every page is composed composes them before its page moves`() {
        Composer.setTracer(tracer)
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        while (pagesComposed == 0) frame()
        compose.onNode(hasText("Dropped") and isSelectable()).performClick()
        repeat(SETTLE_FRAMES) { frame() }
        assertEquals("pages composed", ListStatus.entries.size, pagesComposed)
        assertEquals("pages composed inside the pager's scroll", 0, composedInScroll)
        compose.onNode(hasText("Dropped") and isSelectable()).assertIsSelected()
    }

    // B2's M1: a drag made before every page is composed composes none inside the pager's scroll, and turns the page.
    @Test
    fun `a drag before every page is composed composes no page inside the pager's scroll and still turns the page`() {
        Composer.setTracer(tracer)
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        while (pagesComposed == 0) frame()
        val start = indicatorLeft()
        compose.onRoot().performTouchInput { down(center) }
        var held = 0
        var moved = false
        repeat(DRAG_FRAMES) {
            compose.onRoot().performTouchInput { moveBy(Offset(-width * DRAG_STEP, 0f)) }
            frame()
            moved = moved || indicatorLeft() != start
            if (!moved) held++
        }
        compose.onRoot().performTouchInput { up() }
        repeat(SETTLE_FRAMES) { frame() }
        assertEquals("pages composed inside the pager's scroll", 0, composedInScroll)
        assertEquals("pages composed", ListStatus.entries.size, pagesComposed)
        assertTrue("the drag was held for $held frames", held <= 1)
        compose.mainClock.advanceTimeBy(SETTLED)
        settle()
        compose.onNode(hasText("Planned") and isSelectable()).assertIsSelected()
    }

    private fun indicatorLeft() = compose.onNodeWithTag(INDICATOR, useUnmergedTree = true).getUnclippedBoundsInRoot().left

    private companion object {
        const val DRAG_FRAMES = 10
        const val DRAG_STEP = 0.06f
        const val SETTLE_FRAMES = 30
        const val SETTLED = 2_000L
        const val SETTLE_ROUNDS = 5
        const val FIRST_FRAMES = 60
        const val PAGE = "io.github.kauri_off.kiroku.list.Page"

        val pageComposables =
            listOf(
                "io.github.kauri_off.kiroku.design.component.AnimeRow",
                "io.github.kauri_off.kiroku.list.Page",
                "io.github.kauri_off.kiroku.list.Rows",
            )
    }
}
