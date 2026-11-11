package io.github.kauri_off.kiroku.list

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.ListStatus
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
                    shown?.let {
                        ListScreen(it, onEvent = { e ->
                            events += e
                        })
                    }
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
        compose.onNodeWithText("Episode\u00A013\u00A0watched").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ListEvent.Undo(ListFixtureStates.undoSnackbar.message!!)), events)
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

// The row a "+1" completes leaves with the list's item animation (ARCHITECTURE 8.5).
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

    private fun showAndSettle() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { ListScreen(state, onEvent = {}) } }
        compose.mainClock.advanceTimeBy(SETTLED)
    }

    private fun set(value: ListState) {
        compose.runOnUiThread { state = value }
        Snapshot.sendApplyNotifications()
    }

    private fun complete() = set(ListFixtureStates.completedSnackbar.copy(message = null))

    @Test
    fun `the row leaves and the rows below close up within 300 ms`() {
        showAndSettle()
        val before = top(following)
        val leavingTop = top(leaving)
        complete()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(COMPONENT_MS)
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
        compose.mainClock.advanceTimeBy(COMPONENT_MS / 3)
        set(ListFixtureStates.watching)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(leavingTop, top(leaving))
        assertEquals(followingTop, top(following))
        assertIdle()
    }

    private fun assertIdle() {
        compose.mainClock.advanceTimeByFrame()
        assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }

    private companion object {
        const val COMPONENT_MS = 300L
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
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { ListScreen(state, onEvent = {}) } }
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
