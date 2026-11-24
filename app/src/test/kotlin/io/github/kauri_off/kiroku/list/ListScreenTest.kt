package io.github.kauri_off.kiroku.list

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState
import io.github.kauri_off.kiroku.shell.rememberHostShowing
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

    // The neighbouring tabs' pages are composed off screen.
    private fun noneShown(description: String) {
        val nodes = compose.onAllNodesWithContentDescription(description)
        repeat(nodes.fetchSemanticsNodes().size) { nodes[it].assertIsNotDisplayed() }
    }

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
        noneShown(plusOne)
        show(UserListFixtureStates.watching)
        compose.onAllNodesWithContentDescription(plusOne).assertCountEquals(0)
    }

    // The bar's subtitle alone says a read goes on: no indicator over the tabs or the rows, and the tabs stay put.
    @Test
    fun `another user's list being read says how far it is in the bar alone, and the page does not move`() {
        show(UserListFixtureStates.watching)
        compose.onNodeWithText("6\u00A0titles").assertIsDisplayed()
        val tabs = tabTop()
        val indicators = progressIndicators()
        val said =
            listOf(
                UserListFixtureStates.reading to "40\u00A0titles loaded",
                UserListFixtureStates.readingWithTotal to "Loading 40\u00A0of\u00A0100",
                UserListFixtureStates.readAgain to "Loading the list again",
                UserListFixtureStates.readAgainPart to "Loading the list again:\u00A040\u00A0so far",
            )
        for ((state, text) in said) {
            show(state)
            compose.onNodeWithText(text).assertIsDisplayed()
            assertEquals(text, indicators, progressIndicators())
            assertEquals(text, tabs, tabTop())
        }
        show(UserListFixtureStates.watching)
        compose.onNodeWithText("Loading", substring = true).assertDoesNotExist()
    }

    // Those the page always has, the pull's at rest among them.
    private fun progressIndicators() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size

    private fun tabTop() = compose.onNode(hasText("Watching") and isSelectable()).getUnclippedBoundsInRoot().top

    // The order computed when a read ends replaces the arrival order in place: the first visible row keeps its place.
    @Test
    fun `when another user's list is ordered at the read's end the first visible row stays where it is`() {
        val set = Fixtures.reference
        val entries = set.otherUserList.take(ORDERED_AT_END)
        val status = ListStatus.Completed
        val arrived =
            entries
                .filter { it.status == status }
                .map { listRow(it, null, set.now, TitleLanguage.Romaji, readOnly = true, sort = ListSort.LastUpdated) }
        val sorted = ListFixtureStates.rows(status, ListSort.LastUpdated, entries, readOnly = true)

        fun state(rows: List<ListRow>) =
            ListState(
                owner = ListOwner.Other(set.otherUserName),
                content =
                    ListFixtureStates.tabs(
                        entries,
                        readOnly = true,
                        override = mapOf(status to ListPage(ListSort.LastUpdated, sortsFor(status, withAiring = false), rows)),
                    ),
                tab = status,
                now = set.now,
            )
        show(state(arrived))

        // The tab's own list, found by a row it shows: the first before the scroll, the one scrolled to after it.
        fun list(title: String) =
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
                ).filter(hasAnyDescendant(hasText(title)))[0]
        list(arrived.first().title).performScrollToIndex(FIRST_SHOWN)
        list(arrived[FIRST_SHOWN].title).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, PART_OF_A_ROW) }
        compose.waitForIdle()
        val first = arrived[FIRST_SHOWN].title
        val top = compose.onNode(hasText(first) and hasClickAction()).getUnclippedBoundsInRoot().top
        assertTrue(sorted.indexOfFirst { it.title == first } != FIRST_SHOWN)
        show(state(sorted))
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        compose.waitForIdle()
        assertEquals(top, compose.onNode(hasText(first) and hasClickAction()).getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `another user's private list and an unknown name each say so, with nothing to retry`() {
        show(UserListFixtureStates.privateList)
        compose.onNodeWithText("This list is private").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        show(UserListFixtureStates.unknownUser)
        compose.onNodeWithText("MyAnimeList has no user with this name").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
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
    fun `a status the app does not know is a last tab named by its code, its rows edited, and the tab beside it takes its place`() {
        showFollowing(ListFixtureStates.unknown)
        compose.onNode(hasText("Rewatching") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText(FixtureAnime.onHoldAiring.titles.romaji).performTouchInput { longClick() }
        noneShown(plusOne)
        swipe(left = true)
        swipe(left = false)
        assertEquals(listOf(ListEvent.Edit(FixtureAnime.onHoldAiring.id), ListEvent.SelectTab(ListStatus.Dropped)), events)

        events.clear()
        shown = ListFixtureStates.unknown.copy(content = ListFixtureStates.tabs())
        compose.waitForIdle()
        shown = ListFixtureStates.dropped
        compose.waitForIdle()
        compose.onNode(hasText("Dropped") and isSelected()).assertIsDisplayed()
        compose
            .onNodeWithText(
                ListFixtureStates.dropped.page!!
                    .rows
                    .first()
                    .title,
            ).assertIsDisplayed()
        compose.onNodeWithText("Rewatching").assertDoesNotExist()
        assertEquals(emptyList<ListEvent>(), events)
    }

    @Test
    fun `a list that loads with a status the app does not know selected shows that tab and selects no other on the way`() {
        show(ListFixtureStates.unknown.copy(content = ListContent.Loading))
        show(ListFixtureStates.unknown)
        compose.onNode(hasText("Rewatching") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText(FixtureAnime.onHoldAiring.titles.romaji).assertIsDisplayed()
        assertEquals(emptyList<ListEvent>(), events)
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
        val messages = compose.onAllNodesWithText("No titles match the filter")
        val shown = messages.fetchSemanticsNodes().indices.count { runCatching { messages[it].assertIsDisplayed() }.isSuccess }
        assertEquals(1, shown)
        assertEquals(listOf(ListEvent.SelectTab(ListStatus.Planned)), events)
    }

    @Test
    fun `the empty filter message moves with its page during a held swipe`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                ShellScreen(ShellState(Destination.Profile), {}) { FixtureListScreen(userListNoMatches) }
            }
        }
        val messages = compose.onAllNodesWithText("No titles match the filter", useUnmergedTree = true)
        val message =
            messages
                .fetchSemanticsNodes()
                .indices
                .single { runCatching { messages[it].assertIsDisplayed() }.isSuccess }
                .let { messages[it].fetchSemanticsNode() }
        val page = generateSequence(message.parent) { it.parent }.first { SemanticsProperties.VerticalScrollAxisRange in it.config }
        val messageNode = compose.onNode(SemanticsMatcher("outgoing message") { it.id == message.id }, useUnmergedTree = true)
        val pageNode = compose.onNode(SemanticsMatcher("outgoing page") { it.id == page.id }, useUnmergedTree = true)
        val restingPage = pageNode.getUnclippedBoundsInRoot()
        val restingMessage = messageNode.getUnclippedBoundsInRoot()
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.3f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        val swipingPage = pageNode.getUnclippedBoundsInRoot()
        val swipingMessage = messageNode.getUnclippedBoundsInRoot()
        compose.onRoot().performTouchInput { cancel() }
        val travel = (restingPage.left - swipingPage.left) / (restingPage.right - restingPage.left)
        assertTrue("Outgoing page travelled $travel of its width", travel in 0.15f..0.35f)
        assertEquals(
            (restingMessage.left - restingPage.left).value,
            (swipingMessage.left - swipingPage.left).value,
            1f,
        )
        assertEquals(restingMessage.top, swipingMessage.top)
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
        show(UserListFixtureStates.watching.copy(sortSheetOpen = true))
        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNodeWithText("Start date").performClick()
        assertEquals(listOf(ListEvent.SelectSort(ListSort.StartDate)), events)
    }

    @Test
    fun `the sort sheet offers the chosen sort's two directions, its own first, and sends the other one`() {
        show(ListFixtureStates.sortSheetScoreLowest)
        compose.onNodeWithText("Highest first").assertIsDisplayed().assertIsOff()
        compose.onNodeWithText("Lowest first").assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("Lowest first").performClick()
        compose.onNodeWithText("Highest first").performClick()
        assertEquals(listOf(ListEvent.SelectDirection(SortDirection.Descending)), events)
    }

    @Test
    @Config(qualifiers = "en-w891dp-h411dp-land")
    fun `in phone landscape the sort sheet scrolls to both directions, each with its whole touch target on screen`() {
        show(ListFixtureStates.sortSheetScoreLowest)
        // The sheet is a window of its own, as large as the activity's.
        val screen =
            compose
                .onAllNodes(isRoot())
                .fetchSemanticsNodes()
                .first()
                .boundsInRoot
        val target = with(compose.density) { Sizes.touchTarget.toPx() }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Lowest first"))).performTouchInput { swipeUp() }
        compose.waitForIdle()
        for (label in listOf("Highest first", "Lowest first")) {
            val touch = compose.onNodeWithText(label).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$label: $touch on $screen", touch.top >= screen.top && touch.bottom <= screen.bottom)
            assertTrue("$label: $touch", touch.height >= target - 1f)
        }
    }

    @Test
    fun `a row is spoken with the value its tab is sorted by, the star as the word for the score`() {
        show(ListFixtureStates.watchingByScore)
        compose
            .onNode(
                hasText(
                    "Kagerou Toshokan 2nd Season",
                ) and hasText("Watched 120\u00A0of\u00A0366\u00A0· Score\u00A010") and hasClickAction(),
            ).assertExists()
        compose.onAllNodes(hasText("★", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("★", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose
            .onNode(
                hasText("Gekkou Kishidan") and hasText("Watched 6\u00A0of\u00A013") and hasClickAction(),
            ).assertExists()
    }

    @Test
    fun `with up next chosen the sort sheet offers no direction`() {
        show(ListFixtureStates.sortSheet)
        listOf("A to Z", "Z to A", "Newest first", "Oldest first", "Highest first", "Lowest first", "Earliest first", "Latest first")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun `the screen draws the snackbar host it is given`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                ListScreen(ListFixtureStates.undoSnackbar, {}, rememberHostShowing(ListFixtureStates.undoMessage))
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
        show(UserListFixtureStates.watching)
        val title =
            UserListFixtureStates.watching.page!!
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

// The row a "+1" completes leaves with the list's item animation, a change inside the list; a
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

    private fun complete() = set(ListFixtureStates.completedSnackbar)

    private fun ListState.titles(): List<String> = page!!.rows.map { it.title }

    private fun exists(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    // With [resize], the window's height changes in the same frame the completed row leaves.
    private fun showCompleting(
        events: MutableList<ListEvent>,
        resize: Boolean = false,
    ) {
        val anime = FixtureAnime.oneShortOfTotal
        compose.mainClock.autoAdvance = false
        state = ListFixtureStates.watching
        var halved by mutableStateOf(false)
        compose.setContent {
            val window = LocalWindowInfo.current
            val resized =
                remember(window) {
                    object : WindowInfo by window {
                        override val containerSize: IntSize
                            get() = window.containerSize.let { if (halved) IntSize(it.width, it.height / 2) else it }
                    }
                }
            CompositionLocalProvider(LocalWindowInfo provides resized) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    FixtureListScreen(state) { e ->
                        events += e
                        if (e == ListEvent.PlusOne(anime.id)) {
                            state = ListFixtureStates.completedSnackbar
                            halved = resize
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    // Where the "+1" of the row showing [title] is now.
    private fun plusOneOf(title: String): Offset {
        val row = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
        return compose
            .onAllNodesWithContentDescription("Plus one episode")
            .fetchSemanticsNodes()
            .single { it.boundsInRoot.center.y / it.layoutInfo.density.density in row.top.value..row.bottom.value }
            .boundsInRoot.center
    }

    private fun tap(
        at: Offset,
        thenMillis: Long,
    ) {
        compose.onRoot().performTouchInput { click(at) }
        Snapshot.sendApplyNotifications()
        SystemClock.sleep(thenMillis)
        compose.mainClock.advanceTimeBy(thenMillis)
    }

    // A "+1" burst on a row that leaves Watching does not land on the row sliding into its place.
    @Test
    fun `quick taps where a completed row was do not reach the row that slides into its place`() {
        val anime = FixtureAnime.oneShortOfTotal
        val events = mutableListOf<ListEvent>()
        showCompleting(events)
        val button = plusOneOf(anime.titles.romaji)
        repeat(4) { tap(button, TAP_GAP_MS) }
        assertEquals(listOf(ListEvent.PlusOne(anime.id)), events.filterIsInstance<ListEvent.PlusOne>())
        SystemClock.sleep(SETTLED)
        tap(button, TAP_GAP_MS)
        assertEquals(
            listOf(ListEvent.PlusOne(anime.id), ListEvent.PlusOne(FixtureAnime.estimatedCaughtUp.id)),
            events.filterIsInstance<ListEvent.PlusOne>(),
        )
    }

    // The finger aims at the row below the completed one, which has moved up; the row now there is not hit.
    @Test
    fun `a quick tap aimed at the row below a completed row reaches no other title`() {
        val anime = FixtureAnime.oneShortOfTotal
        val events = mutableListOf<ListEvent>()
        showCompleting(events)
        val below = plusOneOf(following)
        tap(plusOneOf(anime.titles.romaji), PAIR_MS)
        tap(below, TAP_GAP_MS)
        assertEquals(listOf(ListEvent.PlusOne(anime.id)), events)
        SystemClock.sleep(SETTLED)
        tap(below, TAP_GAP_MS)
        assertEquals(2, events.filterIsInstance<ListEvent.PlusOne>().size)
    }

    @Test
    fun `a window resized as a completed row leaves still keeps a quick tap from the row below`() {
        val anime = FixtureAnime.oneShortOfTotal
        val events = mutableListOf<ListEvent>()
        showCompleting(events, resize = true)
        val below = plusOneOf(following)
        tap(plusOneOf(anime.titles.romaji), PAIR_MS)
        tap(below, TAP_GAP_MS)
        assertEquals(listOf(ListEvent.PlusOne(anime.id)), events)
    }

    // The rows have stopped, but the tap aimed at the row below comes at an ordinary pace after the first.
    @Test
    fun `a tap aimed at the row below a completed row at an ordinary pace reaches no other title`() {
        val anime = FixtureAnime.oneShortOfTotal
        val events = mutableListOf<ListEvent>()
        showCompleting(events)
        val below = plusOneOf(following)
        compose.onRoot().performTouchInput { click(plusOneOf(anime.titles.romaji)) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        SystemClock.sleep(ORDINARY_PACE_MS)
        compose.mainClock.advanceTimeBy(ORDINARY_PACE_MS)
        tap(below, TAP_GAP_MS)
        assertEquals(listOf(ListEvent.PlusOne(anime.id)), events.filterIsInstance<ListEvent.PlusOne>())
        SystemClock.sleep(PLUS_ONE_SETTLE_MILLIS)
        tap(below, TAP_GAP_MS)
        assertEquals(2, events.filterIsInstance<ListEvent.PlusOne>().size)
    }

    @Test
    fun `the row leaves and the rows below close up within 250 ms`() {
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
    fun `a selected tab slides its page in and settles within 400 ms`() {
        showAndSettle()
        val watchingLeft = left(following)
        set(ListFixtureStates.planned)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS / 3)
        assertTrue("the watching page is on its way out", left(following) < watchingLeft)
        assertTrue("the planned page is on its way in", left(planned) > watchingLeft)
        compose.mainClock.advanceTimeBy(TAB_SWITCH_MS - TAB_SWITCH_MS / 3)
        assertEquals(watchingLeft, left(planned))
        compose.onNodeWithText(following).assertIsNotDisplayed()
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
        compose.onNodeWithText(planned).assertIsNotDisplayed()
        compose.onNodeWithText(following).assertIsNotDisplayed()
        assertIdle()
    }

    @Test
    fun `a swipe settles on the next tab within 400 ms of letting go and selects it`() {
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
        const val COMPONENT_CHANGE_MS = MotionBounds.CHANGE
        const val TAB_SWITCH_MS = MotionBounds.ENTER
        const val SETTLED = 2_000L

        // Between two taps of a burst.
        const val TAP_GAP_MS = 100L

        // The second tap of a quick pair, measured on the emulator.
        const val PAIR_MS = 300L

        // The second tap of a pair at an ordinary pace, after the rows have stopped.
        const val ORDINARY_PACE_MS = 900L
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
        compose.runOnUiThread { state = ListFixtureStates.completedSnackbar }
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

// The list of another user ordered at the read's end, and the row shown first, a little scrolled.
private const val ORDERED_AT_END = 600
private const val FIRST_SHOWN = 40
private const val PART_OF_A_ROW = 37f
