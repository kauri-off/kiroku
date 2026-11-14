package io.github.kauri_off.kiroku.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.width
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.design.theme.settleMillis
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@Serializable
internal data class TransitionPage(
    val n: Int,
) : NavKey

// Every root and page: its name, a way deeper and a long list whose scroll its saved state keeps.
internal val transitionEntries: ShellEntries = { navigator, _ ->
    entry<ListRoot> { Screen("List root", navigator, TransitionPage(1)) }
    entry<SearchRoot> { Screen("Search root", navigator, TransitionPage(2)) }
    entry<ProfileRoot> { Screen("Profile root", navigator, TransitionPage(3)) }
    entry<TransitionPage> { page -> Screen("Page ${page.n}", navigator, TransitionPage(page.n + 10)) }
}

@Composable
private fun Screen(
    name: String,
    navigator: ShellNavigator,
    next: NavKey,
) = Column {
    Text(name)
    Button(onClick = { navigator.open(next) }) { Text("Open from $name") }
    LazyColumn { items(ROWS) { Text("$name, row $it") } }
}

private const val ROWS = 60

internal class ShellDriver(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    lateinit var navigator: ShellNavigator
    private lateinit var motionScheme: MotionScheme

    lateinit var colors: ColorScheme
    private lateinit var inputModes: InputModeManager

    private var shown = false

    fun show(
        direction: LayoutDirection = LayoutDirection.Ltr,
        entries: ShellEntries = transitionEntries,
    ) {
        shown = true
        compose.mainClock.autoAdvance = false
        compose.setContent {
            navigator = rememberShellNavigator()
            KirokuTheme(dark = false, dynamicColor = false) {
                motionScheme = MaterialTheme.motionScheme
                colors = MaterialTheme.colorScheme
                inputModes = LocalInputModeManager.current
                CompositionLocalProvider(LocalLayoutDirection provides direction) { ShellRoute(navigator, entries = entries) }
            }
        }
        settle()
    }

    // A change settles as the theme's slow effects spring does over its slide, plus the frame it ends in.
    val screenMillis: Long get() = motionScheme.slowEffectsSpec<Float>().settleMillis(1f) + FRAME

    // Back slides the page a window's width, in short slides.
    val backMillis: Long get() = motionScheme.slowEffectsSpec<Float>().settleMillis(windowSlides) + FRAME

    private val windowSlides: Float
        get() =
            compose
                .onRoot()
                .getUnclippedBoundsInRoot()
                .width.value / Spacing.xxl.value

    fun settle() = frames((SETTLED / FRAME).toInt())

    // A test ends with nothing in flight, so no frame work is left to the next one.
    fun finish() {
        if (shown) settle()
    }

    // Frame by frame, letting the work each frame starts run before the next, as on a device.
    fun frames(count: Int) =
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

    fun millis(time: Long) = frames(((time + FRAME - 1) / FRAME).toInt())

    fun open(from: String) = compose.onNode(hasText("Open from $from") and hasClickAction()).performClick()

    fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()

    fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    fun gesture(
        progress: Float,
        edge: Int = BackEventCompat.EDGE_LEFT,
        start: Boolean = true,
    ) = compose.runOnUiThread {
        val dispatcher = compose.activity.onBackPressedDispatcher
        if (start) dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, progress, edge))
    }

    fun cancel() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }

    fun release() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    fun exists(name: String) = compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()

    fun left(name: String) =
        compose
            .onNodeWithText(name)
            .getUnclippedBoundsInRoot()
            .left.value

    fun scrollTo(
        name: String,
        index: Int,
    ) {
        compose.onNode(hasScrollToIndexAction() and hasAnyChild(hasText("$name, row", substring = true))).performScrollToIndex(index)
        settle()
    }

    fun image(): ImageBitmap = compose.onRoot().captureToImage()

    // The most the theme's slow effects spring moves a page across [width] pixels in one frame.
    fun frameTravel(width: Int): Float {
        val spring = motionScheme.slowEffectsSpec<Float>().vectorize(Float.VectorConverter)
        val at = { millis: Long -> spring.getValueFromNanos(millis * NANOS, ZERO, ONE, ZERO).value }
        return (0L..SETTLED step FRAME).maxOf { (at(it + FRAME) - at(it)) * width }
    }

    fun pressKey(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    fun useKeyboard() = compose.runOnUiThread { inputModes.requestInputMode(InputMode.Keyboard) }

    fun assertIdle() {
        settle()
        assertTrue("an animation is still pending", Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    companion object {
        const val SETTLED = 2_000L
        const val FRAME = 16L
        const val NANOS = 1_000_000L
        val ZERO = AnimationVector1D(0f)
        val ONE = AnimationVector1D(1f)
    }
}

internal fun ImageBitmap.samePixels(other: ImageBitmap): Boolean = differences(other) == null

// The colour at the middle of the screen.
internal fun ImageBitmap.middle(): Color = toPixelMap()[width / 2, height / 2]

// The colours across the middle of the screen.
internal fun ImageBitmap.row(): List<Color> = toPixelMap().let { map -> (0 until width).map { map[it, height / 2] } }

// Where the page's colour starts and ends across the middle, as pixels from the left; null when it is not there.
internal fun List<Color>.span(page: Color): IntRange? {
    val first = indexOfFirst { it == page }
    return if (first < 0) null else first..indexOfLast { it == page }
}

// The middle row shows the two screens side by side, never one through the other: only their colours, and at
// most a pixel of edge between them.
internal fun List<Color>.sideBySide(
    list: Color,
    page: Color,
): Boolean = count { it != list && it != page } <= 1

// The List and a page as two plain colours of the theme, so a mix of the two can be measured.
internal val colouredEntries: ShellEntries = { _, _ ->
    entry<ListRoot> { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary)) }
    entry<SearchRoot> { Box(Modifier.fillMaxSize()) }
    entry<ProfileRoot> { Box(Modifier.fillMaxSize()) }
    entry<TransitionPage> { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiary)) }
}

