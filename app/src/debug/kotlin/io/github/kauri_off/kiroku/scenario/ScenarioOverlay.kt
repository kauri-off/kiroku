package io.github.kauri_off.kiroku.scenario

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.ThemeSettings
import io.github.kauri_off.kiroku.design.theme.isDark
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// While a scenario is on, the marker is over every full window of the app, sheets included; with all off, nothing is.
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
                val active = scenarios.scenario.map { it.active }.distinctUntilChanged()
                combine(active, windowsChanged(activity)) { on, _ -> on }.collect { on ->
                    if (on) show(activity, scenarios) else hide(activity)
                }
            }
        }
    }

    // The markers shown, one in each full window of the activity.
    fun markers(activity: Activity): List<View> = windows(activity).mapNotNull { it.findViewWithTag(TAG) }

    // A sheet or dialog opening or closing moves the focus between the activity's windows.
    private fun windowsChanged(activity: Activity) =
        callbackFlow {
            val decor = activity.window.decorView
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { trySend(Unit) }
            decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            send(Unit)
            awaitClose { decor.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }

    // A sheet's layout is raised for its shadow; the marker is drawn and touched above it.
    private fun show(
        activity: ComponentActivity,
        scenarios: Scenarios,
    ) {
        windows(activity).forEach { window ->
            val content = window.findViewById<ViewGroup>(android.R.id.content) ?: return@forEach
            val marker = content.findViewWithTag(TAG) ?: marker(activity, scenarios).also(content::addView)
            val above = content.children.filter { it !== marker }.maxOfOrNull { it.z } ?: 0f
            if (marker.z <= above) marker.translationZ = above - marker.elevation + 1
        }
    }

    private fun hide(activity: Activity) {
        markers(activity).forEach { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun marker(
        activity: ComponentActivity,
        scenarios: Scenarios,
    ) = ComposeView(activity).apply {
        tag = TAG
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setContent {
            val settings = ThemeSettings()
            KirokuTheme(settings.isDark(), settings.dynamicColor) {
                val scenario by scenarios.scenario.collectAsScreenState()
                EdgeMarker(scenario, visibleArea(), onClick = { activity.startActivity(ScenarioActivity.intent(activity)) })
            }
        }
    }

    // What the system bars and the cutout leave, not the keyboard: in landscape no margin beside a control is above it.
    @Composable
    private fun visibleArea(): Modifier {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
        }
        val insets = rootInsets()
        return with(LocalDensity.current) {
            Modifier.absolutePadding(insets.left.toDp(), insets.top.toDp(), insets.right.toDp(), insets.bottom.toDp())
        }
    }

    // The window's own insets, read again before every frame.
    @Composable
    private fun rootInsets(): Insets {
        val view = LocalView.current
        var insets by remember { mutableStateOf(view.rootInsets()) }
        DisposableEffect(view) {
            val listener =
                ViewTreeObserver.OnPreDrawListener {
                    view.rootInsets().let { if (it != insets) insets = it }
                    true
                }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        return insets
    }

    private fun View.rootInsets(): Insets =
        ViewCompat
            .getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: Insets.NONE

    // The activity's own windows that fill the screen: itself and its sheets and dialogs, not menus or tooltips.
    private fun windows(activity: Activity): List<View> {
        val own = activity.window.decorView
        val token = activity.window.attributes.token
        val others = processWindows().filter { it !== own && it.isAttachedToWindow && it.fillsScreen() && it.attributes?.token == token }
        return listOf(own) + others
    }

    private fun processWindows(): List<View> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WindowInspector.getGlobalWindowViews() else olderProcessWindows()

    // Below Android 10 the list of the process's windows is not public API; without it the marker stays on the activity.
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun olderProcessWindows(): List<View> =
        runCatching {
            val global = Class.forName(notShown("android.view.WindowManagerGlobal"))
            val instance = global.getMethod(notShown("getInstance")).invoke(null)
            (global.getDeclaredField(notShown("mViews")).apply { isAccessible = true }.get(instance) as List<*>).filterIsInstance<View>()
        }.getOrDefault(emptyList())

    private val View.attributes get() = layoutParams as? WindowManager.LayoutParams

    private fun View.fillsScreen(): Boolean =
        attributes?.let { it.width == WindowManager.LayoutParams.MATCH_PARENT && it.height == WindowManager.LayoutParams.MATCH_PARENT }
            ?: false

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
