package io.github.kauri_off.kiroku.title

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val third = FixtureAnime.premiereYear.titles.romaji
private val film = TitleFixtures.filmBetweenSeasons.titles.romaji
private const val LOADING = "Loading the watch order"
private const val SYNOPSIS = "Synopsis"
private const val RELATED = "Related"

// A title the walk finds joins the watch order as the List's rows do: it fades in on the default effects spring and
// the rows below make room on the fast one; the indicator leaves. The order arriving with the details does not move.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class TitleChainMotionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf<TitleState>(TitleFixtures.chainLoading)

    private fun top(title: String): Dp = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top

    private fun showAndSettle(start: TitleState) {
        state = start
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { TitleInShell(state, onEvent = {}) } }
        compose.mainClock.advanceTimeBy(SETTLED)
    }

    private fun set(value: TitleState) {
        compose.runOnUiThread { state = value }
        Snapshot.sendApplyNotifications()
    }

    private fun assertIdle() {
        compose.mainClock.advanceTimeByFrame()
        assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    // The rows below make room within the bound of a change, and the found title has faded in within that of an entrance.
    @Test
    fun `a title the walk finds comes in between the seasons, the rows below make room, and it settles within 400 ms`() {
        showAndSettle(TitleFixtures.chainLoading)
        val before = top(third)
        val synopsisBefore = top(SYNOPSIS)
        set(TitleFixtures.chainOfFour)
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        val moving = top(third)
        val synopsisMoving = top(SYNOPSIS)
        compose.mainClock.advanceTimeBy(CHANGE_MS)
        val settled = top(third)
        val synopsisSettled = top(SYNOPSIS)
        assertTrue("the third season is on its way down: $before < $moving < $settled", moving > before && moving < settled)
        assertTrue(
            "Synopsis moves with the rows: $synopsisBefore < $synopsisMoving < $synopsisSettled",
            synopsisMoving > synopsisBefore && synopsisMoving < synopsisSettled,
        )
        assertTrue("the film comes before the third season", top(film) < settled)
        compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(ENTER_MS - CHANGE_MS)
        val entered = compose.onNodeWithText(film).captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(settled, top(third))
        assertEquals(synopsisSettled, top(SYNOPSIS))
        assertTrue(
            "the film has faded in",
            entered.buffer.contentEquals(
                compose
                    .onNodeWithText(film)
                    .captureToImage()
                    .toPixelMap()
                    .buffer,
            ),
        )
        assertIdle()
    }

    @Test
    fun `a walk that goes back in mid-flight ends with every row where the last state puts it`() {
        showAndSettle(TitleFixtures.chainFailed)
        val before = top(third)
        val synopsisBefore = top(SYNOPSIS)
        set(TitleFixtures.chainOfFour)
        compose.mainClock.advanceTimeBy(ENTER_MS / 3)
        set(TitleFixtures.chainFailed)
        compose.mainClock.advanceTimeBy(SETTLED)
        compose.onNodeWithText(film).assertDoesNotExist()
        assertEquals(before, top(third))
        assertEquals(synopsisBefore, top(SYNOPSIS))
        assertIdle()
    }

    @Test
    fun `the watch order that comes with the details is drawn in place at once`() {
        showAndSettle(TitleFixtures.of(FixtureAnime.exactToWatch, details = null))
        set(TitleFixtures.chainFailed)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val first = top(third)
        val row = compose.onNodeWithText(third).captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(first, top(third))
        val settled = compose.onNodeWithText(third).captureToImage().toPixelMap()
        assertTrue("the row is drawn in full at once", row.buffer.contentEquals(settled.buffer))
        assertIdle()
    }

    // As on a page without a watch order: the sections below an opening or closing synopsis are in place at once.
    @Test
    fun `the synopsis opening and closing moves the sections below it at once`() {
        showAndSettle(TitleFixtures.chainOfFour)
        for (expanded in listOf(true, false)) {
            val before = top(RELATED)
            set(TitleFixtures.chainOfFour.copy(moment = TitleMoment(synopsisExpanded = expanded)))
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val first = top(RELATED)
            assertTrue("Related moved", first != before)
            compose.mainClock.advanceTimeBy(SETTLED)
            assertEquals(first, top(RELATED))
            assertIdle()
        }
    }

    private companion object {
        const val ENTER_MS = MotionBounds.ENTER
        const val CHANGE_MS = MotionBounds.CHANGE
        const val SETTLED = 2_000L
    }
}

// With the system's animations off, a title the walk finds is in place at the next frame.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class TitleChainMotionOffTest {
    private val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        )

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf<TitleState>(TitleFixtures.chainLoading)

    private fun top(title: String): Dp = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top

    @Test
    fun `the change is complete at once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { TitleInShell(state, onEvent = {}) } }
        compose.mainClock.advanceTimeBy(SETTLED)
        compose.runOnUiThread { state = TitleFixtures.chainOfFour }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val thirdTop = top(third)
        val row = compose.onNodeWithText(film).captureToImage().toPixelMap()
        compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(thirdTop, top(third))
        assertTrue(
            "the found title is drawn in full at once",
            row.buffer.contentEquals(
                compose
                    .onNodeWithText(film)
                    .captureToImage()
                    .toPixelMap()
                    .buffer,
            ),
        )
    }

    private companion object {
        const val SETTLED = 2_000L
    }
}
