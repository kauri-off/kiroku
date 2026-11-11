package io.github.kauri_off.kiroku.list

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.list.ListSort
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class ListScreenTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<ListEvent>()

    private var shown by mutableStateOf<ListState?>(null)

    private fun show(state: ListState) {
        if (shown == null) {
            compose.setContent {
                KirokuTheme(dark = false, dynamicColor = false) {
                    shown?.let { HostedListScreen(it) { e -> events += e } }
                }
            }
        }
        shown = state
        compose.waitForIdle()
    }

    private val plusOne = "Plus one episode"

    @Test
    fun `plus one is on each watching row and sends that row's title`() {
        val state = ListFixtureStates.watching.copy(content = ListContent.Rows(ListFixtureStates.rows(ListStatus.Watching).take(2)))
        show(state)
        compose.onAllNodesWithContentDescription(plusOne).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(plusOne)[1].performClick()
        assertEquals(listOf(ListEvent.PlusOne(FixtureAnime.unknownTotal.id)), events)
    }

    @Test
    fun `no other tab and no other user's list has plus one`() {
        show(ListFixtureStates.planned)
        compose.onAllNodesWithContentDescription(plusOne).assertCountEquals(0)
        show(ListFixtureStates.readOnly)
        compose.onAllNodesWithContentDescription(plusOne).assertCountEquals(0)
    }

    @Test
    fun `a row opens its title`() {
        show(ListFixtureStates.planned)
        compose.onNodeWithText(FixtureAnime.premiereDate.titles.romaji).performClick()
        assertEquals(listOf(ListEvent.Open(FixtureAnime.premiereDate.id)), events)
    }

    @Test
    fun `filter, sort and tabs send their events`() {
        show(ListFixtureStates.watching)
        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onNodeWithText("Planned").performClick()
        assertEquals(listOf(ListEvent.StartFilter, ListEvent.OpenSort, ListEvent.SelectTab(ListStatus.Planned)), events)
    }

    @Test
    fun `back and clearing the field end filtering`() {
        show(ListFixtureStates.filtering)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(List(3) { ListEvent.EndFilter }, events)
    }

    @Test
    fun `the sort sheet offers the tab's sorts and sends the chosen one`() {
        show(ListFixtureStates.readOnly.copy(sortSheetOpen = true))
        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNodeWithText("Start date").performClick()
        assertEquals(listOf(ListEvent.SelectSort(ListSort.StartDate)), events)
    }

    @Test
    fun `undo in the snackbar is sent with its message`() {
        show(ListFixtureStates.undoSnackbar)
        compose.onNodeWithText("Episode\u00A013 watched").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ListEvent.Undo(ListFixtureStates.undoSnackbar.message!!)), events)
    }

    @Test
    fun `a snackbar cut short by leaving the screen is reported as dismissed`() {
        show(ListFixtureStates.undoSnackbar)
        compose.onNodeWithText("Undo").assertExists()
        shown = null
        compose.waitForIdle()
        assertEquals(listOf(ListEvent.MessageDismissed(ListFixtureStates.undoSnackbar.message!!)), events)
    }

    @Test
    fun `a failed refresh names no connection when that is the cause`() {
        show(ListFixtureStates.offlineCached)
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        show(ListFixtureStates.watching.copy(message = ListMessage(ListMessage.Kind.RefreshFailed(ListFailure.Other), serial = 2)))
        compose.onNodeWithText("Could not refresh").assertExists()
    }

    @Test
    fun `an empty tab can be pulled to refresh`() {
        show(ListFixtureStates.empty)
        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.waitForIdle()
        assertTrue(events.toString(), ListEvent.Refresh in events)
    }

    @Test
    fun `the empty and signed out states offer their one action`() {
        show(ListFixtureStates.empty)
        compose.onNodeWithText("Search").performClick()
        show(ListFixtureStates.signedOut)
        compose.onNodeWithText("Sign in").performClick()
        show(ListFixtureStates.error)
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(ListEvent.Search, ListEvent.SignIn, ListEvent.Retry), events)
    }
}

