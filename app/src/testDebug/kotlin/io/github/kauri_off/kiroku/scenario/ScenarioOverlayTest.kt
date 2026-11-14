package io.github.kauri_off.kiroku.scenario

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.children
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.PATIENCE
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.debugmenu.DebugMenuActivity
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.toJavaDuration

// The debug menu's tab is over the app from after its first frame on, sheets included, and turns red while a scenario is on.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ScenarioOverlayTest {
    @get:Rule(order = 0)
    val source = StartsOn(DataSource.Demo)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val scenarios get() = checkNotNull(RuntimeEnvironment.getApplication().scenarios)

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun markers() = ScenarioOverlay.markers(compose.activity)

    @Test
    fun `the tab opens the menu, and is spoken as the menu, or with the scenarios' names while any is on`() {
        settle()
        assertEquals(1, markers().size)
        compose.onNodeWithContentDescription("Debug menu").performClick()
        assertEquals(DebugMenuActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)

        scenarios.set(Scenario(offline = true, slow = true))
        settle()
        assertEquals(1, markers().size)
        compose.onNodeWithContentDescription("Simulated: Offline\u00A0· Slow").performClick()
        assertEquals(DebugMenuActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)

        compose.activityRule.scenario.recreate()
        settle()
        assertEquals(1, markers().size)

        scenarios.set(Scenario())
        settle()
        assertEquals(1, markers().size)
        compose.onNodeWithContentDescription("Debug menu").assertExists()
    }

    @Test
    fun `the tab joins the app only after its first frame, so start-up draws what a release build draws`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<View>(), ScenarioOverlay.markers(controller.get()))

        controller.resume().visible()
        // The first frame is drawn once the List has its rows, at the latest when the splash's patience is out.
        shadowOf(Looper.getMainLooper()).idleFor(PATIENCE.toJavaDuration())
        assertEquals(1, ScenarioOverlay.markers(controller.get()).size)
        controller.pause().stop().destroy()
    }

    @Test
    fun `a finger takes the tab within the side margin at the end edge, and a tap just outside it reaches the row beneath`() =
        tapsAtTheMargin(rtl = false)

    // The ldrtl qualifier leaves the layout direction left to right under Robolectric, so the window is set to it.
    @Test
    fun `in a right-to-left layout the tab is at the left edge, within the side margin`() = tapsAtTheMargin(rtl = true)

    private fun tapsAtTheMargin(rtl: Boolean) {
        if (rtl) compose.activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        settle()
        val density = compose.activity.resources.displayMetrics.density
        val margin = Spacing.l.value * density
        val width = compose.activity.window.decorView.width
        val tab = compose.onNodeWithTag(MARKER_TAG).fetchSemanticsNode().boundsInWindow
        assertEquals(margin, tab.width, 1f)
        assertEquals(if (rtl) 0f else width.toFloat(), if (rtl) tab.left else tab.right, 1f)
        val inside = if (rtl) margin - 1 else width - margin + 1
        val outside = if (rtl) margin + 1 else width - margin - 1
        val y = tab.center.y

        tap(inside, y)
        assertEquals(DebugMenuActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity?.component?.className)

        compose.onNodeWithText("My list").assertExists()
        tap(outside, y)
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithText("My list").assertDoesNotExist()
    }

    @Test
    fun `a drag that starts on the tab moves the List's pages, as if it began on the List`() {
        settle()
        val tab = compose.onNodeWithTag(MARKER_TAG).fetchSemanticsNode().boundsInWindow
        compose.onNodeWithText("Watching").assertIsSelected()
        val step = compose.activity.resources.displayMetrics.density * Spacing.xl.value
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, tab.center.x, tab.center.y, start, start)
        for (i in 1..DRAG_STEPS) touch(MotionEvent.ACTION_MOVE, tab.center.x - i * step, tab.center.y, start, start + i * FRAME)
        touch(MotionEvent.ACTION_UP, tab.center.x - DRAG_STEPS * step, tab.center.y, start, start + (DRAG_STEPS + 1) * FRAME)
        settle()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithText("Planned").assertIsSelected()
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        val time = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, time, time)
        touch(MotionEvent.ACTION_UP, x, y, time, time + FRAME)
        settle()
    }

    // A touchscreen finger, as the system sends it; Compose widens small targets only for these.
    private fun touch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        time: Long,
    ) {
        val pointer = MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords =
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime,
                time,
                action,
                1,
                arrayOf(pointer),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        compose.activity.window.decorView
            .dispatchTouchEvent(event)
        event.recycle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the tab is over a sheet too`() {
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
    }

    // Robolectric moves no window focus when a sheet opens; the system moves it from the activity to the sheet.
    private fun focusLeavesActivity() {
        val root = compose.activity.window.decorView.parent
        root.javaClass.getMethod("windowFocusChanged", Boolean::class.javaPrimitiveType).invoke(root, false)
    }

    private companion object {
        const val DRAG_STEPS = 8
        const val FRAME = 16L
    }
}
