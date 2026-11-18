package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.profile.ProfileNotBuilt
import io.github.kauri_off.kiroku.profile.ProfileNotBuiltKey
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.OpenedPage
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
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
import kotlin.time.Duration.Companion.seconds

// A fling down another user's list of several thousand titles composes only the rows that come into view.
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class UserListFrameWorkTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose))

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    private val counts = mutableMapOf<String, Int>()
    private var counting = false

    private val tracer =
        object : CompositionTracer {
            override fun isTraceInProgress() = true

            override fun traceEventStart(
                key: Int,
                dirty1: Int,
                dirty2: Int,
                info: String,
            ) {
                if (counting) counts.merge(info.substringBefore(" ("), 1, Int::plus)
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

    private fun open(name: String = Fixtures.reference.otherUserName) {
        Composer.setTracer(tracer)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                AppRoute(graph, rememberShellNavigator(OpenedPage(Destination.Profile, ProfileNotBuiltKey(ProfileNotBuilt.UserList, name))))
            }
        }
    }

    private fun completed() {
        compose.onNode(hasText("Completed") and isSelectable()).performClick()
        repeat(SETTLE_ROUNDS) {
            main.scheduler.runCurrent()
            compose.waitForIdle()
        }
        compose.onNode(hasText("Completed") and isSelectable()).assertIsSelected()
    }

    // The rows on screen now, by title: those of the lists in the window, not of the pages beside it.
    private fun shownRows(): Set<String> {
        val window = compose.onRoot().fetchSemanticsNode().size
        return compose
            .onAllNodes(hasClickAction() and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)))
            .fetchSemanticsNodes()
            .filter {
                it.boundsInRoot.left >= 0f &&
                    it.boundsInRoot.left < window.width &&
                    it.boundsInRoot.bottom > 0f &&
                    it.boundsInRoot.top < window.height
            }.map { it.config[SemanticsProperties.Text].first().text }
            .toSet()
    }

    private class Fling(
        val frames: List<Map<String, Int>>,
        // The rows that came into view during the fling, which no frame showed before it.
        val entered: Int,
    ) {
        val composed: Int get() = frames.sumOf { it[ROW] ?: 0 }

        // A row's place in the list composed: once for each row that comes into view.
        val items: Int get() = frames.sumOf { it[ITEM] ?: 0 }
    }

    // A fling up the shown tab; [during] runs before the frame of its index.
    private fun fling(during: Map<Int, () -> Unit> = emptyMap()): Fling {
        compose.mainClock.autoAdvance = false
        val seen = shownRows().toMutableSet()
        val before = seen.size
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top + height * 0.2f, durationMillis = FLING_MILLIS) }
        val frames =
            (0 until FLING_FRAMES).map { index ->
                during[index]?.invoke()
                frame().also { seen += shownRows() }
            }
        return Fling(frames, seen.size - before)
    }

    @Test
    fun `a fling down the longest tab composes each row that comes into view once, and no page or screen part`() {
        open()
        settle()
        completed()
        val fling = fling()
        assertTrue("the fling moved the list: ${fling.entered} rows", fling.entered > ROWS_ON_A_SCREEN)
        assertTrue("${fling.composed} rows composed for ${fling.entered} that came into view", fling.composed <= fling.entered + PREFETCHED)
        assertTrue("${fling.items} items composed for ${fling.entered} that came into view", fling.items <= fling.entered + PREFETCHED)
        fling.frames.forEachIndexed { index, frame ->
            assertEquals(
                "frame $index",
                emptyMap<String, Int>(),
                frame.filterKeys {
                    it in
                        screenParts
                },
            )
        }
    }

    // A part that arrives goes at the tabs' ends: the page takes it in one frame, and no row on screen is composed again.
    @Test
    fun `a fling while a part of the list arrives composes only the rows that come into view, and the page once`() {
        graph.demo.server.latency = PART_TIME
        open()
        repeat(PARTS_BEFORE) {
            main.scheduler.advanceTimeBy(PART_TIME.inWholeMilliseconds)
            main.scheduler.runCurrent()
            compose.waitForIdle()
        }
        completed()
        val asked = graph.demo.userLists.requests.size
        val fling = fling(mapOf(ARRIVAL to { main.scheduler.advanceTimeBy(PART_TIME.inWholeMilliseconds) }))
        assertEquals("one part arrived during the fling", asked + 1, graph.demo.userLists.requests.size)
        assertTrue("the fling moved the list: ${fling.entered} rows", fling.entered > ROWS_ON_A_SCREEN)
        assertTrue("${fling.composed} rows composed for ${fling.entered} that came into view", fling.composed <= fling.entered + PREFETCHED)
        // The frames that take the part run each composed page's item slots once, its rows skipped; no other frame does.
        val items =
            fling.frames
                .withIndex()
                .filter { it.index !in ARRIVAL..ARRIVAL + 1 }
                .sumOf { it.value[ITEM] ?: 0 }
        assertTrue(
            "$items items composed outside the part's frames for ${fling.entered} that came into view",
            items <= fling.entered + PREFETCHED,
        )
        val pageWork =
            fling.frames
                .withIndex()
                .filter { (_, frame) -> frame.keys.any { it in screenParts } }
                .map { it.index }
        assertTrue("frames that composed the page: $pageWork", pageWork.all { it in ARRIVAL..ARRIVAL + 1 })
        assertTrue("the page took the part: $pageWork", pageWork.isNotEmpty())
    }

    private companion object {
        const val SETTLE_ROUNDS = 5
        const val PARTS_BEFORE = 4
        const val ARRIVAL = 10

        // The rows a lazy list composes ahead of the window at the fling's end.
        const val PREFETCHED = 3
        val PART_TIME = 1.seconds
        const val FLING_MILLIS = 80L
        const val FLING_FRAMES = 90

        // About 891 dp over the shortest row, with the prefetched one.
        const val ROWS_ON_A_SCREEN = 12
        const val ROW = "io.github.kauri_off.kiroku.design.component.AnimeRow"
        const val ITEM = "androidx.compose.foundation.lazy.items.<anonymous>"

        val screenParts =
            setOf(
                "io.github.kauri_off.kiroku.list.ListScreen",
                "io.github.kauri_off.kiroku.list.Page",
                "io.github.kauri_off.kiroku.list.Rows",
                "io.github.kauri_off.kiroku.list.ListTabs",
                "io.github.kauri_off.kiroku.list.TopBar",
            )
    }
}
