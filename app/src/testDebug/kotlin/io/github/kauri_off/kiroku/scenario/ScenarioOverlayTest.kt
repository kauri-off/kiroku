package io.github.kauri_off.kiroku.scenario

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.children
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The marker is over the app exactly while a scenario is on, sheets included; with all off the app's views are a release build's.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ScenarioOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val scenarios get() = checkNotNull(RuntimeEnvironment.getApplication().scenarios)

    @Before
    fun demoMode() = assumeTrue(BuildConfig.MAL_CLIENT_ID.isBlank())

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun markers() = ScenarioOverlay.markers(compose.activity)

    @Test
    fun `the marker shows while a scenario is on, is spoken with their names, and opens the panel`() {
        settle()
        assertEquals(emptyList<View>(), markers())

        scenarios.set(Scenario(offline = true, slow = true))
        settle()
        assertEquals(1, markers().size)
        compose.onNodeWithContentDescription("Simulated: Offline\u00A0· Slow").performClick()
        assertEquals(ScenarioActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)

        compose.activityRule.scenario.recreate()
        settle()
        assertEquals(1, markers().size)

        scenarios.set(Scenario())
        settle()
        assertEquals(emptyList<View>(), markers())
    }

    @Test
    fun `the marker takes a touch only on its tab, at the window's end edge, and lets every other through`() {
        scenarios.set(Scenario(offline = true))
        settle()
        val marker = markers().single()
        val tab = compose.onNodeWithContentDescription("Simulated: Offline").fetchSemanticsNode().boundsInWindow
        assertEquals(marker.width.toFloat(), tab.right, 1f)
        assertTrue(tab.width < marker.resources.displayMetrics.density * Spacing.l.value)

        assertFalse(marker.dispatchTouchEvent(down(marker.width / 2f, marker.height / 2f)))
        assertFalse(marker.dispatchTouchEvent(down(tab.left - 1, tab.center.y)))
        assertTrue(marker.dispatchTouchEvent(down(tab.center.x, tab.center.y)))
    }

    @Test
    fun `the marker is over a sheet too, and leaves with it`() {
        scenarios.set(Scenario(offline = true))
        settle()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji).performTouchInput { longClick() }
        settle()
        focusLeavesActivity()
        settle()
        assertEquals(2, markers().size)
        markers().forEach { marker ->
            val siblings = (marker.parent as ViewGroup).children.filter { it !== marker }
            assertTrue(siblings.all { it.z < marker.z })
        }

        scenarios.set(Scenario())
        settle()
        assertEquals(emptyList<View>(), markers())
    }

    // Robolectric moves no window focus when a sheet opens; the system moves it from the activity to the sheet.
    private fun focusLeavesActivity() {
        val root = compose.activity.window.decorView.parent
        root.javaClass.getMethod("windowFocusChanged", Boolean::class.javaPrimitiveType).invoke(root, false)
    }

    private fun down(
        x: Float,
        y: Float,
    ): MotionEvent = SystemClock.uptimeMillis().let { MotionEvent.obtain(it, it, MotionEvent.ACTION_DOWN, x, y, 0) }
}
