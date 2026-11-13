package io.github.kauri_off.kiroku.scenario

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

class HeldListApplication :
    Application(),
    AppGraphOwner {
    override val graph = TestGraph(Demo(Fixtures.reference, CoroutineScope(SupervisorJob() + Dispatchers.Main))).apply { readsHeld = true }

    override fun onCreate() {
        super.onCreate()
        ScenarioOverlay.install(this)
    }
}

// While the splash holds the window, the debug menu's tab waits, so it adds nothing to the splash's time.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp", application = HeldListApplication::class)
class ScenarioOverlayStartTest {
    // Runs the main looper for a while of its own clock; a held window asks for a frame at every one.
    private fun run(millis: Long) {
        val until = SystemClock.uptimeMillis() + millis
        var tasks = 0
        while (SystemClock.uptimeMillis() < until && tasks++ < MAX_TASKS) shadowOf(Looper.getMainLooper()).runOneTask()
    }

    @Test(timeout = 60_000)
    fun `the tab joins only after the splash has let the first frame be drawn`() {
        val application = RuntimeEnvironment.getApplication() as HeldListApplication
        val controller =
            Robolectric
                .buildActivity(MainActivity::class.java)
                .create()
                .start()
                .resume()
        // As the controller's visible(), without its idling, which never ends while every draw is held.
        val activity = controller.get()
        activity.window.attributes.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION
        ReflectionHelpers.setField(activity, "mDecor", activity.window.decorView)
        ReflectionHelpers.callInstanceMethod<Any>(activity, "makeVisible")
        run(HELD_MILLIS)
        assertEquals(emptyList<View>(), ScenarioOverlay.markers(activity))
        application.graph.readsHeld = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ScenarioOverlay.markers(activity).size)
        controller.pause().stop().destroy()
    }

    private companion object {
        const val HELD_MILLIS = 800L
        const val MAX_TASKS = 2_000
    }
}
