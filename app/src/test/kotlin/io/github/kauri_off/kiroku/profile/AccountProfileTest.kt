package io.github.kauri_off.kiroku.profile

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock

// The real account's Profile is the Profile's own route, as on the demo and the sandbox.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class AccountProfileTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `on the real account, signed out, the Profile says to sign in to see the profile`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val graph = createGraph(RuntimeEnvironment.getApplication(), "", Clock.systemUTC(), scope, other = null)
        assertFalse(graph.madeUp)
        compose.setContent { AppRoute(graph) }
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sign in with MyAnimeList to see your profile here", useUnmergedTree = true).assertExists()
        scope.cancel()
    }
}
