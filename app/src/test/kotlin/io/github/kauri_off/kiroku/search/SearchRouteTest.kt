package io.github.kauri_off.kiroku.search

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
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

    private companion object {
        const val SETTLE_ROUNDS = 5
    }
}
