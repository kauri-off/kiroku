package io.github.kauri_off.kiroku.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The app's Search on the fixture titles: typed text reaches the field and the results with nothing else running.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SearchRouteTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose))

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    @After
    fun stop() = scope.cancel()

    private fun settle() =
        repeat(SETTLE_ROUNDS) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }

    // The search view's field, which has the focus once the bar is tapped.
    private val typing = hasSetTextAction() and isFocused() and hasAnyAncestor(isDialog())

    private fun inView(text: String) = hasText(text) and hasAnyAncestor(isDialog())

    private fun search() {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        settle()
        compose.onNodeWithText("Search").performClick()
        settle()
    }

    @Test
    fun `each keystroke shows in the field and its results before any view model coroutine runs`() {
        search()
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).performClick()
        settle()
        compose.onNode(typing).performKeyInput {
            pressKey(Key.S)
            pressKey(Key.H)
            pressKey(Key.I)
        }
        compose.waitForIdle()
        compose.onNode(typing and hasText("shi")).assertExists()
        compose.onNode(inView(FixtureAnime.exactCaughtUp.titles.romaji)).assertExists()
        compose.onNode(inView(FixtureAnime.exactToWatch.titles.romaji)).assertDoesNotExist()
    }

    @Test
    fun `a search is kept on top of the recent searches, and clearing the field shows them`() {
        search()
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).performClick()
        settle()
        compose.onNode(typing).performKeyInput {
            pressKey(Key.H)
            pressKey(Key.A)
            pressKey(Key.N)
            pressKey(Key.A)
        }
        compose.onNode(typing).performImeAction()
        compose.onNode(hasContentDescription("Clear search") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.onNode(inView("hana")).assertExists()
        compose.onNode(inView(SearchFixtureStates.recent[MAX_RECENT_SEARCHES - 1])).assertDoesNotExist()
        compose.onNode(inView(SearchFixtureStates.recent.first())).assertExists()
    }

    @Test
    fun `a title's status follows the list, also after an edit made elsewhere`() {
        search()
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).performClick()
        settle()
        compose.onNode(typing).performKeyInput {
            pressKey(Key.K)
            pressKey(Key.A)
            pressKey(Key.G)
        }
        settle()
        compose.onNode(inView(watching)).assertExists()
        scope.launch { graph.list.write(ListWrite.Remove(FixtureAnime.exactToWatch)) }
        settle()
        compose.onNode(inView(watching)).assertDoesNotExist()
        compose.onNode(inView(FixtureAnime.exactToWatch.titles.romaji)).assertExists()
    }

    private val watching = "Watching\u00A0· 12\u00A0/\u00A024"

    private fun typeKag() {
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).performClick()
        settle()
        compose.onNode(typing).performKeyInput {
            pressKey(Key.K)
            pressKey(Key.A)
            pressKey(Key.G)
        }
        settle()
    }

    // The first of the results for "kag" that is not on the list.
    private val notListed =
        SearchFixtureStates
            .standIn(Fixtures.reference)
            .search("kag")
            .first { it.entry == null }
            .anime

    private fun longPress(title: String) {
        compose.onNode(inView(title) and hasClickAction()).performTouchInput { longClick() }
        settle()
    }

    // A status of the edit sheet, whose toggle buttons are radio buttons.
    private fun status(name: String) = hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun `a long press on a result opens its edit sheet over the search view, and Save writes it`() {
        search()
        typeKag()
        val title = FixtureAnime.exactToWatch.titles.romaji
        longPress(title)
        compose.onNodeWithText("Save").assertExists()
        compose.onNode(status("Watching")).assertIsOn()
        compose.onNode(status("On hold")).performClick()
        compose.onNodeWithText("Save").performClick()
        settle()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNode(inView(title) and hasText("On hold", substring = true)).assertExists()
    }

    @Test
    fun `a long press on a result not on the list opens the sheet of a planned title, and Save adds it`() {
        search()
        typeKag()
        val title = notListed.titles.romaji
        compose.onNode(inView(title) and hasText("Planned")).assertDoesNotExist()
        longPress(title)
        compose.onNode(status("Planned")).assertIsOn()
        compose.onNodeWithText("Remove from list").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        settle()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNode(inView(title) and hasText("Planned")).assertExists()
    }

    private val topAiring = SearchFixtureStates.browseRows(BrowseList.TopAiring).first().title

    @Test
    fun `a long press on a browse list's row opens the sheet of that row's title`() {
        search()
        compose.onNodeWithText("Top airing").performClick()
        settle()
        val pressed = SearchFixtureStates.browseRows(BrowseList.TopAiring)[2]
        compose.onNode(hasText(pressed.title, substring = true) and hasClickAction()).performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Save").assertExists()
        // The sheet names its title alone; the row puts its rank in front.
        compose.onNode(hasText(pressed.title) and !hasClickAction()).assertExists()
        assertEquals(ListStatus.OnHold, pressed.entry?.status)
        compose.onNode(status("On hold")).assertIsOn()
    }

    @Test
    fun `a result's title reaches the sheet, also when neither the list nor the store has it`() {
        graph.titlesUnstored.value = true
        search()
        typeKag()
        longPress(notListed.titles.romaji)
        compose.onNodeWithText("Save").assertExists()
        compose.onNode(status("Planned")).assertIsOn()
    }

    @Test
    fun `with the session expired a result offers an edit, and the refused save and the banner are shown over the view`() {
        graph.demo.server.sessionExpired.value = true
        search()
        typeKag()
        compose.onNode(inView("Session expired")).assertExists()
        longPress(FixtureAnime.exactToWatch.titles.romaji)
        compose.onNode(status("On hold")).performClick()
        compose.onNodeWithText("Save").performClick()
        settle()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNode(inView("Could not save")).assertExists()
        compose.onNode(hasText("Retry")).assertDoesNotExist()
    }

    @Test
    fun `a save that fails from the open view offers Retry over the view`() {
        graph.demo.server.offline.value = true
        search()
        typeKag()
        longPress(FixtureAnime.exactToWatch.titles.romaji)
        compose.onNode(status("On hold")).performClick()
        compose.onNodeWithText("Save").performClick()
        settle()
        compose.onNode(inView("Could not save")).assertExists()
        compose.onNode(inView("Retry") and hasClickAction()).assertExists()
    }

    @Test
    fun `while the session is unknown a result offers no edit`() {
        graph.readsHeld = true
        val standIn = SearchFixtureStates.standIn(Fixtures.reference)
        val messages = ShellMessages(scope, graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SearchRoute(standIn, graph, messages, remember { SnackbarHostState() }, { _, _ -> }, onBrowse = {}, onSignIn = {})
            }
        }
        settle()
        typeKag()
        val result = compose.onNode(inView(FixtureAnime.exactToWatch.titles.romaji) and hasClickAction())
        result.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        graph.readsHeld = false
        settle()
        result.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `signed out, a browse row offers no edit`() {
        scope.launch { graph.session.signOut() }
        search()
        compose.onNodeWithText("Top airing").performClick()
        settle()
        val row = compose.onNode(hasText(topAiring, substring = true) and hasClickAction())
        row.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        row.performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `signed out, a result offers no edit`() {
        scope.launch { graph.session.signOut() }
        search()
        typeKag()
        val result = compose.onNode(inView(FixtureAnime.exactToWatch.titles.romaji) and hasClickAction())
        result.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        result.performTouchInput { longClick() }
        settle()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    // Under the bar, not in the search view.
    private fun underBar(text: String) = hasText(text) and !hasAnyAncestor(isDialog())

    @Test
    fun `searching a recent search again moves it to the top without a second row`() {
        search()
        val again = SearchFixtureStates.recent[2]
        compose.onNode(underBar(again)).performClick()
        settle()
        compose.onNode(hasContentDescription("Clear search") and !hasAnyAncestor(isDialog())).performClick()
        settle()
        val top = compose.onNode(underBar(again)).getUnclippedBoundsInRoot().top
        val first = compose.onNode(underBar(SearchFixtureStates.recent[0])).getUnclippedBoundsInRoot().top
        assertTrue(top < first)
        compose.onNode(underBar(SearchFixtureStates.recent[MAX_RECENT_SEARCHES - 1])).assertExists()
    }

    @Test
    fun `a query of spaces is no query, and is not kept`() {
        search()
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).performClick()
        settle()
        compose.onNode(typing).performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.Spacebar)
        }
        settle()
        compose.onNode(inView("Recent searches")).assertExists()
        compose.onNode(hasText("Nothing found", substring = true)).assertDoesNotExist()
        compose.onNode(typing).performImeAction()
        settle()
        compose.onNode(inView(SearchFixtureStates.recent[MAX_RECENT_SEARCHES - 1])).assertExists()
    }

    private companion object {
        const val SETTLE_ROUNDS = 5
    }
}

// The field's text and the recent searches survive a rotation and the process being recreated.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SearchRouteRestoreTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose))

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val messages = ShellMessages(scope, graph.list)

    @After
    fun stop() = scope.cancel()

    @Test
    fun `the search text and the recent searches are restored`() {
        val restore = StateRestorationTester(compose)
        val standIn = SearchFixtureStates.standIn(Fixtures.reference)
        restore.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SearchRoute(standIn, graph, messages, remember { SnackbarHostState() }, { _, _ -> }, onBrowse = {}, onSignIn = {})
            }
        }
        val searched = SearchFixtureStates.recent[3]
        compose.onNodeWithText(searched).performClick()
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText(searched)).assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitForIdle()
        val top = compose.onNodeWithText(searched).getUnclippedBoundsInRoot().top
        assertTrue(top < compose.onNodeWithText(SearchFixtureStates.recent[0]).getUnclippedBoundsInRoot().top)
    }
}
