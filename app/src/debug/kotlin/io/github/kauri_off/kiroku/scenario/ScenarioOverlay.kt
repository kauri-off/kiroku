package io.github.kauri_off.kiroku.scenario

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.ThemeSettings
import io.github.kauri_off.kiroku.design.theme.isDark
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// While a scenario is on, the marker floats over the app's screens, centred on their top app bar, where no screen has
// a control; with every scenario off the app's views are those of a release build.
object ScenarioOverlay : Application.ActivityLifecycleCallbacks {
    private val TAG = notShown("scenario-marker")

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (activity !is MainActivity) return
        val scenarios = activity.scenarios ?: return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scenarios.scenario.map { it.active }.distinctUntilChanged().collect { active ->
                    if (active) show(activity, scenarios) else hide(activity)
                }
            }
        }
    }

    // The marker's view, when it is shown.
    fun marker(activity: Activity): View? = activity.window.decorView.findViewWithTag(TAG)

    private fun show(
        activity: ComponentActivity,
        scenarios: Scenarios,
    ) {
        if (marker(activity) != null) return
        val view =
            ComposeView(activity).apply {
                tag = TAG
                setContent {
                    val settings = ThemeSettings()
                    KirokuTheme(settings.isDark(), settings.dynamicColor) {
                        val scenario by scenarios.scenario.collectAsScreenState()
                        FloatingScenarioMarker(scenario, onClick = { activity.startActivity(ScenarioActivity.intent(activity)) })
                    }
                }
            }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        activity.addContentView(view, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private fun hide(activity: Activity) {
        marker(activity)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
