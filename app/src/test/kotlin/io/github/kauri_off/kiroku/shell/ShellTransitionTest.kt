package io.github.kauri_off.kiroku.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
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

@Serializable
internal data class TransitionPage(
    val n: Int,
) : NavKey

// The shell over roots and pages that each show their name, a way deeper and a long list whose scroll is kept.
internal class ShellDriver(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    lateinit var navigator: ShellNavigator
    private var shown = false

    // Each screen's own transition, by its name, as NavDisplay runs it.
    val motion = mutableMapOf<String, Transition<EnterExitState>>()

    private val entries: ShellEntries = { navigator, _ ->
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
        motion[name] = LocalNavAnimatedContentScope.current.transition
        Text(name)
        Button(onClick = { navigator.open(next) }) { Text("Open from $name") }
        LazyColumn { items(ROWS) { Text("$name, row $it") } }
    }

    fun show(direction: LayoutDirection = LayoutDirection.Ltr) {
        shown = true
        compose.mainClock.autoAdvance = false
        compose.setContent {
            navigator = rememberShellNavigator()
            KirokuTheme(dark = false, dynamicColor = false) {
                val inputModes = LocalInputModeManager.current
                useKeyboard = { inputModes.requestInputMode(InputMode.Keyboard) }
                CompositionLocalProvider(LocalLayoutDirection provides direction) { ShellRoute(navigator, entries = entries) }
            }
        }
        settle()
    }

    lateinit var useKeyboard: () -> Unit

    fun settle() = frames((SETTLED / FRAME).toInt())

    fun finish() {
        if (shown) settle()
    }

    // Frame by frame, letting the work each frame starts run before the next, as on a device.
    fun frames(count: Int) =
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

    // Gives where [name] is after every frame of a change that settles within a screen's time, from the first that
    // shows it, which is at most the change's start frames away.
    fun eachFrame(
        name: String,
        check: (frame: Int, left: Float) -> Unit,
    ) = repeat(START_FRAMES + (MotionBounds.SCREEN / FRAME).toInt()) { frame ->
        frames(1)
        if (exists(name)) check(frame, left(name)) else assertTrue("$name is not shown at frame $frame", frame < START_FRAMES)
    }

    fun open(from: String) = compose.onNode(hasText("Open from $from") and hasClickAction()).performClick()

    fun onUi(change: ShellNavigator.() -> Unit) = compose.runOnUiThread { navigator.change() }

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

    fun release() = back()

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

    fun pressKey(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    fun focus(button: String) {
        compose.onNode(hasText(button) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        frames(1)
        compose.onNode(hasText(button) and hasClickAction()).assertIsFocused()
    }

    fun tapWhere(button: String): () -> Unit {
        val bounds = compose.onNode(hasText(button) and hasClickAction()).getUnclippedBoundsInRoot()
        return {
            compose.onRoot().performTouchInput {
                click(Offset((bounds.left + bounds.right).toPx() / 2, (bounds.top + bounds.bottom).toPx() / 2))
            }
        }
    }

    // At rest: [name] in its place, nothing else of the change left, and no animation pending.
    fun assertAtRest(
        name: String,
        rest: Float,
        gone: String,
    ) {
        settle()
        assertEquals(rest, left(name))
        assertFalse("$gone is still there", exists(gone))
        assertTrue("an animation is still pending", Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    companion object {
        const val SETTLED = 2_000L
        const val FRAME = 16L
        const val ROWS = 60

        // A change may take this many frames to show: one to apply the state, one to start, one to move.
        const val START_FRAMES = 3
    }
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

// ARCHITECTURE 8.5: the screen transitions, and the back gesture whose preview is NavDisplay's own.
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

    // Along the change, [name] moves only toward its place, from the side [from] says, and is there within its time.
    private fun arrives(
        name: String,
        from: Float,
    ) {
        val path = mutableListOf<Float>()
        shell.eachFrame(name) { _, left -> path += left }
        shell.settle()
        val rest = shell.left(name)
        assertTrue("$name never left its place: $path", path.first() != rest)
        path.forEach { now -> assertTrue("$name at $now in $path", (now - rest) * from >= 0f) }
        path.zipWithNext().forEach { (a, b) -> assertTrue("$name went back from $a to $b in $path", (a - b) * from >= 0f) }
        assertEquals("$name is not in its place within a screen's time", rest, path.last())
    }

    @Test
    fun `a page opened comes in from the end, and Back brings the List back from the start, each within a screen's time`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        arrives("Page 1", from = 1f)
        shell.assertAtRest("Page 1", rest, gone = "List root")
        shell.back()
        arrives("List root", from = -1f)
        shell.assertAtRest("List root", rest, gone = "Page 1")
    }

    @Test
    fun `an exit is shorter than the entrance it matches, and both settle within a screen's time`() {
        shell.show()
        shell.open("List root")
        shell.frames(START_FRAMES)
        shorter(exit = "List root", entrance = "Page 1")
        shell.settle()
        shell.back()
        shell.frames(START_FRAMES)
        shorter(exit = "Page 1", entrance = "List root")
    }

    private fun shorter(
        exit: String,
        entrance: String,
    ) {
        val exitMillis = shell.motion.getValue(exit).totalDurationNanos / NANOS
        val entranceMillis = shell.motion.getValue(entrance).totalDurationNanos / NANOS
        assertTrue("$exit leaves in $exitMillis ms, $entrance enters in $entranceMillis ms", exitMillis in 1 until entranceMillis)
        assertTrue("$entrance enters in $entranceMillis ms", entranceMillis <= MotionBounds.SCREEN)
    }

    @Test
    fun `another destination fades through in place, and so does Back to the List`() {
        shell.show()
        val rest = shell.left("List root")
        shell.onUi { select(Destination.Search) }
        shell.eachFrame("Search root") { frame, left -> assertEquals("frame $frame", rest, left) }
        shell.assertAtRest("Search root", rest, gone = "List root")
        shell.back()
        shell.eachFrame("List root") { frame, left -> assertEquals("frame $frame", rest, left) }
        shell.assertAtRest("List root", rest, gone = "Search root")
    }

    @Test
    fun `Back pressed while a page comes in ends on the screen under it, and a page opened during Back on the page`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.frames(MID_FLIGHT_FRAMES)
        shell.back()
        shell.assertAtRest("List root", rest, gone = "Page 1")
        shell.open("List root")
        shell.settle()
        shell.back()
        shell.frames(MID_FLIGHT_FRAMES)
        shell.onUi { open(TransitionPage(1)) }
        shell.assertAtRest("Page 1", rest, gone = "List root")
    }

    @Test
    fun `a tap while a page leaves acts on neither screen, and one after it on the screen arrived`() {
        shell.show()
        shell.open("List root")
        shell.settle()
        val tap = shell.tapWhere("Open from Page 1")
        shell.back()
        shell.frames(START_FRAMES)
        tap()
        shell.settle()
        assertFalse("the leaving page took the tap", shell.exists("Page 11"))
        assertFalse("the List under it took the tap", shell.exists("Page 1"))
        shell.open("List root")
        shell.settle()
        assertTrue(shell.exists("Page 1"))
    }

    @Test
    fun `a key pressed while a page leaves does not act on it`() {
        shell.show()
        compose.runOnUiThread { shell.useKeyboard() }
        shell.focus("Open from List root")
        shell.pressKey(Key.Enter)
        shell.settle()
        assertTrue("a key reaches a screen at rest", shell.exists("Page 1"))
        shell.focus("Open from Page 1")
        shell.back()
        shell.frames(START_FRAMES)
        shell.pressKey(Key.Enter)
        shell.settle()
        assertFalse("the leaving page took the key", shell.exists("Page 11"))
        assertTrue(shell.exists("List root"))
    }

    @Test
    fun `a held gesture shows the screen Back leads to, and a cancelled one restores the page exactly, its scroll and its stack`() {
        shell.show()
        val rest = shell.left("List root")
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
            assertTrue("the List shows under the page as it was", shell.exists("List root, row $ROWS_SCROLLED"))
            shell.cancel()
            shell.settle()
            assertFalse(shell.exists("List root"))
            assertEquals("edge $edge", null, before.differences(shell.image()))
        }
        shell.open("Page 1")
        shell.settle()
        shell.back()
        shell.settle()
        assertTrue("the stack kept the page", shell.exists("Page 1, row $ROWS_SCROLLED"))
        shell.back()
        shell.assertAtRest("List root, row $ROWS_SCROLLED", rest, gone = "Page 1")
    }

    @Test
    fun `a completed gesture ends on the screen under it as it was`() {
        shell.show()
        val rest = shell.left("List root")
        shell.scrollTo("List root", ROWS_SCROLLED)
        val list = shell.image()
        shell.open("List root")
        shell.settle()
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        shell.release()
        shell.assertAtRest("List root, row $ROWS_SCROLLED", rest, gone = "Page 1")
        assertEquals(null, list.differences(shell.image()))
    }

    @Test
    fun `a gesture from the root of Search shows the List and a cancelled one keeps Search`() {
        shell.show()
        val rest = shell.left("List root")
        shell.onUi { select(Destination.Search) }
        shell.settle()
        val before = shell.image()
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        assertTrue(shell.exists("List root"))
        shell.cancel()
        shell.settle()
        assertFalse(shell.exists("List root"))
        assertEquals(null, before.differences(shell.image()))
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        shell.release()
        shell.assertAtRest("List root", rest, gone = "Search root")
    }

    @Test
    fun `right to left, a page comes in from the left and Back brings the List back from the right`() {
        shell.show(LayoutDirection.Rtl)
        shell.open("List root")
        arrives("Page 1", from = -1f)
        shell.settle()
        shell.back()
        arrives("List root", from = 1f)
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
        navigator.select(Destination.Search)
        assertEquals("the destination tapped again pops to its root", Way.Up, navigator.way)
        navigator.back()
        assertEquals("Back from a root goes to another destination", Way.Across, navigator.way)
    }

    private companion object {
        const val START_FRAMES = ShellDriver.START_FRAMES
        const val MID_FLIGHT_FRAMES = 5
        const val ROWS_SCROLLED = 30
        const val QUARTER = 0.25f
        const val HALF = 0.5f
        const val NANOS = 1_000_000L
    }
}

// With the system's animations off, every change of screen is complete in the first frame that shows it.
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

    private fun completeAtOnce(
        shown: String,
        gone: String,
        change: () -> Unit,
    ) {
        change()
        var waited = 0
        while (!shell.exists(shown) && waited++ < ShellDriver.START_FRAMES) shell.frames(1)
        val first = shell.image()
        shell.assertAtRest(shown, shell.left(shown), gone)
        assertEquals("$gone to $shown", null, first.differences(shell.image()))
    }

    @Test
    fun `every change of screen is complete at once`() {
        shell.show()
        completeAtOnce("Page 1", "List root") { shell.open("List root") }
        completeAtOnce("List root", "Page 1") { shell.back() }
        completeAtOnce("Profile root", "List root") { shell.onUi { select(Destination.Profile) } }
        completeAtOnce("List root", "Profile root") { shell.back() }
    }

    @Test
    fun `a gesture is complete at once when released and gone at once when cancelled`() {
        shell.show()
        shell.open("List root")
        shell.settle()
        val before = shell.image()
        shell.gesture(HALF)
        shell.frames(DONE_FRAMES)
        shell.cancel()
        shell.frames(DONE_FRAMES)
        assertFalse(shell.exists("List root"))
        assertEquals(null, before.differences(shell.image()))
        completeAtOnce("List root", "Page 1") {
            shell.gesture(HALF)
            shell.release()
        }
    }

    private companion object {
        const val DONE_FRAMES = 3
        const val HALF = 0.5f
    }
}
