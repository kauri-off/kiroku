package io.github.kauri_off.kiroku.routes

import androidx.activity.BackEventCompat
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.onAllNodesWithText
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A screen counts as shown only on top: one that a back gesture uncovers asks MAL nothing until the gesture ends.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class ShownOnTopTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private lateinit var navigator: ShellNavigator

    private val beneath = FixtureAnime.exactToWatch
    private val onTop = FixtureAnime.premiereYear

    @After
    fun stop() = scope.cancel()

    @Test
    fun `a title page under another asks nothing while a back gesture shows it, and loads once it is on top`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, SnackbarHostState())
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            navigator.open(TitleKey(beneath.id.value))
            navigator.open(TitleKey(onTop.id.value))
        }
        compose.waitForIdle()
        assertEquals(1, graph.refreshes)

        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue(
            "the page under the gesture is drawn",
            compose.onAllNodesWithText(beneath.titles.romaji).fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(1, graph.refreshes)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(1, graph.refreshes)

        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(2, graph.refreshes)
    }
}
