package io.github.kauri_off.kiroku.title

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
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

// A section by its heading, or a watch-order card by the title its spoken sentence starts with.
private fun ComposeTestRule.on(text: String): SemanticsNodeInteraction =
    onNode(
        hasText(text) or
            SemanticsMatcher("spoken first: $text") { node ->
                node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .any { it.startsWith("$text, ") }
            },
    )

// A title the walk finds joins the watch order's strip: it fades in on the default effects spring, and the cards after
// it make room at once; the indicator leaves. The order arriving with the details does not move.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class TitleChainMotionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf<TitleState>(TitleFixtures.chainLoading)

    private fun top(title: String): Dp = compose.on(title).getUnclippedBoundsInRoot().top

    private fun left(title: String): Dp = compose.on(title).getUnclippedBoundsInRoot().left

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

    // The found title fades in within the bound of an entrance; the cards after it and the sections below, and the
    // indicator leaving, are in place at once, as no slide can be kept to this change alone.
    @Test
    fun `a title the walk finds fades in between the seasons within 400 ms, and nothing else moves`() {
        showAndSettle(TitleFixtures.chainLoading)
        val before = left(third)
        set(TitleFixtures.chainOfFour)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val third2 = left(third)
        val synopsis2 = top(SYNOPSIS)
        val fading = compose.on(film).captureToImage().toPixelMap()
        assertTrue("the third season made room", third2 > before)
        assertTrue("the film comes before the third season", left(film) < third2)
        compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(ENTER_MS)
        val entered = compose.on(film).captureToImage().toPixelMap()
        assertTrue("the film was fading in", !fading.buffer.contentEquals(entered.buffer))
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(third2, left(third))
        assertEquals(synopsis2, top(SYNOPSIS))
        assertTrue(
            "the film has faded in",
            entered.buffer.contentEquals(
                compose
                    .on(film)
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
        val before = left(third)
        val synopsisBefore = top(SYNOPSIS)
        set(TitleFixtures.chainOfFour)
        compose.mainClock.advanceTimeBy(ENTER_MS / 3)
        set(TitleFixtures.chainFailed)
        compose.mainClock.advanceTimeBy(SETTLED)
        compose.on(film).assertDoesNotExist()
        assertEquals(before, left(third))
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
        val row = compose.on(third).captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(first, top(third))
        val settled = compose.on(third).captureToImage().toPixelMap()
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

    // A refresh that shortens the synopsis changes the height above Related, and is not animated.
    @Test
    fun `a refresh that changes a section's height moves the sections below it at once`() {
        showAndSettle(TitleFixtures.chainOfFour)
        val before = top(RELATED)
        val details = checkNotNull(TitleFixtures.chainOfFour.page.details)
        set(TitleFixtures.chainOfFourWith(details = details.copy(synopsis = "A short synopsis.")))
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val first = top(RELATED)
        assertTrue("Related moved", first < before)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(first, top(RELATED))
        assertIdle()
    }

    // "Add to list" becomes the list block, which is taller: the watch order and everything under it are in place at once.
    @Test
    fun `Add to list becoming the list block moves the watch order and the sections below at once`() {
        showAndSettle(TitleFixtures.chainOfFourWith(entry = null))
        val thirdBefore = top(third)
        val anime = FixtureAnime.exactToWatch
        set(
            TitleFixtures.chainOfFourWith(
                entry = ListEntry(anime, ListValues(ListStatus.Planned, 0, null, null, null), FixtureTime.reference),
            ),
        )
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val thirdFirst = top(third)
        val synopsisFirst = top(SYNOPSIS)
        assertTrue("the third season moved", thirdFirst > thirdBefore)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(thirdFirst, top(third))
        assertEquals(synopsisFirst, top(SYNOPSIS))
        assertIdle()
    }

    private companion object {
        const val ENTER_MS = MotionBounds.ENTER
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

    private fun left(title: String): Dp = compose.on(title).getUnclippedBoundsInRoot().left

    @Test
    fun `the change is complete at once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { TitleInShell(state, onEvent = {}) } }
        compose.mainClock.advanceTimeBy(SETTLED)
        compose.runOnUiThread { state = TitleFixtures.chainOfFour }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val thirdLeft = left(third)
        val row = compose.on(film).captureToImage().toPixelMap()
        compose.onNodeWithContentDescription(LOADING).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(thirdLeft, left(third))
        assertTrue(
            "the found title is drawn in full at once",
            row.buffer.contentEquals(
                compose
                    .on(film)
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
