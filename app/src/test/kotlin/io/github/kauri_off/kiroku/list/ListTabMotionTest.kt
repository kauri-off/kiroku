package io.github.kauri_off.kiroku.list

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.math.roundToInt
import kotlin.math.sign

// The tab row is drawn from the pager's position: through a drag, its settle and a tap.
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

    private val labels = listOf("Watching", "Planned", "Completed", "On hold", "Dropped")

    private val firstRows =
        listOf(
            ListFixtureStates.watching,
            ListFixtureStates.planned,
            ListFixtureStates.completed,
            ListFixtureStates.onHold,
            ListFixtureStates.dropped,
        ).map {
            it.page!!
                .rows
                .first()
                .title
        }

    private lateinit var scheme: ColorScheme

    // Follows the selected tab as the view model does.
    private fun show(start: ListState) {
        state = start
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                scheme = MaterialTheme.colorScheme
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

    private fun exists(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    // The pager's place, from the first row of a page that is composed; [rest] is a row's left on a page at rest.
    private fun pagerPosition(rest: Float): Float {
        val page = firstRows.indices.first { exists(firstRows[it]) }
        return page + (rest - left(firstRows[page])) / pageWidth
    }

    private fun assertIndicatorAt(position: Float) {
        val from = position.toInt().coerceIn(0, labels.lastIndex)
        assertIndicatorAt(labels[from], labels[minOf(from + 1, labels.lastIndex)], position - from)
        assertRowAt(position)
    }

    // The row's scroll, from the first tab's place on screen: the row starts on the content edge.
    private fun rowScroll(): Float = -bounds(tab(labels[0])).left.value

    // The scroll the row must have at the pager's [position], worked out from the tabs' places alone: at a whole
    // page it centres that page's tab where the row can scroll that far, between two it blends the two. A scroll by
    // [hand] made at [handAt] fades out over one page of the pager's travel.
    private fun expectedScroll(
        position: Float,
        hand: Float = 0f,
        handAt: Float = 0f,
    ): Float {
        val scroll = rowScroll()
        val tabs = labels.map { bounds(tab(it)) }
        val lefts = tabs.map { it.left.value + scroll }
        val max = (tabs.last().right.value + scroll - pageWidth).coerceAtLeast(0f)

        fun centring(index: Int) = (lefts[index] - (pageWidth / 2 - tabs[index].width.value / 2)).coerceIn(0f, max)
        val from = position.toInt().coerceIn(0, labels.lastIndex)
        val to = minOf(from + 1, labels.lastIndex)
        val f = position - from
        val pager = centring(from) + (centring(to) - centring(from)) * f
        return (pager + hand * (1f - abs(position - handAt)).coerceAtLeast(0f)).coerceIn(0f, max)
    }

    private fun assertRowAt(
        position: Float,
        hand: Float = 0f,
        handAt: Float = 0f,
    ) {
        val expected = expectedScroll(position, hand, handAt)
        assertEquals("the row's scroll at the pager's $position", expected, rowScroll(), TOLERANCE)
    }

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
        assertRowAt(past)
        tab("Planned").assertIsSelected()
        tab("Watching").assertIsNotSelected()
        assertTrue("nothing is selected in the view model while the finger is down", events.isEmpty())
        compose.onRoot().performTouchInput { up() }
        var frames = 0
        while (pagePosition(watchingRow, rest) < 1f - SETTLE_EPSILON && frames++ < MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            assertIndicatorAt(pagePosition(watchingRow, rest).coerceIn(0f, 1f))
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

    // The header is drawn from the pager: a far page is put next to its tab at once, and the header goes with it.
    private fun assertHeaderOnThePage(tab: String) {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        tab(tab).performClick()
        compose.mainClock.advanceTimeByFrame()
        // The spring does most of its way early: a frame well inside the slide.
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS / 8)
        val page = pagerPosition(rest)
        val target = labels.indexOf(tab).toFloat()
        assertTrue("part-way: $page", page > target - 1f + 0.1f && page < target - 0.05f)
        assertIndicatorAt(page)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIndicatorAt(tab, tab, 0f)
        assertIdle()
    }

    @Test
    fun `on a tap the indicator is where the page is`() = assertHeaderOnThePage("Planned")

    // The header's place in tabs, read from the indicator against the labels it blends between.
    private fun headerPosition(): Float {
        val left = indicator().left.value
        val lefts = labels.map { label(it).left.value }
        val from = (0 until labels.lastIndex).lastOrNull { lefts[it] <= left + 0.01f } ?: 0
        return (from + (left - lefts[from]) / (lefts[from + 1] - lefts[from])).coerceIn(0f, labels.lastIndex.toFloat())
    }

    // Telegram's far tap: one page slides, and the header glides from the tab it left to the tapped one by its progress.
    @Test
    fun `on a tap on a far tab the header glides the whole way with the one sliding page`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        val target = labels.lastIndex.toFloat()
        tab("Dropped").performClick()
        var last = 0f
        var moving = 0
        repeat(MAX_FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val page = pagerPosition(rest)
            val header = headerPosition()
            val shown = indicator()
            assertTrue(
                "frame $frame: the indicator ${shown.left}..${shown.right} off the row",
                shown.left.value >= 0f && shown.right.value <= pageWidth,
            )
            assertTrue("frame $frame: the header went back from $last to $header", header >= last - GLIDE_TOLERANCE)
            val progress = (page - (target - 1f)).coerceIn(0f, 1f)
            assertEquals("frame $frame: the header at the page's progress $progress", target * progress, header, GLIDE_TOLERANCE)
            if (header > last + GLIDE_TOLERANCE) moving++
            last = header
        }
        assertTrue("the glide took $moving frames", moving >= GLIDE_FRAMES)
        assertEquals(target, last, GLIDE_TOLERANCE)
        assertEquals("the page has arrived with the header", rest, left(droppedRow), TOLERANCE)
        assertIndicatorAt("Dropped", "Dropped", 0f)
        assertIdle()
    }

    // A release's speed sets the settle's length: a fast flick ends sooner than a release with the finger at rest.
    @Test
    fun `a fast flick settles sooner than a still release over the same way`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])

        fun settleFrames(
            to: Int,
            release: TouchInjectionScope.() -> Unit,
        ): Int {
            compose.onRoot().performTouchInput(release)
            var frames = 0
            while (pagerPosition(rest) < to - SETTLE_EPSILON && frames < MAX_FRAMES) {
                compose.mainClock.advanceTimeByFrame()
                frames++
            }
            compose.mainClock.advanceTimeBy(SETTLED)
            tab(labels[to]).assertIsSelected()
            return frames
        }
        val flick =
            settleFrames(1) {
                down(center)
                moveBy(Offset(-width * FLICK, 0f), delayMillis = FRAME)
                moveBy(Offset(-width * FLICK, 0f), delayMillis = FRAME)
                up()
            }
        val still =
            settleFrames(2) {
                down(center)
                repeat(SLOW_STEPS) { moveBy(Offset(-width * FLICK * 2 / SLOW_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
                repeat(SLOW_STEPS) { moveBy(Offset.Zero, delayMillis = SLOW_STEP_MS) }
                up()
            }
        assertTrue("a flick settles in $flick frames, a still release in $still", flick * FRAME <= FLICK_SETTLE_MS && still > flick + 2)
    }

    // The stock row's own scroll to a newly selected tab never runs against the finger.
    @Test
    fun `a drag held just past half-way leaves the row still, and on release the row moves one way only`() {
        show(ListFixtureStates.completed)
        val rest = left(firstRows[2])
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.3f, 0f))
            moveBy(Offset(-width * 0.3f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        val held = pagerPosition(rest)
        assertTrue("just past half-way: $held", held > 2.5f && held < 2.75f)
        tab("On hold").assertIsSelected()
        val row = label("Completed").left.value
        val bar = indicator().left.value
        repeat(HOLD_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("the row is still while the finger is", row, label("Completed").left.value, STILL)
            assertEquals("the indicator is still while the finger is", bar, indicator().left.value, STILL)
        }
        compose.onRoot().performTouchInput { up() }
        var last = row
        repeat(MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val now = label("Completed").left.value
            assertTrue("the row only moves on towards On hold: $last, then $now", now <= last + STILL)
            assertIndicatorAt(pagerPosition(rest))
            last = now
        }
        tab("On hold").assertIsSelected()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(last, label("Completed").left.value, STILL)
        assertIndicatorAt("On hold", "On hold", 0f)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.OnHold)), events)
        assertIdle()
    }

    // A tap that interrupts a settle glides the header from where it is, and it arrives with the page.
    @Test
    fun `a tap during a settle glides the header from where it is and arrives with the page`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.2f, 0f))
            moveBy(Offset(-width * 0.2f, 0f))
            up()
        }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val settling = pagerPosition(rest)
        assertTrue("mid-settle: $settling", settling > 0.05f && settling < 0.95f)
        tab("Completed").performClick()
        val from = headerPosition()
        val jumped = pagerPosition(rest)
        assertTrue(
            "the glide starts where the settle was: $from after $settling",
            from >= settling - GLIDE_TOLERANCE && from <= settling + SETTLE_FRAME,
        )
        assertTrue("the header is not where the pager jumped: $from, $jumped", from < jumped - SETTLE_FRAME)
        var last = from
        repeat(MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            tab("Completed").assertIsSelected()
            val progress = (pagerPosition(rest) - 1f).coerceIn(0f, 1f)
            val header = headerPosition()
            assertEquals("the header at the page's progress $progress", from + (2f - from) * progress, header, GLIDE_TOLERANCE)
            assertTrue("the header went back from $last to $header", header >= last - GLIDE_TOLERANCE)
            last = header
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIndicatorAt("Completed", "Completed", 0f)
        assertEquals(rest, left(firstRows[2]), TOLERANCE)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Completed)), events)
        assertIdle()
    }

    // A drag that catches a far tap's glide takes the pager at once; the header comes back to it with no jump. The
    // slow effects spring (stiffness 800, critically damped) moves at most a sixth of its way in a frame.
    @Test
    fun `a drag that catches a far tap's glide brings the header back to the pager with no jump`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        tab("Dropped").performClick()
        repeat(CATCH_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        var header = headerPosition()
        var page = pagerPosition(rest)
        val gap = abs(page - header)
        assertTrue("caught early in the glide: header $header, pager $page", gap > 1.5f)
        compose.onRoot().performTouchInput { down(center) }
        val steps = mutableListOf<String>()

        fun frame() {
            compose.mainClock.advanceTimeByFrame()
            val nextHeader = headerPosition()
            val nextPage = pagerPosition(rest)
            val step = abs(nextHeader - header)
            steps += "h%.2f p%.2f".format(nextHeader, nextPage)
            assertTrue(
                "a step of $step tabs with the pager's ${abs(nextPage - page)}: $steps",
                step <= abs(nextPage - page) + gap * RETURN_STEP,
            )
            header = nextHeader
            page = nextPage
        }
        repeat(DRAG_FRAMES) {
            compose.onRoot().performTouchInput { moveBy(Offset(width * 0.03f, 0f), delayMillis = 0) }
            frame()
        }
        compose.onRoot().performTouchInput { up() }
        repeat(MAX_FRAMES) { frame() }
        assertEquals("the header is back on the pager", pagerPosition(rest), headerPosition(), GLIDE_TOLERANCE)
        val shown = pagerPosition(rest).roundToInt()
        tab(labels[shown]).assertIsSelected()
        assertIdle()
    }

    // Unselected titles take Material's inactive colour; the two titles the header is between blend with it.
    @Test
    fun `the titles' colours follow the header`() {
        show(ListFixtureStates.watching)
        assertTitleColour("Watching", 1f)
        assertTitleColour("Planned", 0f)
        assertTitleColour("Dropped", 0f)
        compose.onRoot().performTouchInput {
            down(center)
            repeat(SLOW_STEPS) { moveBy(Offset(-width * 0.5f / SLOW_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
        }
        compose.mainClock.advanceTimeByFrame()
        val at = headerPosition()
        assertTitleColour("Watching", 1f - at)
        assertTitleColour("Planned", at)
        assertTitleColour("Completed", 0f)
        compose.onRoot().performTouchInput { up() }
    }

    // The colour most of a title's ink is drawn in, against the blend of the inactive and the active colour by [weight].
    private fun assertTitleColour(
        title: String,
        weight: Float,
    ) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val box = label(title)
        val density = compose.activity.resources.displayMetrics.density
        val background = image.getPixel(0, ((box.bottom.value + 2) * density).toInt())
        val counts = mutableMapOf<Int, Int>()
        for (x in (box.left.value * density).toInt() until (box.right.value * density).toInt()) {
            for (y in (box.top.value * density).toInt() until (box.bottom.value * density).toInt()) {
                if (x in 0 until image.width) image.getPixel(x, y).takeIf { it != background }?.let { counts.merge(it, 1, Int::plus) }
            }
        }
        val ink = Color(counts.maxBy { it.value }.key)
        val expected = lerp(scheme.onSurfaceVariant, scheme.primary, weight)
        for ((name, a, b) in listOf(
            Triple("red", ink.red, expected.red),
            Triple("green", ink.green, expected.green),
            Triple("blue", ink.blue, expected.blue),
        )) {
            assertEquals("$title's $name at weight $weight", b, a, COLOUR_TOLERANCE)
        }
    }

    // A drag that takes over from a tap sends no tab but the tapped one and the one the drag settles on.
    private fun dragAfterTap(
        drag: Float,
        settles: ListStatus,
    ) {
        show(ListFixtureStates.watching)
        tab("Planned").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MID_TAP_MS)
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(width * drag, 0f))
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned), ListEvent.SelectTab(settles)), events)
        tab(labels[settles.ordinal]).assertIsSelected()
        assertIndicatorAt(labels[settles.ordinal], labels[settles.ordinal], 0f)
        assertIdle()
    }

    @Test
    fun `a drag on during a tap ends on the tab it settles on`() = dragAfterTap(-0.6f, ListStatus.Completed)

    @Test
    fun `a drag back during a tap ends on the tab it settles on`() = dragAfterTap(0.6f, ListStatus.Watching)

    // Every frame of a drag and its settle the row has the pager's scroll; on a tap it goes straight to the tab's place.
    @Test
    fun `the row has the pager's scroll through a drag and goes straight to a tapped tab by the glide`() {
        show(ListFixtureStates.planned)
        val rest = left(firstRows[1])
        assertRowAt(1f)
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.2f, 0f))
        }
        repeat(HOLD_FRAMES) {
            compose.onRoot().performTouchInput { moveBy(Offset(-width * 0.02f, 0f)) }
            compose.mainClock.advanceTimeByFrame()
            assertRowAt(pagerPosition(rest))
        }
        compose.onRoot().performTouchInput { up() }
        repeat(MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            assertRowAt(pagerPosition(rest))
        }
        // A tap's header glides; the row goes straight from where it was to the tab's place, by the glide's progress.
        tapRowFrames(target = 0)
        assertRowAt(pagerPosition(rest))
        tapRowFrames(target = 4)
        assertRowAt(4f)
        assertIdle()
    }

    private fun tapRowFrames(target: Int) {
        val from = headerPosition()
        val start = rowScroll()
        tab(labels[target]).performClick()
        val to = expectedScroll(target.toFloat())
        repeat(MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val progress = (headerPosition() - from) / (target - from)
            assertEquals("the row at the tap's progress $progress", start + (to - start) * progress, rowScroll(), TOLERANCE)
        }
    }

    // A row scrolled by hand goes back to the pager's scroll over the page's own move, not in one frame.
    @Test
    fun `the row scrolls by hand and goes back to the pager's scroll as the page moves`() {
        show(ListFixtureStates.completed)
        val rest = left(firstRows[2])
        val pagerScroll = rowScroll()
        assertTrue("the row can scroll back: $pagerScroll", pagerScroll > HAND)
        tab("Completed").performTouchInput {
            down(center)
            repeat(SLOW_STEPS) { moveBy(Offset(HAND.dp.toPx() / SLOW_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
            advanceEventTime(SLOW_STEP_MS * SLOW_STEPS)
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        val byHand = rowScroll()
        assertTrue("scrolled by hand: $pagerScroll, then $byHand", byHand < pagerScroll - TOLERANCE)
        tab("Completed").assertIsSelected()
        repeat(HOLD_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("stays where the hand left it while the page rests", byHand, rowScroll(), STILL)
        }
        val hand = byHand - pagerScroll
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.1f, 0f))
        }
        repeat(HOLD_FRAMES) {
            compose.onRoot().performTouchInput { moveBy(Offset(-width * 0.02f, 0f)) }
            compose.mainClock.advanceTimeByFrame()
            assertRowAt(pagerPosition(rest), hand, handAt = 2f)
        }
        compose.onRoot().performTouchInput { up() }
        repeat(MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            assertRowAt(pagerPosition(rest), hand, handAt = 2f)
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        tab("On hold").assertIsSelected()
        assertRowAt(3f)
        tab("Completed").performClick()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals("no scroll by hand is left once the page has moved on", pagerScroll, rowScroll(), TOLERANCE)
        assertIdle()
    }

    private fun rowMax(): Float = bounds(tab(labels.last())).right.value + rowScroll() - pageWidth

    // Drags the tab row by hand past its end; the selected first tab is then off the row.
    private fun scrollRowToEnd() {
        tab("Planned").performTouchInput {
            down(center)
            repeat(SLOW_STEPS) { moveBy(Offset(-width * END_DRAG / SLOW_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
            advanceEventTime(SLOW_STEP_MS * SLOW_STEPS)
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals("scrolled by hand to the end", rowMax(), rowScroll(), TOLERANCE)
        assertTrue("the selected tab's title is off the row", label("Watching").right.value <= 0f)
    }

    // The scroll the row rests at on a tab, in whole pixels as the app works it out.
    private fun place(index: Int): Float {
        val density = compose.density.density

        fun px(dp: Dp) = (dp.value * density).roundToInt()
        val scroll = (rowScroll() * density).roundToInt()
        val tabs = labels.map { bounds(tab(it)) }
        val viewport = (pageWidth * density).roundToInt()
        val max = (px(tabs.last().right) + scroll - viewport).coerceAtLeast(0)
        val width = px(tabs[index].right) - px(tabs[index].left)
        return (px(tabs[index].left) + scroll - (viewport / 2 - width / 2)).coerceIn(0, max) / density
    }

    // Frame by frame, the row goes from [from] towards [to] and never back, ending there with the page on [target].
    private fun assertRowMovesOnce(
        from: Float,
        target: Int,
        rest: Float,
    ): Int {
        val to = place(target)
        var last = from
        var moving = 0
        repeat(MAX_FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val now = rowScroll()
            assertTrue("frame $frame: the row at $now, outside $from..$to", now in minOf(from, to) - STILL..maxOf(from, to) + STILL)
            assertTrue("frame $frame: the row went from $last to $now, away from $to", (now - last) * sign(to - from) >= -STILL)
            if (abs(target - pagerPosition(rest)) > ARRIVING) {
                assertTrue("frame $frame: the row at $now has arrived before the page", from == to || abs(now - to) > TOLERANCE)
            }
            if (abs(now - last) > STILL) moving++
            last = now
        }
        assertEquals("the page has arrived", target.toFloat(), pagerPosition(rest), SETTLE_EPSILON)
        assertEquals("the row has arrived with it", to, rowScroll(), STILL)
        tab(labels[target]).assertIsSelected()
        return moving
    }

    // The owner's case: the row scrolled by hand to its end, a tap on a tab moves it once, from where it is shown to
    // the tab's place, with the page's slide; already there, it does not move.
    private fun tapFromScrolledRow(target: String): Int {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        scrollRowToEnd()
        val from = rowScroll()
        tab(target).performClick()
        val moving = assertRowMovesOnce(from, labels.indexOf(target), rest)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertRowAt(labels.indexOf(target).toFloat())
        assertIdle()
        return moving
    }

    @Test
    @Config(qualifiers = NARROW)
    fun `a tap on a far tab after the row was scrolled by hand moves the row once, towards the tab`() {
        val moving = tapFromScrolledRow("Completed")
        assertTrue("the row moved in $moving frames", moving >= GLIDE_FRAMES)
    }

    // At the owner's width, with the wider tabs of a large font, where the selected title is off the row too.
    @Test
    @Config(qualifiers = "en-w411dp-h2000dp", fontScale = LARGE_TEXT)
    fun `at the phone's width with large text a tap on a far tab after a scroll by hand moves the row once`() {
        val moving = tapFromScrolledRow("Completed")
        assertTrue("the row moved in $moving frames", moving >= GLIDE_FRAMES)
    }

    // A scroll by hand during a tap's movement moves the row on top of it, for the rest of the tap and after it.
    @Test
    @Config(qualifiers = NARROW)
    fun `a scroll by hand during a tap is kept on top of the tap's movement`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        val start = rowScroll()
        tab("Completed").performClick()
        compose.mainClock.advanceTimeByFrame()
        val to = place(2)

        fun tapped() = start + (to - start) * headerPosition() / 2f
        // Moves with no delay, which would advance the clock past the tap; the finger is lifted once the tap is over.
        tab("On hold").performTouchInput {
            down(center)
            repeat(SLOW_STEPS) { moveBy(Offset(-HAND.dp.toPx() / SLOW_STEPS, 0f), delayMillis = 0) }
        }
        val hand = rowScroll() - tapped()
        assertTrue("the row moved with the finger: $hand", hand > TOLERANCE)
        assertTrue("still mid-tap: ${headerPosition()}", headerPosition() < 1f)
        repeat(MAX_FRAMES) { frame ->
            compose.mainClock.advanceTimeByFrame()
            assertEquals("frame $frame: the tap's movement and the hand", tapped() + hand, rowScroll(), TOLERANCE)
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        tab("On hold").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(2f, pagerPosition(rest), SETTLE_EPSILON)
        assertEquals("the hand is kept after the tap", to + hand, rowScroll(), TOLERANCE)
        assertIdle()
    }

    @Test
    @Config(qualifiers = NARROW)
    fun `a tap on the next tab after the row was scrolled by hand moves the row once, towards the tab`() {
        tapFromScrolledRow("Planned")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun `a tap on a tab the row is already at after a scroll by hand leaves the row where it is`() {
        assertEquals(0, tapFromScrolledRow("Dropped"))
    }

    @Test
    @Config(qualifiers = NARROW)
    fun `a second tap during the row's movement moves it on from where it is to the second tab`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        scrollRowToEnd()
        tab("Completed").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS / 8)
        val from = rowScroll()
        assertTrue(
            "part-way: $from in ${expectedScroll(2f)}..${rowMax()}",
            from < rowMax() - TOLERANCE && from > expectedScroll(2f) + TOLERANCE,
        )
        tab("Watching").performClick()
        assertRowMovesOnce(from, 0, rest)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertRowAt(0f)
        assertEquals(ListStatus.Watching, state.tab)
        assertIdle()
    }

    // The row comes back to the pager with the header, on the slow effects spring: no frame jumps.
    @Test
    @Config(qualifiers = NARROW)
    fun `a drag that catches a tap after a scroll by hand brings the row back to the pager with no jump`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        scrollRowToEnd()
        tab("Completed").performClick()
        repeat(CATCH_FRAMES + 2) { compose.mainClock.advanceTimeByFrame() }
        var header = headerPosition()
        var row = rowScroll()
        val gap = abs(row - expectedScroll(header))
        assertTrue("caught with the row away from the pager's scroll: $gap", gap > HAND)
        compose.onRoot().performTouchInput { down(center) }
        val steps = mutableListOf<String>()

        fun frame() {
            compose.mainClock.advanceTimeByFrame()
            val nextHeader = headerPosition()
            val nextRow = rowScroll()
            steps += "h%.2f r%.1f".format(nextHeader, nextRow)
            val pagers = abs(expectedScroll(nextHeader) - expectedScroll(header))
            assertTrue(
                "a step of ${abs(nextRow - row)} with the pager's $pagers: $steps",
                abs(nextRow - row) <= pagers + gap * RETURN_STEP + TOLERANCE,
            )
            header = nextHeader
            row = nextRow
        }
        repeat(DRAG_FRAMES) {
            compose.onRoot().performTouchInput { moveBy(Offset(width * 0.03f, 0f), delayMillis = 0) }
            frame()
        }
        compose.onRoot().performTouchInput { up() }
        repeat(MAX_FRAMES) { frame() }
        val page = pagerPosition(rest)
        assertEquals("the page has settled", page.roundToInt().toFloat(), page, SETTLE_EPSILON)
        assertRowAt(page)
        tab(labels[page.roundToInt()]).assertIsSelected()
        assertIdle()
    }

    // The pager rests for a moment between the drag and the fling; the tab it reports then does not pull it back.
    @Test
    fun `a drag past half-way flicked back ends on the page it started from`() {
        show(ListFixtureStates.watching)
        val rest = left(firstRows[0])
        compose.onRoot().performTouchInput {
            down(center)
            repeat(SLOW_STEPS) { moveBy(Offset(-width * 0.65f / SLOW_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
            repeat(3) { moveBy(Offset(width * 0.05f, 0f), delayMillis = FRAME) }
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals("$events", 0f, pagerPosition(rest), SETTLE_EPSILON)
        tab("Watching").assertIsSelected()
        assertEquals(ListStatus.Watching, state.tab)
        assertIndicatorAt("Watching", "Watching", 0f)
        assertIdle()
    }

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
        const val TAB_SWITCH_MS = MotionBounds.ENTER
        const val MID_TAP_MS = 100L
        const val FRAME = 16L
        const val SETTLED = 2_000L
        const val MAX_FRAMES = 60
        const val SETTLE_EPSILON = 0.001f
        const val TOLERANCE = 1.5f
        const val HOLD_FRAMES = 30
        const val STILL = 0.01f
        const val SHORT_DRAG = 0.38f
        const val SLOW_STEPS = 20
        const val SLOW_STEP_MS = 50L
        const val HAND = 24f
        const val GLIDE_TOLERANCE = 0.02f
        const val SETTLE_FRAME = 0.15f
        const val CATCH_FRAMES = 2
        const val DRAG_FRAMES = 12
        const val RETURN_STEP = 1f / 6
        const val COLOUR_TOLERANCE = 0.02f
        const val GLIDE_FRAMES = 6
        const val FLICK = 0.2f
        const val FLICK_SETTLE_MS = 220L
        const val END_DRAG = 4f
        const val NARROW = "en-w360dp-h2000dp"
        const val LARGE_TEXT = 2f
        const val ARRIVING = 0.1f
    }
}

// With the system's animations off, a tap on a far tab puts the page, the indicator and the row there in one frame.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class ListTabMotionOffTest {
    private val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        )

    @get:Rule
    val rule: TestRule = registered(compose)

    // With animations off, the row was scrolled a frame after the indicator was placed.
    @Test
    fun `a far tap never shows the indicator off the row`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(ListFixtureStates.watching) } }
        compose.mainClock.advanceTimeBy(2_000)
        val width = compose.onRoot().getUnclippedBoundsInRoot().width
        compose.onNode(hasText("Dropped") and isSelectable()).performClick()
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            val shown = compose.onNodeWithTag(INDICATOR, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("frame $it: ${shown.left}..${shown.right} in 0..$width", shown.left >= 0.dp && shown.right <= width)
        }
        val label = compose.onAllNodesWithText("Dropped", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        val shown = compose.onNodeWithTag(INDICATOR, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(label.left.value, shown.left.value, 1.5f)
    }

    // The row scrolled by hand to its end, a tap on a far tab puts the row at that tab's place with the page.
    @Test
    fun `a tap after a scroll by hand puts the row at the tab at once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(ListFixtureStates.watching) } }
        compose.mainClock.advanceTimeBy(2_000)
        val screen =
            compose
                .onRoot()
                .getUnclippedBoundsInRoot()
                .width.value

        fun tab(text: String) = compose.onNode(hasText(text) and isSelectable())

        fun bounds(text: String) = tab(text).getUnclippedBoundsInRoot()
        tab("Planned").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-screen.dp.toPx() / 10, 0f), delayMillis = 50) }
            up()
        }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals("scrolled by hand to the end", screen, bounds("Dropped").right.value, 1.5f)
        tab("Completed").performClick()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val completed = bounds("Completed")
        val scroll = -bounds("Watching").left.value
        val max = bounds("Dropped").right.value + scroll - screen
        val centring = (completed.left.value + scroll - (screen - completed.width.value) / 2).coerceIn(0f, max)
        assertEquals(centring, scroll, 1.5f)
        tab("Completed").assertIsSelected()
    }

    // ARCHITECTURE 8.5: with animations off a released swipe is on its page at once.
    @Test
    fun `a released swipe is on its page at once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(ListFixtureStates.watching) } }
        compose.mainClock.advanceTimeBy(2_000)
        val planned =
            ListFixtureStates.planned.page!!
                .rows
                .first()
                .title
        val rest = compose.onNodeWithText(FixtureAnime.estimatedCaughtUp.titles.romaji).getUnclippedBoundsInRoot().left
        compose.onRoot().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width * 0.04f, 0f), delayMillis = 50) }
            up()
        }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(
            rest.value,
            compose
                .onNodeWithText(planned)
                .getUnclippedBoundsInRoot()
                .left.value,
            1.5f,
        )
        compose.onNode(hasText("Planned") and isSelectable()).assertIsSelected()
    }
}

