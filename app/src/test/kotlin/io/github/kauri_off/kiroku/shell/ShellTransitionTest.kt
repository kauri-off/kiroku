package io.github.kauri_off.kiroku.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import io.github.kauri_off.kiroku.design.theme.settleMillis
import io.github.kauri_off.kiroku.signin.SignInKey
import io.github.kauri_off.kiroku.signin.browse
import io.github.kauri_off.kiroku.signin.signedIn
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
import kotlin.math.sqrt

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

    // Each screen's colour, by its name; a mix of two is a screen part-faded.
    val colours = mutableMapOf<String, Color>()

    // The keys that passed every screen, bubbling up from the focused one.
    val keys = mutableListOf<Key>()

    lateinit var motionScheme: MotionScheme

    private val entries: ShellEntries = { navigator, _ ->
        entry<ListRoot> { Screen("List root", navigator, TransitionPage(1), MaterialTheme.colorScheme.primaryContainer) }
        entry<SearchRoot> { Screen("Search root", navigator, TransitionPage(2), MaterialTheme.colorScheme.tertiaryContainer) }
        entry<ProfileRoot> { Screen("Profile root", navigator, TransitionPage(3), Color.Transparent) }
        entry<TransitionPage> { page ->
            Screen("Page ${page.n}", navigator, TransitionPage(page.n + 10), MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }

    @Composable
    private fun Screen(
        name: String,
        navigator: ShellNavigator,
        next: NavKey,
        colour: Color,
    ) = Column(Modifier.fillMaxSize().background(colour)) {
        motion[name] = LocalNavAnimatedContentScope.current.transition
        colours[name] = colour
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
                motionScheme = MaterialTheme.motionScheme
                val inputModes = LocalInputModeManager.current
                useKeyboard = { inputModes.requestInputMode(InputMode.Keyboard) }
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(
                        Modifier.onKeyEvent {
                            if (it.type == KeyEventType.KeyDown) keys += it.key
                            true
                        },
                    ) { ShellRoute(navigator, entries = entries) }
                }
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

    // Where [name] is after each frame of a change, from the first that shows it to a screen's time.
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

    // The colour at the middle of the content's end edge, where no text is drawn.
    fun edge(): Color = image().toPixelMap().let { it[it.width - 2, it.height / 2] }

    // Frames after a change's start until a screen leaving with the fast effects spring is no longer drawn.
    val fadeFrames: Int get() = START_FRAMES + (motionScheme.fastEffectsSpec<Float>().settleMillis(1f) / FRAME).toInt() + 1

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

// The screen transitions, and the back gesture that shows nothing until it commits.
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
        shell.settle()
        shell.onUi { select(Destination.Search) }
        shell.frames(START_FRAMES)
        shorter(exit = "List root", entrance = "Search root")
        shell.settle()
        shell.back()
        shell.frames(START_FRAMES)
        shorter(exit = "Search root", entrance = "List root")
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
    fun `between destinations both screens are drawn part-faded a few frames in`() {
        shell.show()
        shell.onUi { select(Destination.Profile) }
        shell.settle()
        val window = shell.edge()
        shell.back()
        shell.settle()
        val list = shell.colours.getValue("List root")
        shell.onUi { select(Destination.Search) }
        shell.frames(START_FRAMES + 2)
        val search = shell.colours.getValue("Search root")
        val mix = shell.edge()
        assertTrue("the leaving List is not drawn under Search: $mix", mix.awayFrom(window, search) > MIXED)
        assertTrue("Search is not drawn over the List: $mix", mix.awayFrom(window, list) > MIXED)
        shell.settle()
        assertEquals(search, shell.edge())
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
    fun `after Back a tap reaches the screen arriving once the leaving one is no longer drawn, never the leaving one`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.settle()
        val tap = shell.tapWhere("Open from Page 1")
        shell.back()
        shell.frames(START_FRAMES)
        tap()
        shell.frames(START_FRAMES)
        assertFalse("the leaving page took the tap", shell.exists("Page 11"))
        assertFalse("a tap went through the page still drawn", shell.exists("Page 1"))
        shell.frames(shell.fadeFrames - 2 * START_FRAMES)
        assertTrue("the List is already at rest", shell.left("List root") != rest)
        tap()
        shell.settle()
        assertTrue("the List did not take the tap", shell.exists("Page 1"))
        assertFalse(shell.exists("Page 11"))
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
        shell.keys.clear()
        for (key in listOf(Key.Enter, Key.Back, Key.Escape)) shell.pressKey(key)
        assertEquals("Back and Escape pass a leaving page", listOf(Key.Back, Key.Escape), shell.keys)
        shell.settle()
        assertFalse("the leaving page took the key", shell.exists("Page 11"))
        assertTrue(shell.exists("List root"))
    }

    @Test
    fun `a held gesture changes nothing on screen, and a cancelled one leaves the page as it was, its scroll, focus and stack`() {
        shell.show()
        val rest = shell.left("List root")
        shell.scrollTo("List root", ROWS_SCROLLED)
        shell.open("List root")
        shell.settle()
        shell.scrollTo("Page 1", ROWS_SCROLLED)
        val before = shell.image()
        for (progress in listOf(QUARTER, HALF)) {
            shell.gesture(progress, start = progress == QUARTER)
            shell.frames(START_FRAMES)
            assertEquals("at $progress", null, before.differences(shell.image()))
        }
        shell.cancel()
        shell.settle()
        assertEquals(null, before.differences(shell.image()))
        compose.runOnUiThread { shell.useKeyboard() }
        shell.focus("Open from Page 1")
        shell.open("Page 1")
        shell.settle()
        shell.back()
        shell.settle()
        assertTrue("the stack kept the page", shell.exists("Page 1, row $ROWS_SCROLLED"))
        shell.back()
        shell.assertAtRest("List root, row $ROWS_SCROLLED", rest, gone = "Page 1")
    }

    // Where [name] is and the colour at the end edge, frame by frame, from [change] to a screen's time.
    private fun path(
        name: String,
        change: () -> Unit,
    ): List<Pair<Float?, Color>> {
        change()
        val path = mutableListOf<Pair<Float?, Color>>()
        repeat(START_FRAMES + (MotionBounds.SCREEN / FRAME).toInt()) {
            shell.frames(1)
            path += (if (shell.exists(name)) shell.left(name) else null) to shell.edge()
        }
        return path
    }

    // A gesture held and then committed, from the moment it commits.
    private fun committed() {
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        shell.release()
    }

    @Test
    fun `a committed gesture brings the List back as Back does, from the start, and the List then takes a tap`() {
        shell.show()
        val rest = shell.left("List root")
        shell.open("List root")
        shell.settle()
        val byBack = path("List root") { shell.back() }
        shell.open("List root")
        shell.settle()
        val tap = shell.tapWhere("Open from Page 1")
        val byGesture = path("List root") { committed() }
        assertTrue("Back moved nothing: $byBack", byBack.mapNotNull { it.first }.distinct().size > 1)
        assertEquals(byBack, byGesture)
        shell.assertAtRest("List root", rest, gone = "Page 1")
        tap()
        shell.settle()
        assertTrue("the List did not take the tap", shell.exists("Page 1"))
    }

    @Test
    fun `a gesture from the root of Search changes nothing until it commits, then fades through to the List as Back does`() {
        shell.show()
        shell.onUi { select(Destination.Search) }
        shell.settle()
        val before = shell.image()
        shell.gesture(HALF)
        shell.frames(START_FRAMES)
        assertEquals(null, before.differences(shell.image()))
        shell.cancel()
        shell.settle()
        assertEquals(null, before.differences(shell.image()))
        val byBack = path("List root") { shell.back() }
        shell.onUi { select(Destination.Search) }
        shell.settle()
        val byGesture = path("List root") { committed() }
        val list = shell.colours.getValue("List root")
        assertTrue("Back faded nothing: $byBack", byBack.first().second != list)
        assertEquals(byBack, byGesture)
        assertEquals(list, shell.edge())
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
        navigator.open(TransitionPage(3))
        navigator.open(TransitionPage(4))
        navigator.remove(TransitionPage(3))
        assertEquals("a screen removed from under the top moves nothing", Way.Deeper, navigator.way)
        navigator.remove(TransitionPage(4))
        assertEquals(Way.Up, navigator.way)
        assertEquals(Way.Across, signingIn(Destination.Profile).apply { signedIn() }.way)
        assertEquals(Way.Up, signingIn(Destination.List, TransitionPage(5)).apply { signedIn() }.way)
        assertEquals(Way.Up, signingIn(Destination.Search).apply { browse() }.way)
        assertEquals(Way.Across, signingIn(Destination.Profile).apply { browse() }.way)
    }

    // The sign-in screen open on [from], over [pages].
    private fun signingIn(
        from: Destination,
        vararg pages: NavKey,
    ) = ShellNavigator.inMemory().apply {
        select(from)
        pages.forEach { open(it) }
        open(SignInKey)
    }

    private companion object {
        const val START_FRAMES = ShellDriver.START_FRAMES
        const val FRAME = ShellDriver.FRAME
        const val MID_FLIGHT_FRAMES = 5
        const val ROWS_SCROLLED = 30
        const val QUARTER = 0.25f
        const val HALF = 0.5f
        const val NANOS = 1_000_000L

        // How far a colour lies from the mix of two others before it shows a third screen drawn.
        const val MIXED = 0.02f
    }
}

// The distance of this colour from the line through [a] and [b], in RGB.
private fun Color.awayFrom(
    a: Color,
    b: Color,
): Float {
    val ab = listOf(b.red - a.red, b.green - a.green, b.blue - a.blue)
    val ac = listOf(red - a.red, green - a.green, blue - a.blue)
    val t = ab.zip(ac).sumOf { (x, y) -> (x * y).toDouble() } / ab.sumOf { (it * it).toDouble() }
    return sqrt(ac.indices.sumOf { ((ac[it] - t * ab[it]).let { d -> d * d }) }).toFloat()
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
