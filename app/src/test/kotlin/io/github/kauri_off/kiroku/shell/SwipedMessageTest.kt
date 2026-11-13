package io.github.kauri_off.kiroku.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListEvent
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The shell's messages on the host a screen draws, over a surface that counts the taps that reach it.
private class SwipeHarness(
    val compose: AndroidComposeTestRule<*, ComponentActivity>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val graph = TestGraph(Demo(Fixtures.reference, scope))
    val messages = ShellMessages(scope, graph.list)
    val anime = FixtureAnime.exactToWatch
    var composed by mutableStateOf(true)
    var direction by mutableStateOf(LayoutDirection.Ltr)
    var taps = 0

    fun show() =
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.fillMaxSize().clickable { taps++ })
                    if (composed) {
                        val host = remember { SnackbarHostState() }
                        ShownMessages(messages, host)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { ScreenSnackbarHost(host) }
                    }
                }
            }
        }

    fun post(vararg posted: ShellMessage) = compose.runOnUiThread { posted.forEach(messages::post) }

    fun watched() =
        runBlocking {
            graph.list
                .observeEntry(anime.id)
                .first()
                ?.values
                ?.watchedEpisodes
        }

    fun failed(episode: Int) = ShellMessage(ShellMessage.Text.CouldNotSave, ShellMessage.Action.Retry(ListWrite.SetWatched(anime, episode)))

    val moved = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Remove(anime)))

    // A drag from the middle of the message's text by [by], over [millis]; a negative [by] is to the left.
    fun drag(
        text: String,
        by: Dp,
        millis: Long = FAST_MS,
    ) = compose.onNodeWithText(text).performTouchInput { swipe(center, center + Offset(by.toPx(), 0f), millis) }

    fun left(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().left

    companion object {
        val PAST = 200.dp

        // Short of the stock threshold of 56 dp, and slow enough to be no fling.
        val SHORT = 30.dp
        const val FAST_MS = 150L
        const val SLOW_MS = 1_000L
    }
}

// A message swiped away either way is dismissed as when its time runs out (ARCHITECTURE 7.2).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SwipedMessageTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val harness = SwipeHarness(compose)

    @After
    fun stop() = harness.scope.cancel()

    private fun swipesAwayEitherWay() =
        with(harness) {
            show()
            val before = watched()
            post(failed(1), moved, failed(2))
            compose.waitForIdle()
            drag("Could not save", SwipeHarness.PAST)
            compose.waitForIdle()
            assertEquals(moved, messages.current.value?.message)
            compose.onNodeWithText("Moved to Completed").assertExists()
            drag("Moved to Completed", -SwipeHarness.PAST)
            compose.waitForIdle()
            assertEquals(failed(2), messages.current.value?.message)
            compose.onNodeWithText("Moved to Completed").assertDoesNotExist()
            assertEquals("a swipe ran an action", before, watched())
        }

    @Test
    fun `a message swiped past the threshold either way is dismissed and the next waiting one is shown`() = swipesAwayEitherWay()

    @Test
    fun `in a right-to-left layout a message is swiped away either way too`() {
        harness.direction = LayoutDirection.Rtl
        swipesAwayEitherWay()
    }

    @Test
    fun `a short swipe returns the message, and its action still works`() =
        with(harness) {
            show()
            val before = runBlocking { graph.list.observeEntry(anime.id).first()!! }
            val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
            val message = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(change.undo))
            post(message)
            compose.waitForIdle()
            val rest = left("Moved to Completed")
            drag("Moved to Completed", SwipeHarness.SHORT, SwipeHarness.SLOW_MS)
            compose.waitForIdle()
            assertEquals(message, messages.current.value?.message)
            assertEquals(rest.value, left("Moved to Completed").value, 0.5f)
            drag("Moved to Completed", -SwipeHarness.SHORT, SwipeHarness.SLOW_MS)
            compose.waitForIdle()
            assertEquals(rest.value, left("Moved to Completed").value, 0.5f)
            compose.onNodeWithText("Undo").performClick()
            compose.waitForIdle()
            assertEquals(before.values.watchedEpisodes, watched())
            assertNull(messages.current.value)
        }

    // The person threw the Undo away: a recreation does not bring it back, and nothing is written.
    @Test
    fun `a swiped message that must stay is gone after a recreation and its Undo is not run`() =
        with(harness) {
            show()
            val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
            val after = watched()
            post(ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(change.undo)))
            compose.waitForIdle()
            drag("Moved to Completed", -SwipeHarness.PAST)
            compose.waitForIdle()
            composed = false
            compose.waitForIdle()
            composed = true
            compose.waitForIdle()
            compose.onNodeWithText("Moved to Completed").assertDoesNotExist()
            assertNull(messages.current.value)
            assertEquals(after, watched())
        }

    @Test
    fun `a screen reader can dismiss a message, and the next waiting one is shown`() =
        with(harness) {
            show()
            post(failed(1), moved)
            compose.waitForIdle()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
            compose.waitForIdle()
            assertEquals(moved, messages.current.value?.message)
        }

    // The swipe takes the message's own area, no more: its margin is no wider a dead zone than before.
    @Test
    fun `a touch in the message's margin reaches the screen under it`() =
        with(harness) {
            show()
            post(moved)
            compose.waitForIdle()
            val item = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            item.performTouchInput { click(Offset(centerX, MARGIN_TOUCH.toPx())) }
            compose.waitForIdle()
            assertEquals(1, taps)
            item.performTouchInput {
                swipe(
                    Offset(centerX, MARGIN_TOUCH.toPx()),
                    Offset(centerX + SwipeHarness.PAST.toPx(), MARGIN_TOUCH.toPx()),
                )
            }
            compose.waitForIdle()
            assertEquals(moved, messages.current.value?.message)
        }

    private companion object {
        // Inside the stock 12 dp margin above the message.
        val MARGIN_TOUCH = 4.dp
    }
}