// Where two images differ, or null when they are the same.
internal fun ImageBitmap.differences(other: ImageBitmap): String? {
    val a = toPixelMap()
    val b = other.toPixelMap()
    if (a.width != b.width || a.height != b.height) return "${a.width}×${a.height} and ${b.width}×${b.height}"
    val differing = a.buffer.indices.filter { a.buffer[it] != b.buffer[it] }
    if (differing.isEmpty()) return null
    val xs = differing.map { it % a.width }
    val ys = differing.map { it / a.width }
    return "${differing.size} pixels differ in x ${xs.min()}..${xs.max()}, y ${ys.min()}..${ys.max()}"
}

// ARCHITECTURE 8.5: the screen transitions and the back gesture that shows where Back leads.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class ShellTransitionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val shell = ShellDriver(compose)

    @After
    fun finish() = shell.finish()

    private fun arrive() {
        shell.frames(START_FRAMES)
        shell.millis(shell.screenMillis)
    }

    private fun opens(
        from: String,
        page: String,
    ) {
        shell.open(from)
        shell.settle()
        assertTrue("$from took no tap", shell.exists(page))
    }

    @Test
    fun `a page opened slides in from the end with the one it covers, and arrives within a screen's time`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.frames(START_FRAMES)
        assertTrue("the page comes from the end", shell.left("Page 1") > rest)
        assertFalse("the leaving List is out of the semantics tree", shell.exists("List root"))
        shell.millis(shell.screenMillis)
        assertEquals(rest, shell.left("Page 1"))
        assertFalse(shell.exists("List root"))
        shell.assertIdle()
    }

    @Test
    fun `Back uncovers the screen under the page in its place, and the page is gone within its time`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.settle()
        shell.back()
        shell.frames(START_FRAMES)
        assertFalse("the leaving page is out of the semantics tree", shell.exists("Page 1"))
        assertEquals("the List is in its place under the page", rest, shell.left("List root"))
        shell.millis(shell.backMillis)
        assertFalse(shell.exists("Page 1"))
        shell.assertIdle()
    }

    @Test
    fun `Back slides the page off opaque toward the end, the List beside it`() {
        shell.show(entries = colouredEntries)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.settle()
        val list = shell.colors.primary
        val page = shell.colors.tertiary
        shell.back()
        var last = -1
        repeat(((START_FRAMES * FRAME + shell.backMillis) / FRAME).toInt()) { frame ->
            shell.frames(1)
            val row = shell.image().row()
            assertTrue("frame $frame shows the screens through each other", row.sideBySide(list, page))
            val start = row.span(page)?.first ?: row.size
            assertTrue("frame $frame: the page went back to $start from $last", start >= last)
            last = start
        }
        assertEquals("the page is gone", null, shell.image().row().span(page))
    }

    @Test
    fun `another destination fades through in place, and Back uncovers the List in its place`() {
        shell.show()
        val rest = shell.left("List root")
        shell.tab("Search")
        shell.frames(START_FRAMES)
        assertEquals(rest, shell.left("Search root"))
        shell.millis(shell.screenMillis)
        assertFalse(shell.exists("List root"))
        shell.assertIdle()
        shell.back()
        shell.frames(START_FRAMES)
        assertEquals(rest, shell.left("List root"))
        shell.millis(shell.backMillis)
        assertFalse(shell.exists("Search root"))
        shell.assertIdle()
    }

    @Test
    fun `Back pressed while a page slides in ends on the screen under it, at rest`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.frames(MID_FLIGHT_FRAMES)
        shell.back()
        shell.frames(START_FRAMES)
        shell.millis(shell.backMillis)
        assertEquals(rest, shell.left("List root"))
        assertFalse(shell.exists("Page 1"))
        shell.assertIdle()
        opens("List root", "Page 1")
    }

    @Test
    fun `a page opened while the screen before slides back ends on the new page, at rest`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.settle()
        shell.back()
        shell.frames(MID_FLIGHT_FRAMES)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.frames(START_FRAMES)
        shell.millis(shell.backMillis)
        assertEquals(rest, shell.left("Page 1"))
        assertFalse(shell.exists("List root"))
        shell.assertIdle()
    }

    // Where the gesture's progress holds, the page is there and opaque, and the screen Back leads to is seen beside it.
    private fun holds(
        edge: Int,
        points: List<Float>,
    ) {
        val list = shell.colors.primary
        val page = shell.colors.tertiary
        val starts = mutableListOf<Int>()
        points.forEachIndexed { index, progress ->
            shell.gesture(progress, edge, start = index == 0)
            shell.frames(START_FRAMES)
            val row = shell.image().row()
            assertTrue("at $progress the screens show through each other", row.sideBySide(list, page))
            val span = checkNotNull(row.span(page)) { "at $progress the page is gone" }
            val moved = if (edge == BackEventCompat.EDGE_LEFT) span.first else row.size - 1 - span.last
            assertEquals("at $progress the page is where the progress puts it", progress * row.size, moved.toFloat(), EDGE_PX)
            val uncovered = if (edge == BackEventCompat.EDGE_LEFT) row[moved / 2] else row[row.size - 1 - moved / 2]
            assertEquals("at $progress the List is seen beside the page", list, uncovered)
            starts += moved
        }
        assertEquals("the page is somewhere else at each hold", starts.size, starts.toSet().size)
        shell.cancel()
        shell.settle()
    }

    @Test
    fun `under a held gesture the page follows its progress opaque, the List beside it, from either edge`() {
        shell.show(entries = colouredEntries)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.settle()
        val points = listOf(0.15f, 0.33f, 0.66f, 0.10f)
        holds(BackEventCompat.EDGE_LEFT, points)
        holds(BackEventCompat.EDGE_LEFT, points)
        holds(BackEventCompat.EDGE_RIGHT, points)
    }

    @Test
    fun `a gesture brought back to the edge and let go leaves the next one following its progress`() {
        shell.show(entries = colouredEntries)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.settle()
        shell.gesture(TWO_THIRDS)
        shell.frames(START_FRAMES)
        shell.gesture(0f, start = false)
        shell.frames(START_FRAMES)
        shell.cancel()
        shell.settle()
        holds(BackEventCompat.EDGE_LEFT, listOf(0.33f, 0.5f))
    }

    // The progress an emulator sent for a slow sweep to 80 % and back, one event a frame: the system brings it home
    // while the finger is still out.
    @Test
    fun `a gesture whose progress the system brings home takes the page home with it, side by side`() {
        shell.show(entries = colouredEntries)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.settle()
        val list = shell.colors.primary
        val page = shell.colors.tertiary
        val width = shell.image().width
        val frame = shell.frameTravel(width)
        var last: Int? = null
        DEVICE_SWEEP.forEachIndexed { index, progress ->
            shell.gesture(progress, start = index == 0)
            shell.frames(if (index == 0) START_FRAMES else 1)
            val row = shell.image().row()
            assertTrue("at $progress the screens show through each other", row.sideBySide(list, page))
            val start = checkNotNull(row.span(page)).first
            assertEquals("at $progress the page follows the system's progress", progress * width, start.toFloat(), EDGE_PX)
            last?.let { assertTrue("a jump of ${abs(start - it)} px at $progress, more than $frame", abs(start - it) <= frame) }
            last = start
        }
        assertEquals("the page is home", 0, last)
        shell.cancel()
        shell.settle()
    }

    // After the release, the page goes on from where it was, one way only, and settles.
    private fun releasedFrom(
        progress: Float,
        commit: Boolean,
    ) {
        shell.show(entries = colouredEntries)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.settle()
        val before = shell.image()
        val list = shell.colors.primary
        val page = shell.colors.tertiary
        shell.gesture(progress)
        shell.frames(START_FRAMES)
        val left = checkNotNull(shell.image().row().span(page)).first
        if (commit) shell.release() else shell.cancel()
        val way = if (commit) shell.image().row().size - left else left
        var last = left
        repeat(((START_FRAMES * FRAME + shell.screenMillis) / FRAME).toInt()) { frame ->
            shell.frames(1)
            val row = shell.image().row()
            assertTrue("frame $frame shows the screens through each other", row.sideBySide(list, page))
            val start = row.span(page)?.first ?: row.size
            assertTrue("frame $frame: from $last to $start", if (commit) start >= last else start <= last)
            if (frame == 0) assertTrue("it jumped from $left to $start", abs(start - left) < way / 2)
            last = start
        }
        if (commit) {
            assertEquals("the page is gone", null, shell.image().row().span(page))
        } else {
            assertEquals("the page is back", null, before.differences(shell.image()))
        }
        shell.assertIdle()
    }

    @Test
    fun `a gesture let go at two thirds goes on from there to the List`() = releasedFrom(TWO_THIRDS, commit = true)

    @Test
    fun `a gesture cancelled at two thirds comes back from there to the page`() = releasedFrom(TWO_THIRDS, commit = false)

    @Test
    fun `a gesture held still stays where its progress is`() {
        shell.show()
        shell.open("List root")
        shell.settle()
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        val held = shell.image()
        shell.settle()
        assertTrue("nothing moves while the progress is still", held.samePixels(shell.image()))
        shell.assertIdle()
        shell.cancel()
        shell.settle()
    }

    @Test
    fun `a cancelled gesture restores the page exactly, its scroll and its stack`() {
        shell.show()
        shell.scrollTo("List root", ROWS_SCROLLED)
        shell.open("List root")
        shell.settle()
        shell.scrollTo("Page 1", ROWS_SCROLLED)
        val before = shell.image()
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            shell.gesture(QUARTER, edge)
            shell.frames(START_FRAMES)
            shell.gesture(HALF, edge, start = false)
            shell.frames(START_FRAMES)
            assertTrue("the List shows under the page", shell.exists("List root, row $ROWS_SCROLLED"))
            shell.cancel()
            arrive()
            assertFalse(shell.exists("List root"))
            assertTrue("edge $edge: the page is drawn as before the gesture", before.samePixels(shell.image()))
            shell.assertIdle()
        }
        opens("Page 1", "Page 11")
        shell.back()
        shell.settle()
        shell.back()
        shell.settle()
        assertTrue("the List kept its scroll", shell.exists("List root, row $ROWS_SCROLLED"))
        assertFalse(shell.exists("List root, row 0"))
    }

    @Test
    fun `a completed gesture finishes the way it went and shows the screen under it as it was`() {
        shell.show()
        shell.scrollTo("List root", ROWS_SCROLLED)
        val list = shell.image()
        shell.open("List root")
        shell.settle()
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        shell.release()
        arrive()
        assertFalse(shell.exists("Page 1"))
        assertTrue("the List is drawn as before the page opened", list.samePixels(shell.image()))
        shell.assertIdle()
    }

    @Test
    fun `a gesture from the root of Search shows the List and a cancelled one keeps Search`() {
        shell.show()
        compose.runOnUiThread { shell.navigator.select(Destination.Search) }
        shell.settle()
        val before = shell.image()
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        assertTrue(shell.exists("List root"))
        shell.cancel()
        arrive()
        assertFalse(shell.exists("List root"))
        assertEquals(null, before.differences(shell.image()))
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        shell.release()
        arrive()
        assertFalse(shell.exists("Search root"))
        shell.assertIdle()
    }

    @Test
    fun `a tap while a page leaves acts on neither screen, and one after it on the screen arrived`() {
        shell.show()
        shell.open("List root")
        shell.settle()
        val button = compose.onNode(hasText("Open from Page 1") and hasClickAction()).getUnclippedBoundsInRoot()
        shell.back()
        shell.frames(START_FRAMES)
        compose.onRoot().performTouchInput {
            click(
                Offset((button.left + button.right).toPx() / 2, (button.top + button.bottom).toPx() / 2),
            )
        }
        shell.settle()
        assertFalse("the leaving page took the tap", shell.exists("Page 11"))
        assertFalse(shell.exists("Page 1"))
        shell.open("List root")
        shell.settle()
        assertTrue(shell.exists("Page 1"))
    }

    @Test
    fun `right to left, a page comes from the left`() {
        shell.show(LayoutDirection.Rtl)
        shell.open("List root")
        shell.frames(START_FRAMES)
        val coming = shell.left("Page 1")
        shell.settle()
        assertTrue("from the left", coming < shell.left("Page 1"))
    }

    @Test
    fun `right to left, Back slides the page off to the left and uncovers the List on the right`() {
        shell.show(LayoutDirection.Rtl, colouredEntries)
        compose.runOnUiThread { shell.navigator.open(TransitionPage(1)) }
        shell.settle()
        shell.back()
        shell.frames(MID_FLIGHT_FRAMES)
        val row = shell.image().row()
        assertEquals("the List at the right", shell.colors.primary, row.last())
        assertEquals("the page at the left", shell.colors.tertiary, row.first())
    }

    @Test
    fun `a key pressed while a page leaves does not act on it`() {
        shell.show()
        shell.useKeyboard()
        focus("Open from List root")
        shell.pressKey(Key.Enter)
        shell.settle()
        assertTrue("a key reaches a screen at rest", shell.exists("Page 1"))
        focus("Open from Page 1")
        shell.back()
        shell.frames(START_FRAMES)
        shell.pressKey(Key.Enter)
        shell.settle()
        assertFalse("the leaving page took the key", shell.exists("Page 11"))
        assertTrue(shell.exists("List root"))
    }

    private fun focus(button: String) {
        compose.onNode(hasText(button) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        shell.frames(1)
        compose.onNode(hasText(button) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `each navigation says which way it went`() {
        val navigator = ShellNavigator.inMemory()
        navigator.open(TransitionPage(1))
        assertEquals(Way.Deeper, navigator.way)
        navigator.back()
        assertEquals(Way.Up, navigator.way)
        navigator.select(Destination.Search)
        assertEquals(Way.Across, navigator.way)
        navigator.open(TransitionPage(2))
        navigator.open(TransitionPage(3))
        navigator.select(Destination.Search)
        assertEquals(Way.Up, navigator.way)
        navigator.back()
        assertEquals("Back from a root is Back", Way.Up, navigator.way)
    }

    @Test
    fun `a page arrives from the end and Back sends it off there, a gesture page away from its edge`() {
        assertEquals(1f, Way.Deeper.enter(rtl = false))
        assertEquals(-1f, Way.Deeper.enter(rtl = true))
        assertEquals(0f, Way.Up.enter(rtl = false))
        assertEquals(0f, Way.Across.enter(rtl = false))
        assertEquals(-1f, Way.Deeper.exit(rtl = false, full = WINDOW))
        assertEquals(WINDOW, Way.Up.exit(rtl = false, full = WINDOW))
        assertEquals(-WINDOW, Way.Up.exit(rtl = true, full = WINDOW))
        assertEquals(0f, Way.Across.exit(rtl = false, full = WINDOW))
        assertEquals(1f, gestureSide(NavigationEvent.EDGE_LEFT, rtl = true))
        assertEquals(-1f, gestureSide(NavigationEvent.EDGE_RIGHT, rtl = false))
        assertEquals(-1f, gestureSide(NavigationEvent.EDGE_NONE, rtl = true))
        assertEquals(NavigationEvent.EDGE_LEFT, BackEventCompat.EDGE_LEFT)
        assertEquals(NavigationEvent.EDGE_RIGHT, BackEventCompat.EDGE_RIGHT)
    }

    @Test
    fun `a released page goes on from where it was left`() {
        assertEquals(0.66f, released(0.66f, atRelease = 0.9f, now = 0.9f, commit = true), 0f)
        assertEquals(1f, released(0.66f, atRelease = 0.9f, now = 1f, commit = true), 0f)
        assertEquals(0.83f, released(0.66f, atRelease = 0.9f, now = 0.95f, commit = true), 1e-4f)
        assertEquals(0.66f, released(0.66f, atRelease = 0.9f, now = 0.9f, commit = false), 0f)
        assertEquals(0.33f, released(0.66f, atRelease = 0.9f, now = 0.45f, commit = false), 1e-4f)
        assertEquals(0f, released(0.66f, atRelease = 0.9f, now = 0f, commit = false), 0f)
    }

    private companion object {
        const val START_FRAMES = 4
        const val FRAME = ShellDriver.FRAME
        const val MID_FLIGHT_FRAMES = 5
        const val TWO_THIRDS = 0.66f
        const val EDGE_PX = 2f
        const val WINDOW = 12f

        // Logged on the emulator: the progress as the finger goes out to 80 %, then as it comes back.
        val DEVICE_SWEEP =
            listOf(
                0.069f,
                0.105f,
                0.146f,
                0.193f,
                0.250f,
                0.306f,
                0.367f,
                0.426f,
                0.497f,
                0.560f,
                0.628f,
                0.665f,
                0.698f,
                0.608f,
                0.452f,
                0.311f,
                0.204f,
                0.129f,
                0.080f,
                0.047f,
                0.028f,
                0.017f,
                0.010f,
                0f,
            )
        const val HALF = 0.5f
        const val QUARTER = 0.25f
        const val ROWS_SCROLLED = 30
    }
}

// With the system's animations off, every change of screen is complete in its first frame.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class ShellTransitionOffTest {
    private val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        )

    @get:Rule
    val rule: TestRule = registered(compose)

    private val shell = ShellDriver(compose)

    @After
    fun finish() = shell.finish()

    private fun done() = shell.frames(DONE_FRAMES)

    // The first frame that shows the change shows it complete: it is the screen as it stays.
    private fun completeAtOnce(
        shown: String,
        gone: String,
        change: () -> Unit,
    ) {
        change()
        var waited = 0
        while (!shell.exists(shown) && waited++ < START_FRAMES) shell.frames(1)
        val first = shell.image()
        shell.settle()
        assertFalse(shell.exists(gone))
        assertEquals("$gone to $shown", null, first.differences(shell.image()))
        shell.assertIdle()
    }

    @Test
    fun `every change of screen is complete at once`() {
        shell.show()
        completeAtOnce("Page 1", "List root") { shell.open("List root") }
        completeAtOnce("List root", "Page 1") { shell.back() }
        completeAtOnce("Profile root", "List root") { compose.runOnUiThread { shell.navigator.select(Destination.Profile) } }
        completeAtOnce("List root", "Profile root") { shell.back() }
    }

    @Test
    fun `a gesture is complete at once when released and gone at once when cancelled`() {
        shell.show()
        shell.open("List root")
        shell.settle()
        val before = shell.image()
        shell.gesture(HALF)
        done()
        shell.cancel()
        done()
        assertFalse(shell.exists("List root"))
        assertEquals(null, before.differences(shell.image()))
        completeAtOnce("List root", "Page 1") {
            shell.gesture(HALF)
            shell.release()
        }
    }

    private companion object {
        const val DONE_FRAMES = 3
        const val START_FRAMES = 3
        const val HALF = 0.5f
    }
}