// The view model hears of each page the pager rests on, and a tab it hears back is not a request to move the pager.
@RunWith(RobolectricTestRunner::class)
class ListTabFollowTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    // A fling starts a moment after the drag that threw it ends, so the pager can rest on a page in between.
    // A tap that waits for the pages to be laid out does not move the pager once a later tap has.
    @Test
    fun `of two taps before every page is laid out only the later one moves the pager`() {
        var laidOut by mutableStateOf(false)
        lateinit var pager: PagerState
        lateinit var motion: ListTabMotion
        compose.setContent {
            pager = rememberPagerState { 5 }
            motion = rememberListTabMotion(pager)
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = if (laidOut) motion.ahead else 1) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.runOnIdle {
            motion.select(4)
            motion.select(1)
        }
        compose.waitForIdle()
        compose.runOnIdle { laidOut = true }
        compose.waitForIdle()
        assertEquals(1, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction, 0.001f)
    }

    @Test
    fun `a tab reported in a pause before a fling does not pull the pager back to it`() {
        var tab by mutableIntStateOf(0)
        val reports = mutableListOf<Int>()
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        compose.mainClock.autoAdvance = false
        compose.setContent {
            pager = rememberPagerState { 5 }
            scope = rememberCoroutineScope()
            val motion = rememberListTabMotion(pager)
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = motion.ahead) { Box(Modifier.fillMaxSize()) }
            LaunchedEffect(motion) { motion.followRest { reports += it } }
            LaunchedEffect(tab) { motion.show(tab) }
        }
        compose.mainClock.advanceTimeBy(SETTLED)
        compose.runOnIdle { scope.launch { pager.scrollToPage(1) } }
        compose.mainClock.advanceTimeBy(FRAMES)
        assertEquals(listOf(0, 1), reports)
        compose.runOnIdle { scope.launch { pager.animateScrollToPage(0) } }
        compose.mainClock.advanceTimeBy(FRAMES)
        assertTrue(pager.isScrollInProgress)
        // The view model's answer to the report reaches the screen while the fling runs.
        compose.runOnIdle { tab = 1 }
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(0, pager.currentPage)
        assertEquals(listOf(0, 1, 0), reports)
    }

    @Test
    fun `a tab chosen elsewhere moves the pager to it`() {
        var tab by mutableIntStateOf(0)
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState { 5 }
            val motion = rememberListTabMotion(pager)
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = motion.ahead) { Box(Modifier.fillMaxSize()) }
            LaunchedEffect(motion) { motion.followRest { tab = it } }
            LaunchedEffect(tab) { motion.show(tab) }
        }
        compose.runOnIdle { tab = 3 }
        compose.waitForIdle()
        assertEquals(3, pager.currentPage)
    }

    private companion object {
        const val SETTLED = 2_000L
        const val FRAMES = 48L
    }
}
