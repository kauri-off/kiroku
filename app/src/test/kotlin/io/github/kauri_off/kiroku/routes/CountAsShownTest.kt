package io.github.kauri_off.kiroku.routes

import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

// A screen counts as shown while it is on top and started (ARCHITECTURE 7.1), in the app's real routes.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class CountAsShownTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
    private val graph = TestGraph(Demo(Fixtures.reference, scope, clock = clock))
    private lateinit var navigator: ShellNavigator

    private val beneath = FixtureAnime.exactToWatch

    // Not of the chain of the page beneath, whose walk would read the page beneath.
    private val onTop = FixtureAnime.exactCaughtUp

    private fun app() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, SnackbarHostState())
            }
        }
        compose.waitForIdle()
    }

    // The app below started, as under another app or with the screen off, for [seconds] of the main thread's time.
    private fun away(seconds: Long) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    private fun back() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun age() {
        clock.instant += Duration.ofDays(1)
    }

    @Test
    fun `the app back from the background past the stop timeout loads a stale List once, under it nothing, and nothing while away`() {
        app()
        assertEquals(1, graph.syncs)
        age()
        away(3)
        back()
        assertEquals("under the stop timeout", 1, graph.syncs)
        away(6)
        back()
        assertEquals("past it, stale", 2, graph.syncs)

        graph.demo.server.offline.value = true
        age()
        away(6)
        back()
        assertEquals("failed for the connection", 3, graph.syncs)
        away(1)
        graph.demo.server.offline.value = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("the connection returned while away", 3, graph.syncs)
        back()
        assertEquals("repeated once back", 4, graph.syncs)
    }

    @Test
    fun `a title page under another asks nothing while a back gesture shows it, and loads once it is on top`() {
        app()
        compose.runOnUiThread {
            navigator.open(TitleKey(beneath.id.value))
            navigator.open(TitleKey(onTop.id.value))
        }
        compose.waitForIdle()
        assertEquals(listOf(onTop.id), graph.refreshed)

        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue(
            "the gesture shows no preview: the page on top stays",
            compose.onAllNodesWithText(onTop.titles.romaji).fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(listOf(onTop.id), graph.refreshed)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(listOf(onTop.id), graph.refreshed)

        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(listOf(onTop.id, beneath.id, FixtureAnime.premiereYear.id, FixtureAnime.prequel.id), graph.refreshed)
    }
}
