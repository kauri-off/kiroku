package io.github.kauri_off.kiroku.routes

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
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

// A text field shows each keystroke while no view model coroutine runs: nothing asynchronous sits in between.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class TypedTextTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose))

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val kagerou = FixtureAnime.exactToWatch

    @After
    fun stop() = scope.cancel()

    // Lets the view models' coroutines run, which every step below holds back.
    private fun settle() =
        repeat(SETTLE_ROUNDS) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }

    private fun app() {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        settle()
    }

    @Test
    fun `the List's filter field shows every keystroke before the List's state follows`() {
        app()
        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performKeyInput {
            pressKey(Key.K)
            pressKey(Key.A)
            pressKey(Key.G)
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("kag")).assertExists()
        settle()
        compose.onNode(hasSetTextAction() and hasText("kag")).assertExists()
        compose.onNodeWithText(kagerou.titles.romaji).assertExists()
    }

    @Test
    fun `the edit sheet follows every keystroke of its count field with nothing else running`() {
        app()
        compose.onNodeWithText(kagerou.titles.romaji).performTouchInput { longClick() }
        settle()
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched"))
        field.performClick()
        field.performTextClearance()
        field.performKeyInput {
            pressKey(Key.Two)
            pressKey(Key.Four)
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("24")).assertExists()
        compose.onNode(hasText("Completed") and isToggleable()).assertIsOn()
    }

    private companion object {
        const val SETTLE_ROUNDS = 5
    }
}
