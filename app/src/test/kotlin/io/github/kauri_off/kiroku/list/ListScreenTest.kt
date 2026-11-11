package io.github.kauri_off.kiroku.list

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Motion
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
                    shown?.let { FixtureListScreen(it) { e -> events += e } }
                }
            }
        }
        shown = state
        compose.waitForIdle()
    }

    private val plusOne = "Plus one episode"

    @Test
    fun `plus one is on each watching row and sends that row's title`() {
        val rows = ListFixtureStates.rows(ListStatus.Watching).take(2)
        val state = ListFixtureStates.watching.copy(content = ListFixtureStates.tabs(override = ListFixtureStates.watchingPage(rows)))
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

    // Shows [state] and follows its tab as a view model would.
    private fun showFollowing(state: ListState) {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                shown?.let {
                    FixtureListScreen(it) { e ->
                        events += e
                        if (e is ListEvent.SelectTab) shown = shown?.copy(tab = e.status)
                    }
                }
            }
        }
        shown = state
        compose.waitForIdle()
    }

    private fun swipe(left: Boolean) {
        compose.onRoot().performTouchInput { if (left) swipeLeft() else swipeRight() }
        compose.waitForIdle()
    }

    @Test
    fun `a swipe selects the next or previous tab`() {
        showFollowing(ListFixtureStates.watching)
        swipe(left = true)
        compose
            .onNodeWithText(
                ListFixtureStates.planned.page!!
                    .rows
                    .first()
                    .title,
            ).assertIsDisplayed()
        swipe(left = false)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned), ListEvent.SelectTab(ListStatus.Watching)), events)
    }

    @Test
    fun `each tab keeps its scroll position`() {
        showFollowing(ListFixtureStates.watching)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val title =
            ListFixtureStates.watching.page!!
                .rows
                .last()
                .title
        val top = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top
        swipe(left = true)
        swipe(left = false)
        assertEquals(ListStatus.Watching, shown?.tab)
        assertEquals(top, compose.onNodeWithText(title).getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `a swipe while filtering shows the next tab filtered`() {
        showFollowing(ListFixtureStates.filtering)
        swipe(left = true)
        compose.onNodeWithText("No titles match the filter").assertIsDisplayed()
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned)), events)
    }

    @Test
    fun `a page with rows can be pulled to refresh`() {
        show(ListFixtureStates.planned)
        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.waitForIdle()
        assertEquals(listOf(ListEvent.Refresh), events)
    }

    @Test
    fun `W9 a count above the total is written as it is and keeps plus one`() {
        val rows =
            ListFixtureStates.aboveTotal.page!!
                .rows
                .filter { it.id == FixtureAnime.finishedPartWay.id }
        show(ListFixtureStates.aboveTotal.copy(content = ListFixtureStates.tabs(override = ListFixtureStates.watchingPage(rows))))
        compose.onNodeWithText("Watched 25\u00A0of\u00A024").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(plusOne).assertAll(isEnabled())
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
    fun `the screen draws the snackbar host it is given`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                ListScreen(ListFixtureStates.undoSnackbar, {}, remember { SnackbarHostState() })
            }
        }
        compose.onNodeWithText("Episode\u00A013 watched").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun `a long press or the edit action on a row asks to edit it, in every tab`() {
        show(ListFixtureStates.watching)
        val watching = FixtureAnime.exactToWatch
        compose.onNodeWithText(watching.titles.romaji).performTouchInput { longClick() }
        show(ListFixtureStates.planned)
        val planned = FixtureAnime.premiereDate
        val row = compose.onNode(hasText(planned.titles.romaji) and hasClickAction()).fetchSemanticsNode()
        val edit = row.config[SemanticsActions.OnLongClick]
        assertEquals("Edit", edit.label)
        assertFalse("Edit is offered once", row.config.contains(SemanticsActions.CustomActions))
        compose.runOnIdle { edit.action!!() }
        compose.waitForIdle()
        assertEquals(listOf(ListEvent.Edit(watching.id), ListEvent.Edit(planned.id)), events)
    }

    @Test
    fun `another user's rows offer no edit`() {
        show(ListFixtureStates.readOnly)
        val title =
            ListFixtureStates.readOnly.page!!
                .rows
                .first()
                .title
        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose
            .onNode(hasText(title) and hasClickAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        assertFalse(events.toString(), events.any { it is ListEvent.Edit })
    }

    @Test
    fun `plus one stands out only on a row with episodes to watch, at one size`() {
        val rows = ListFixtureStates.rows(ListStatus.Watching)
        val toWatch = rows.first { it.line3 is Line3.ToWatch }
        val caughtUp = rows.first { it.line3 !is Line3.ToWatch }
        show(
            ListFixtureStates.watching.copy(
                content = ListFixtureStates.tabs(override = ListFixtureStates.watchingPage(listOf(toWatch, caughtUp))),
            ),
        )
        val buttons = compose.onAllNodesWithContentDescription(plusOne)
        val (prominent, quiet) = listOf(buttons[0], buttons[1]).map { it.captureToImage().toPixelMap() }
        assertEquals(prominent.width, quiet.width)
        assertEquals(prominent.height, quiet.height)
        val target = with(compose.density) { 48.dp.roundToPx() }
        listOf(buttons[0], buttons[1]).forEach { button ->
            val touch = button.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(touch.toString(), touch.width >= target && touch.height >= target)
        }

        // Inside the button's visible shape, beside the icon: a container colour, or the row's own background.
        fun filled(image: PixelMap) = image[image.width / 5, image.height / 2] != image[0, 0]
        assertTrue("prominent", filled(prominent))
        assertFalse("quiet", filled(quiet))
    }

    @Test
    fun `starting and ending the filter keep the unfiltered list where it was`() {
        showFollowing(ListFixtureStates.watching)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val title = lastWatching()
        val top = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top
        shown = shown?.copy(filter = "")
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithText(title).getUnclippedBoundsInRoot().top)
        shown = ListFixtureStates.filtering
        compose.waitForIdle()
        shown = ListFixtureStates.watching
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithText(title).getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `coming back to the list keeps its rows where they were`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(ListFixtureStates.watching) } }
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val title = lastWatching()
        val top = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(top, compose.onNodeWithText(title).getUnclippedBoundsInRoot().top)
    }

    private fun lastWatching() =
        ListFixtureStates.watching.page!!
            .rows
            .last()
            .title

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
// new order or a filter replaces the rows at once; another tab's page slides in, an entrance.
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
    private val planned =
        ListFixtureStates.planned.page!!
            .rows
            .first()
            .title
    private val completed =
        ListFixtureStates.completed.page!!
            .rows
            .first()
            .title

    private fun top(title: String): Dp = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top

    private fun showAndSettle(start: ListState = ListFixtureStates.watching) {
        state = start
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(state) } }
        compose.mainClock.advanceTimeBy(SETTLED)
    }

    private fun set(value: ListState) {
        compose.runOnUiThread { state = value }
        Snapshot.sendApplyNotifications()
    }

    private fun complete() = set(ListFixtureStates.completedSnackbar.copy(message = null))

    private fun ListState.titles(): List<String> = page!!.rows.map { it.title }

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
        val page = watching.page!!
        val reordered = page.copy(rows = page.rows.reversed(), ordering = page.ordering + 1)
        assertInstant(watching, watching.copy(content = ListFixtureStates.tabs(override = mapOf(ListStatus.Watching to reordered))))
    }

    @Test
    fun `a new sort does not animate`() {
        assertInstant(
            ListFixtureStates.watching,
            ListFixtureStates.watching.copy(
                content =
                    ListFixtureStates.tabs(
                        override =
                            ListFixtureStates.watchingPage(
                                ListFixtureStates.rows(ListStatus.Watching, ListSort.Title),
                                ListSort.Title,
                            ),
                    ),
            ),
        )
    }

    @Test
    fun `a selected tab slides its page in and settles within 300 ms`() {
        showAndSettle()
        val watchingLeft = left(following)
        set(ListFixtureStates.planned)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS / 3)
        assertTrue("the watching page is on its way out", left(following) < watchingLeft)
        assertTrue("the planned page is on its way in", left(planned) > watchingLeft)
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS - TAB_SWITCH_MS / 3)
        assertEquals(watchingLeft, left(planned))
        assertFalse(exists(following))
        compose.mainClock.advanceTimeBy(SETTLED)
        assertIdle()
    }

    @Test
    fun `a tab selected mid-flight ends on that tab`() {
        showAndSettle()
        val watchingLeft = left(following)
        set(ListFixtureStates.planned)
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS / 3)
        set(ListFixtureStates.completed)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(watchingLeft, left(completed))
        assertFalse(exists(planned))
        assertFalse(exists(following))
        assertIdle()
    }

    @Test
    fun `a swipe settles on the next tab within 300 ms of letting go and selects it`() {
        val events = mutableListOf<ListEvent>()
        state = ListFixtureStates.watching
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(state) { events += it } } }
        compose.mainClock.advanceTimeBy(SETTLED)
        val watchingLeft = left(following)
        compose.onNodeWithText(following).performTouchInput { swipeLeft(durationMillis = TAB_SWITCH_MS) }
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS)
        assertEquals(watchingLeft, left(planned))
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned)), events)
        assertIdle()
    }

    private fun left(title: String): Dp = compose.onNodeWithText(title).getUnclippedBoundsInRoot().left

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
        val TAB_SWITCH_MS = Motion.Duration.MEDIUM2.toLong()
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
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(state) } }
        compose.mainClock.advanceTimeByFrame()
        val leavingTop = compose.onNodeWithText(FixtureAnime.oneShortOfTotal.titles.romaji).getUnclippedBoundsInRoot().top
        compose.runOnUiThread { state = ListFixtureStates.completedSnackbar.copy(message = null) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(FixtureAnime.oneShortOfTotal.titles.romaji).assertDoesNotExist()
        assertEquals(leavingTop, compose.onNodeWithText(FixtureAnime.estimatedCaughtUp.titles.romaji).getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `another tab is shown at once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { FixtureListScreen(state) } }
        compose.mainClock.advanceTimeByFrame()
        val left = compose.onNodeWithText(FixtureAnime.estimatedCaughtUp.titles.romaji).getUnclippedBoundsInRoot().left
        compose.runOnUiThread { state = ListFixtureStates.planned }
        Snapshot.sendApplyNotifications()
        repeat(OFF_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        val planned =
            ListFixtureStates.planned.page!!
                .rows
                .first()
                .title
        assertEquals(left, compose.onNodeWithText(planned).getUnclippedBoundsInRoot().left)
        compose.onNodeWithText(FixtureAnime.estimatedCaughtUp.titles.romaji).assertIsNotDisplayed()
        val first = compose.onRoot().captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(OFF_SETTLED)
        assertTrue(
            "nothing moves after that",
            first.buffer.contentEquals(
                compose
                    .onRoot()
                    .captureToImage()
                    .toPixelMap()
                    .buffer,
            ),
        )
    }
}

// With animations off, a change that shows within these frames of the state changing is complete at once.
private const val OFF_FRAMES = 3
private const val OFF_SETTLED = 2_000L
