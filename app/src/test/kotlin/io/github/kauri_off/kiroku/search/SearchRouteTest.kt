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
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.BrowseSession
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SearchRouteTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose) { scope.cancel() })

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    private fun settle() =
        repeat(SETTLE_ROUNDS) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }

    // The search view's field, which has the focus once the bar is tapped.
    private val typing = hasSetTextAction() and isFocused() and hasAnyAncestor(isDialog())

    private fun inView(text: String) = hasText(text) and hasAnyAncestor(isDialog())

    private fun search(source: AppGraph = graph) {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(source) } }
        settle()
        compose.onNodeWithText("Search").performClick()
        settle()
    }

    @Test
    fun `the field shows keystrokes immediately and results follow after the typing pause`() {
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
        compose.onNode(inView(FixtureAnime.exactCaughtUp.titles.romaji)).assertDoesNotExist()
        settle()
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

    @Test
    fun `search without fixtures opens a result with its title and the current list status`() {
        val anime = FixtureAnime.exactToWatch
        graph.titlesUnstored.value = true
        graph.refreshFails.value = setOf(anime.id)
        val source =
            object : AppGraph by graph {
                override val madeUp = false
            }
        search(source)
        compose.onNodeWithText("Top airing").assertExists()
        typeKag()
        compose.onNode(inView(watching)).assertExists()
        compose.onNode(inView(anime.titles.romaji) and hasClickAction()).performClick()
        settle()
        compose.onNodeWithText(anime.titles.romaji).assertExists()
    }

    @Test
    fun `a search that starts offline retries when the connection returns`() {
        graph.demo.server.offline.value = true
        search()
        typeKag()
        compose.onNode(inView("No connection")).assertExists()
        compose.onNode(inView("Retry")).assertExists()
        graph.demo.server.offline.value = false
        settle()
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

    @Test
    fun `an open search changes its title labels when the preference changes`() {
        search()
        typeKag()
        val anime = FixtureAnime.exactToWatch
        compose.onNode(inView(anime.titles.romaji)).assertExists()
        scope.launch { graph.preferences.setTitleLanguage(TitleLanguage.English) }
        settle()
        compose.onNode(inView(anime.titles.preferred(TitleLanguage.English))).assertExists()
    }

    private val topAiring = SearchFixtureStates.browseRows(BrowseList.TopAiring).first().title

    @Test
    fun `browse without fixtures retries the failed page and opens a title with its current entry`() {
        val anime = FixtureAnime.exactToWatch
        val title = anime.titles.romaji
        val requests = mutableListOf<Triple<BrowseList, Int, Boolean>>()
        graph.titlesUnstored.value = true
        graph.refreshFails.value = setOf(anime.id)
        val source =
            object : AppGraph by graph {
                override val madeUp = false
                override val search =
                    object : SearchRepository by graph.search {
                        override fun openBrowse(list: BrowseList): BrowseSession =
                            BrowseSession { offset, includeAdult ->
                                requests += Triple(list, offset, includeAdult)
                                when (requests.size) {
                                    1 -> Outcome.Failure(DataError.ServerError)
                                    2 -> Outcome.Success(Page(listOf(Ranked(4, anime)), 17))
                                    3 -> Outcome.Failure(DataError.ServerError)
                                    else -> Outcome.Success(Page(listOf(Ranked(18, FixtureAnime.film)), null))
                                }
                            }
                    }
            }
        scope.launch { graph.preferences.setAdultInSearch(true) }
        settle()
        search(source)
        compose.onNodeWithText("Most popular").performClick()
        settle()
        compose.onNodeWithText("Retry").performClick()
        settle()
        compose.onNodeWithText("4.\u00A0$title").assertExists()
        compose.onNodeWithText("Retry").performClick()
        settle()
        compose.onNodeWithText("18.\u00A0${FixtureAnime.film.titles.romaji}").assertExists()
        assertEquals(listOf(0, 0, 17, 17).map { Triple(BrowseList.MostPopular, it, true) }, requests)

        val row = hasText("4.\u00A0$title") and hasClickAction()
        compose.onNode(row and hasText(watching)).assertExists()
        scope.launch { graph.list.write(ListWrite.Remove(anime)) }
        settle()
        compose.onNode(row and hasText(watching)).assertDoesNotExist()
        compose.onNode(row).performClick()
        settle()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        compose.onNode(row).assertExists()
        assertEquals(4, requests.size)
    }

    @Test
    fun `suggestions appear only for a signed-in session and belong to the sign-in that opened them`() {
        val current = MutableStateFlow<Session>(Session.SignedOut)
        val anime = FixtureAnime.exactToWatch
        val title = anime.titles.romaji
        val requests = mutableListOf<Pair<SignInId, Int>>()
        val source =
            object : AppGraph by graph {
                override val session =
                    object : SessionRepository by graph.session {
                        override fun observeSession() = current
                    }
                override val search =
                    object : SearchRepository by graph.search {
                        override fun openSuggestions(owner: SignInId): BrowseSession =
                            BrowseSession { offset, _ ->
                                requests += owner to offset
                                when (requests.size) {
                                    1 -> Outcome.Failure(DataError.ServerError)
                                    2 -> Outcome.Success(Page(listOf(Ranked(null, anime)), 17))
                                    3 -> Outcome.Failure(DataError.ServerError)
                                    else -> Outcome.Success(Page(listOf(Ranked(null, FixtureAnime.film)), null))
                                }
                            }
                    }
            }
        val owner = Fixtures.reference.session.signIn
        search(source)
        compose.onNodeWithText("Suggestions for you").assertDoesNotExist()
        compose.onNodeWithText("Top rated").assertExists()
        current.value = Session.Expired(owner)
        settle()
        compose.onNodeWithText("Suggestions for you").assertDoesNotExist()
        current.value = Session.SignedIn(owner)
        settle()
        compose.onNodeWithText("Suggestions for you").performClick()
        settle()
        compose.onNodeWithText("Retry").performClick()
        settle()
        compose.onNodeWithText("Retry").performClick()
        settle()
        compose.onNodeWithText(FixtureAnime.film.titles.romaji).assertExists()

        val row = hasText(title) and hasClickAction()
        compose.onNode(row and hasText(watching)).assertExists()
        compose.onNode(row).performClick()
        settle()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        compose.onNode(row).assertExists()
        assertEquals(listOf(0, 0, 17, 17).map { owner to it }, requests)

        current.value = Session.SignedIn(SignInId("replacement"))
        settle()
        compose.onNodeWithText("Suggestions are unavailable for this session").assertExists()
        compose.onNode(row).assertDoesNotExist()
        assertEquals(4, requests.size)
    }

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
        search()
        typeKag()
        graph.demo.server.offline.value = true
        longPress(FixtureAnime.exactToWatch.titles.romaji)
        compose.onNode(status("On hold")).performClick()
        compose.onNodeWithText("Save").performClick()
        settle()
        compose.onNode(inView("Could not save")).assertExists()
        compose.onNode(inView("Retry") and hasClickAction()).assertExists()
        // The page's own host steps aside while the view shows the message.
        compose.onNode(hasText("Could not save") and !hasAnyAncestor(isDialog())).assertDoesNotExist()
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
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose) { scope.cancel() })

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val messages = ShellMessages(scope, graph.list)

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
