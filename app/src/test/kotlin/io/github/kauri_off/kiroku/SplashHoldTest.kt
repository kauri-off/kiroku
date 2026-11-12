package io.github.kauri_off.kiroku

import android.os.Looper
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kauri_off.kiroku.core.demo.Demo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

// The system's splash covers the start until the List has rows or a state to show, so no indicator is seen at a start.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SplashHoldTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun stop() = scope.cancel()

    private fun view() = View(compose.activity)

    @Test
    fun `the splash stays while the List has nothing to show and goes once it has`() {
        val graph = TestGraph(Demo(Fixtures.reference, scope))
        graph.readsHeld = true
        val splash = SplashHold(compose.activity.window.decorView, atMost = 1.hours)
        compose.setContent {
            CompositionLocalProvider(
                LocalSplashHold provides splash,
            ) { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        }
        compose.waitForIdle()
        assertTrue("kept while the List loads", splash.keeping)
        graph.readsHeld = false
        compose.waitForIdle()
        assertFalse("kept once the List has rows", splash.keeping)
    }

    @Test
    fun `the splash stays for the patience at most`() {
        val view = view()
        val splash = SplashHold(view, atMost = PATIENCE)
        splash.hold()
        assertTrue(view.viewTreeObserver.dispatchOnPreDraw())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(PATIENCE.toJavaDuration().minus(Duration.ofMillis(1)))
        assertTrue("kept within the patience", splash.keeping)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse("kept after the patience", splash.keeping)
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
    }

    @Test
    fun `once the window has drawn nothing holds it again`() {
        val view = view()
        val splash = SplashHold(view, atMost = 1.hours)
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
        splash.hold()
        assertFalse(splash.keeping)
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
    }
}
