package io.github.kauri_off.kiroku.list

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The List's rows as they first appear: drawn where they settle, and a finger that travels beyond touch slop never
// activates a row or its "+", however its events are batched into frames.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ListFirstRowsTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<ListEvent>()
    private var state by mutableStateOf(ListFixtureStates.loading)

    private val plusOne = "Plus one episode"
    private val firstTitle get() =
        (ListFixtureStates.watching.content as ListContent.Tabs)
            .pages
            .getValue(
                ListStatus.Watching,
            ).rows[0]
            .title

    private fun plus() = compose.onAllNodesWithContentDescription(plusOne)[0]

    private fun row() = compose.onAllNodesWithText(firstTitle)[0]

    // The List as it first shows its rows: the gesture comes in the frame that composes them, or once all has settled.
    private fun list(firstFrame: Boolean) {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(state) { events += it } } }
        compose.waitForIdle()
        if (firstFrame) compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state = ListFixtureStates.watching }
        if (firstFrame) {
            while (compose.onAllNodesWithContentDescription(plusOne).fetchSemanticsNodes().isEmpty()) {
                compose.mainClock.advanceTimeByFrame()
            }
        } else {
            compose.waitForIdle()
        }
    }

    private fun gesture(
        target: SemanticsNodeInteraction,
        input: TouchInjectionScope.() -> Unit,
    ): List<ListEvent> {
        target.performTouchInput(input)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        return events.filter { it is ListEvent.PlusOne || it is ListEvent.Open }
    }

    // Down, then up far away with no move between: what reaches the app when every move came during a long frame.
    private fun TouchInjectionScope.leapLeft() {
        down(center)
        updatePointerTo(0, Offset(center.x - LEAP * density, center.y))
        up()
    }

    // Down, one move far away and up, all handled in one go before the next frame.
    private fun TouchInjectionScope.batchedSwipeLeft() {
        down(center)
        moveBy(Offset(-LEAP * density, 0f), delayMillis = 0)
        up()
    }

    private fun TouchInjectionScope.leapDown() {
        down(center)
        updatePointerTo(0, Offset(center.x, center.y + LEAP * density))
        up()
    }

    private fun travelled(
        firstFrame: Boolean,
        target: () -> SemanticsNodeInteraction,
        input: TouchInjectionScope.() -> Unit,
    ) {
        list(firstFrame)
        assertEquals(emptyList<ListEvent>(), gesture(target(), input))
    }

    @Test
    fun `a leap from the plus one in the first frame does not add an episode`() = travelled(true, ::plus) { leapLeft() }

    @Test
    fun `a leap from the plus one once settled does not add an episode`() = travelled(false, ::plus) { leapLeft() }

    @Test
    fun `a batched swipe from the plus one in the first frame does not add an episode`() = travelled(true, ::plus) { batchedSwipeLeft() }

    @Test
    fun `a leap across a row in the first frame does not open its title`() = travelled(true, ::row) { leapLeft() }

    @Test
    fun `a leap down a row once settled does not open its title`() = travelled(false, ::row) { leapDown() }

    @Test
    fun `a batched swipe across a row once settled does not open its title`() = travelled(false, ::row) { batchedSwipeLeft() }

    @Test
    fun `the rows are drawn in their first frame where they settle`() {
        list(firstFrame = true)
        val first = row().getUnclippedBoundsInRoot().top
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(row().getUnclippedBoundsInRoot().top, first)
    }

    @Test
    fun `a tap on the plus one in the first frame still adds an episode`() {
        list(firstFrame = true)
        assertEquals(1, gesture(plus()) { click() }.count { it is ListEvent.PlusOne })
    }

    @Test
    fun `a tap that trembles within touch slop still opens the title`() {
        list(firstFrame = false)
        val opened =
            gesture(row()) {
                down(center)
                moveBy(Offset(TREMBLE * density, TREMBLE * density))
                up()
            }
        assertEquals(1, opened.count { it is ListEvent.Open })
    }

    private companion object {
        const val LEAP = 300f
        const val TREMBLE = 2f
    }
}