// The row a "+1" completes leaves with the list's item animation, a change inside the list (ARCHITECTURE 8.5); a
// new order, a filter or another tab replaces the rows at once.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class ListMotionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf(ListFixtureStates.watching)

    private val leaving = FixtureAnime.oneShortOfTotal.titles.romaji
    private val following = FixtureAnime.estimatedCaughtUp.titles.romaji

    private fun top(title: String): Dp = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top

    private fun showAndSettle(start: ListState = ListFixtureStates.watching) {
        state = start
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { HostedListScreen(state) } }
        compose.mainClock.advanceTimeBy(SETTLED)
    }

    private fun set(value: ListState) {
        compose.runOnUiThread { state = value }
        Snapshot.sendApplyNotifications()
    }

    private fun complete() = set(ListFixtureStates.completedSnackbar.copy(message = null))

    private fun ListState.titles(): List<String> = (content as ListContent.Rows).rows.map { it.title }

    private fun exists(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the row leaves and the rows below close up within 200 ms`() {
        showAndSettle()
        val before = top(following)
        val leavingTop = top(leaving)
        complete()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(COMPONENT_CHANGE_MS / 2)
        assertTrue("the rows below are on their way up", top(following) < before && top(following) > leavingTop)
        compose.mainClock.advanceTimeBy(COMPONENT_CHANGE_MS / 2)
        compose.onNodeWithText(leaving).assertDoesNotExist()
        assertEquals(leavingTop, top(following))
        assertTrue(before > leavingTop)
        assertIdle()
    }

    @Test
    fun `undo in mid-flight puts the row back in its place`() {
        showAndSettle()
        val leavingTop = top(leaving)
        val followingTop = top(following)
        complete()
        compose.mainClock.advanceTimeBy(COMPONENT_CHANGE_MS / 3)
        set(ListFixtureStates.watching)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(leavingTop, top(leaving))
        assertEquals(followingTop, top(following))
        assertIdle()
    }

    @Test
    fun `typing in the filter does not animate`() {
        assertInstant(ListFixtureStates.watching.copy(filter = ""), ListFixtureStates.filtering)
    }

    @Test
    fun `a refresh that orders the rows again does not animate`() {
        val watching = ListFixtureStates.watching
        val rows = (watching.content as ListContent.Rows).rows
        assertInstant(watching, watching.copy(ordering = watching.ordering + 1, content = ListContent.Rows(rows.reversed())))
    }

    @Test
    fun `a new sort does not animate`() {
        assertInstant(
            ListFixtureStates.watching,
            ListFixtureStates.watching.copy(
                sort = ListSort.Title,
                ordering = ListFixtureStates.watching.ordering + 1,
                content = ListContent.Rows(ListFixtureStates.rows(ListStatus.Watching, ListSort.Title)),
            ),
        )
    }

    @Test
    fun `switching tabs does not animate`() {
        assertInstant(ListFixtureStates.watching, ListFixtureStates.planned)
    }

    // One frame after the change, rows that left are gone and every row is where it settles.
    private fun assertInstant(
        from: ListState,
        to: ListState,
    ) {
        showAndSettle(from)
        set(to)
        compose.mainClock.advanceTimeByFrame()
        (from.titles() - to.titles().toSet()).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        val shown = to.titles().filter(::exists)
        assertTrue(shown.isNotEmpty())
        val first = shown.associateWith(::top)
        val firstRow = compose.onNodeWithText(shown.first()).captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(first, shown.associateWith(::top))
        val settledRow = compose.onNodeWithText(shown.first()).captureToImage().toPixelMap()
        assertTrue("the first row is drawn in full at once", firstRow.buffer.contentEquals(settledRow.buffer))
        assertIdle()
    }

    private fun assertIdle() {
        compose.mainClock.advanceTimeByFrame()
        assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    private companion object {
        val COMPONENT_CHANGE_MS = Motion.Duration.SHORT4.toLong()
        const val SETTLED = 2_000L
    }
}

// With the system's animations off, the row is gone at the next frame.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h2000dp")
class ListMotionOffTest {
    private val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        )

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf(ListFixtureStates.watching)

    @Test
    fun `the change is complete at once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { HostedListScreen(state) } }
        compose.mainClock.advanceTimeByFrame()
        val leavingTop = compose.onNodeWithText(FixtureAnime.oneShortOfTotal.titles.romaji).getUnclippedBoundsInRoot().top
        compose.runOnUiThread { state = ListFixtureStates.completedSnackbar.copy(message = null) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(FixtureAnime.oneShortOfTotal.titles.romaji).assertDoesNotExist()
        assertEquals(leavingTop, compose.onNodeWithText(FixtureAnime.estimatedCaughtUp.titles.romaji).getUnclippedBoundsInRoot().top)
    }
}
