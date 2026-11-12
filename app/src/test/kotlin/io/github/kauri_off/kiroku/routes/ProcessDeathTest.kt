package io.github.kauri_off.kiroku.routes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Process death: the saved state comes back, every view model is new.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ProcessDeathTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    @After
    fun stop() = scope.cancel()

    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun `the list's tab and an open edit sheet with a typed count come back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNode(hasText("Completed") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(FixtureAnime.prequel.titles.romaji).performTouchInput { longClick() }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched"))
        field.performTextReplacement("1")
        field.performTextReplacement("17")
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("17")).assertExists()
        compose.onNode(hasText("Completed") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
    }
}