// ARCHITECTURE 8.5: the message follows the finger, and settles from the theme's fast effects spring.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SwipedMessageMotionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val harness = SwipeHarness(compose)

    @After
    fun stop() = harness.scope.cancel()

    private fun shown(): Dp =
        with(harness) {
            compose.mainClock.autoAdvance = false
            show()
            post(failed(1), moved)
            compose.mainClock.advanceTimeBy(SETTLED_MS)
            left("Could not save")
        }

    private fun assertIdle() {
        compose.mainClock.advanceTimeByFrame()
        assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    private fun followsTheFinger(direction: LayoutDirection) {
        harness.direction = direction
        val rest = shown()
        val by = SwipeHarness.SHORT
        compose.onNodeWithText("Could not save").performTouchInput {
            down(center)
            moveBy(Offset(by.toPx(), 0f), delayMillis = 100)
            moveBy(Offset(by.toPx(), 0f), delayMillis = 100)
        }
        compose.mainClock.advanceTimeByFrame()
        val held = harness.left("Could not save") - rest
        assertTrue("$direction: moved by $held for a finger moved by ${by * 2}", held > by && held <= by * 2)
        compose.onNodeWithText("Could not save").performTouchInput { up() }
    }

    @Test
    fun `the message follows the finger`() = followsTheFinger(LayoutDirection.Ltr)

    @Test
    fun `in a right-to-left layout the message follows the finger too`() = followsTheFinger(LayoutDirection.Rtl)

    @Test
    fun `a short swipe is back at rest within the time of a change, and nothing runs after`() {
        val rest = shown()
        harness.drag("Could not save", SwipeHarness.SHORT, SwipeHarness.SLOW_MS)
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(rest.value, harness.left("Could not save").value, 0.5f)
        assertEquals(
            harness.failed(1),
            harness.messages.current.value
                ?.message,
        )
        assertIdle()
    }

    @Test
    fun `a swipe past the threshold ends within the time of a change`() {
        shown()
        harness.drag("Could not save", SwipeHarness.PAST)
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(
            harness.moved,
            harness.messages.current.value
                ?.message,
        )
    }

    @Test
    fun `a returning message caught mid-flight goes where the new swipe sends it`() {
        shown()
        harness.drag("Could not save", SwipeHarness.SHORT, SwipeHarness.SLOW_MS)
        compose.mainClock.advanceTimeByFrame()
        harness.drag("Could not save", -SwipeHarness.PAST)
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(
            harness.moved,
            harness.messages.current.value
                ?.message,
        )
    }

    @Test
    fun `a leaving message caught mid-flight and brought back stays`() {
        val rest = shown()
        harness.drag("Could not save", SwipeHarness.PAST)
        compose.mainClock.advanceTimeByFrame()
        val out = harness.left("Could not save") - rest
        assertTrue("$out", out > 0.dp)
        harness.drag("Could not save", -out, SwipeHarness.SLOW_MS)
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(
            harness.failed(1),
            harness.messages.current.value
                ?.message,
        )
        assertEquals(rest.value, harness.left("Could not save").value, 0.5f)
    }

    private companion object {
        const val SETTLED_MS = 2_000L
    }
}

// ARCHITECTURE 8.5: with the system's animations off, a released swipe ends at once.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SwipedMessageMotionOffTest {
    private val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        )

    @get:Rule
    val rule: TestRule = registered(compose)

    private val harness = SwipeHarness(compose)

    @After
    fun stop() = harness.scope.cancel()

    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    @Test
    fun `a swipe past the threshold shows the next message, and a short one is back, by the next frames`() {
        with(harness) {
            compose.mainClock.autoAdvance = false
            show()
            post(failed(1), moved)
            compose.mainClock.advanceTimeBy(SETTLED_MS)
            val rest = left("Could not save")
            drag("Could not save", SwipeHarness.SHORT, SwipeHarness.SLOW_MS)
            frames(2)
            assertEquals(rest.value, left("Could not save").value, 0.5f)
            drag("Could not save", SwipeHarness.PAST)
            frames(4)
            assertEquals(moved, messages.current.value?.message)
            compose.onNodeWithText("Could not save").assertDoesNotExist()
            compose.onNodeWithText("Moved to Completed").assertExists()
        }
    }

    private companion object {
        const val SETTLED_MS = 2_000L
    }
}

// The message lies over the List's pages: a swipe on it moves the message, not the pager under it.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class SwipedMessageOverPagesTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a swipe on the message does not turn the List's page`() {
        val events = mutableListOf<ListEvent>()
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                FixtureListScreen(ListFixtureStates.watching, ListFixtureStates.undoMessage) { events += it }
            }
        }
        compose.waitForIdle()
        val row =
            ListFixtureStates.watching.page!!
                .rows
                .first()
                .title
        val rest = compose.onNodeWithText(row).getUnclippedBoundsInRoot().left
        compose.onNodeWithText("Episode\u00A013 watched").performTouchInput {
            swipe(center, center - Offset(SwipeHarness.PAST.toPx(), 0f), SwipeHarness.FAST_MS)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Episode\u00A013 watched").assertDoesNotExist()
        assertEquals(rest, compose.onNodeWithText(row).getUnclippedBoundsInRoot().left)
        assertTrue("$events", events.none { it is ListEvent.SelectTab })
        compose.onNode(hasText("Watching") and isSelectable()).assertIsSelected()
    }
}
