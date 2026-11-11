package io.github.kauri_off.kiroku.list

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.width
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

// The tab row is drawn from the pager's position: through a drag, its settle and a tap (ARCHITECTURE 8.5).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class ListTabMotionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf(ListFixtureStates.watching)
    private val events = mutableListOf<ListEvent>()

    private val watchingRow = FixtureAnime.estimatedCaughtUp.titles.romaji
    private val plannedRow =
        ListFixtureStates.planned.page!!
            .rows
            .first()
            .title
    private val droppedRow =
        ListFixtureStates.dropped.page!!
            .rows
            .first()
            .title
    private val completedRow =
        ListFixtureStates.completed.page!!
            .rows
            .first()
            .title

    // Follows the selected tab as the view model does.
    private fun show(start: ListState) {
        state = start
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                FixtureListScreen(state) { event ->
                    events += event
                    if (event is ListEvent.SelectTab) state = state.copy(tab = event.status)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLED)
    }

    private fun bounds(node: SemanticsNodeInteraction): DpRect = node.getUnclippedBoundsInRoot()

    private fun label(text: String): DpRect = bounds(compose.onAllNodesWithText(text, useUnmergedTree = true)[0])

    private fun indicator(): DpRect = bounds(compose.onNodeWithTag(INDICATOR, useUnmergedTree = true))

    private fun tab(text: String) = compose.onNode(hasText(text) and isSelectable())

    private fun left(title: String) =
        compose
            .onNodeWithText(title)
            .getUnclippedBoundsInRoot()
            .left.value

    private val pageWidth: Float get() =
        compose
            .onRoot()
            .getUnclippedBoundsInRoot()
            .width.value

    // The pager's place, from a row of the first page: 0.4 is four tenths of the way to the next page.
    private fun pagePosition(
        row: String,
        rest: Float,
    ) = (rest - left(row)) / pageWidth

    // The indicator sits between the two labels at the pager's place, as wide as the blend of the two.
    private fun assertIndicatorAt(
        from: String,
        to: String,
        fraction: Float,
    ) {
        val a = label(from)
        val b = label(to)
        val shown = indicator()
        val expectedLeft = a.left.value + (b.left.value - a.left.value) * fraction
        val expectedWidth = a.width.value + (b.width.value - a.width.value) * fraction
        assertTrue("left ${shown.left.value} at $fraction, expected $expectedLeft", abs(shown.left.value - expectedLeft) <= TOLERANCE)
        assertTrue("width ${shown.width.value}, expected $expectedWidth", abs(shown.width.value - expectedWidth) <= TOLERANCE)
    }

    @Test
    fun `a drag held part-way moves the indicator with the page and flips the selection half-way`() {
        show(ListFixtureStates.watching)
        val rest = left(watchingRow)
        assertIndicatorAt("Watching", "Planned", 0f)
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.3f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        val third = pagePosition(watchingRow, rest)
        assertTrue("the page follows the finger: $third", third in 0.15f..0.35f)
        assertIndicatorAt("Watching", "Planned", third)
        tab("Watching").assertIsSelected()
        tab("Planned").assertIsNotSelected()
        compose.onRoot().performTouchInput { moveBy(Offset(-width * 0.35f, 0f)) }
        compose.mainClock.advanceTimeByFrame()
        val past = pagePosition(watchingRow, rest)
        assertTrue(past > 0.5f)
        assertIndicatorAt("Watching", "Planned", past)
        tab("Planned").assertIsSelected()
        tab("Watching").assertIsNotSelected()
        assertTrue("nothing is selected in the view model while the finger is down", events.isEmpty())
        compose.onRoot().performTouchInput { up() }
        var frames = 0
        while (pagePosition(watchingRow, rest) < 1f - SETTLE_EPSILON && frames++ < MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            assertIndicatorAt("Watching", "Planned", pagePosition(watchingRow, rest).coerceIn(0f, 1f))
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIndicatorAt("Planned", "Planned", 0f)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned)), events)
        assertIdle()
    }

    @Test
    fun `dragging past the first tab keeps the indicator under it`() {
        show(ListFixtureStates.watching)
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.3f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        assertIndicatorAt("Watching", "Watching", 0f)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIdle()
    }

    @Test
    fun `the tab row scrolls with the drag, not after it`() {
        show(ListFixtureStates.completed)
        val rest = left(completedRow)
        val start = label("Completed").left.value
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.5f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        val held = label("Completed").left.value
        val position = pagePosition(completedRow, rest)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(SETTLED)
        val end = label("Completed").left.value
        tab("On hold").assertIsSelected()
        assertTrue("the row scrolls on the way to On hold: $start, $end", end < start - TOLERANCE)
        assertTrue("held at $position the row has moved: $start > $held >= $end", held < start - TOLERANCE && held >= end - TOLERANCE)
    }

    @Test
    fun `a short slow drag turns the page`() {
        show(ListFixtureStates.watching)
        compose.onRoot().performTouchInput {
            down(center)
            repeat(SLOW_STEPS) { moveBy(Offset(-width * SHORT_DRAG / SLOW_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        tab("Planned").assertIsSelected()
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned)), events)
    }

    @Test
    fun `a tapped far tab is selected at once and the page and the header arrive together`() {
        show(ListFixtureStates.watching)
        val rest = left(watchingRow)
        tab("Dropped").performClick()
        var elapsed = 0L
        while (elapsed < TAB_SWITCH_MS + FRAME) {
            compose.mainClock.advanceTimeByFrame()
            elapsed += FRAME
            tab("Dropped").assertIsSelected()
            listOf("Watching", "Planned", "Completed", "On hold").forEach { tab(it).assertIsNotSelected() }
        }
        assertIndicatorAt("Dropped", "Dropped", 0f)
        assertEquals("the page has arrived with the header", rest, left(droppedRow), TOLERANCE)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Dropped)), events)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIndicatorAt("Dropped", "Dropped", 0f)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Dropped)), events)
        assertIdle()
    }

    // A far page jumps next to its tab at once and slides the last page in.
    private fun assertOneCurve(
        tab: String,
        row: String,
    ) {
        show(ListFixtureStates.watching)
        val rest = left(watchingRow)
        tab(tab).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS / 3)
        val page = (rest + pageWidth - left(row)) / pageWidth
        val a = label("Watching").left.value
        val b = label(tab).left.value
        val header = (indicator().left.value - a) / (b - a)
        assertTrue("part-way: $page", page in 0.1f..0.95f)
        assertTrue("the header at $header, the page at $page", abs(header - page) <= CURVE_TOLERANCE)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIndicatorAt(tab, tab, 0f)
        assertIdle()
    }

    @Test
    fun `on a tap the indicator and the page run one curve`() = assertOneCurve("Planned", plannedRow)

    @Test
    fun `on a tap on a far tab the indicator and the last page run one curve`() = assertOneCurve("Dropped", droppedRow)

    @Test
    fun `tapping the selected tab moves nothing`() {
        show(ListFixtureStates.watching)
        val before = indicator()
        tab("Watching").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(before, indicator())
        assertIdle()
    }

    private fun assertIdle() {
        compose.mainClock.advanceTimeByFrame()
        assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    private companion object {
        val TAB_SWITCH_MS = Motion.Duration.MEDIUM2.toLong()
        const val FRAME = 16L
        const val SETTLED = 2_000L
        const val MAX_FRAMES = 60
        const val SETTLE_EPSILON = 0.001f
        const val TOLERANCE = 1.5f
        const val CURVE_TOLERANCE = 0.08f
        const val SHORT_DRAG = 0.38f
        const val SLOW_STEPS = 20
        const val SLOW_STEP_MS = 50L
    }
}
