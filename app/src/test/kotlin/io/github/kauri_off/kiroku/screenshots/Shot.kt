package io.github.kauri_off.kiroku.screenshots

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.children
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.RoboComponent
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.hamcrest.Matchers
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

@OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
class Shot private constructor(
    private val controller: ActivityController<ComponentActivity>,
) : AutoCloseable {
    private val activity: ComponentActivity get() = controller.get()
    private val density: Float get() = activity.resources.displayMetrics.density

    fun image(): Bitmap {
        idle()
        val screen = RoboComponent.Screen(fetchRobolectricWindowRoots(), options).image ?: error("Nothing was drawn")
        val bitmap = screen.copy(Bitmap.Config.ARGB_8888, true)
        SystemBars.draw(Canvas(bitmap), activity.window, density)
        return bitmap
    }

    fun capture(file: File) = image().captureRoboImage(file, options)

    fun captureBounds(file: File) {
        val bitmap = image()
        val canvas = Canvas(bitmap)
        val paint =
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = BOUNDS_COLOR
            }
        val origin = IntArray(2)
        fetchRobolectricWindowRoots().forEach { root ->
            root.decorView.getLocationOnScreen(origin)
            root.decorView.composeRoots().forEach { compose ->
                compose.semanticsOwner.unmergedRootSemanticsNode.descendants().drop(1).forEach { node ->
                    val r = node.boundsInWindow
                    if (r.width > 0 && r.height > 0) {
                        canvas.drawRect(origin[0] + r.left, origin[1] + r.top, origin[0] + r.right - 1, origin[1] + r.bottom - 1, paint)
                    }
                }
            }
        }
        bitmap.captureRoboImage(file, options)
    }

    fun accessibilityProblems(): List<String> {
        idle()
        return fetchRobolectricWindowRoots().mapNotNull { root ->
            try {
                Espresso
                    .onView(ViewMatchers.isRoot())
                    .inRoot(RootMatchers.withDecorView(Matchers.`is`(root.decorView)))
                    .checkRoboAccessibility(accessibilityOptions, options)
                null
            } catch (e: AccessibilityViewCheckException) {
                e.results.joinToString("\n") { "${it.sourceCheckClass.simpleName}: ${it.getMessage(Locale.ENGLISH)}" }
            }
        }
    }

    fun scrollRangeDp(): Int {
        val node = mainVerticalScrollable() ?: return 0
        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
        val scrollBy = node.config[SemanticsActions.ScrollByOffset]
        val step = node.boundsInRoot.height
        var total = 0f
        while (range.value() < range.maxValue()) {
            var consumed: Offset? = null
            CoroutineScope(AndroidUiDispatcher.Main).launch { consumed = scrollBy(Offset(0f, step)) }
            var frames = 0
            while (consumed == null && frames++ < MAX_FRAMES) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(FRAME)
            }
            val moved = consumed?.y ?: error("Scrolling did not finish")
            if (moved < 0.5f) break
            total += moved
        }
        return ceil(total / density).toInt()
    }

    fun scrolledContentBottomPx(): Float = mainVerticalScrollable()?.descendants()?.drop(1)?.maxOfOrNull { it.boundsInWindow.bottom } ?: 0f

    val windowHeightPx: Int get() = activity.window.decorView.height

    fun dpToPx(dp: Int): Float = dp * density

    private fun mainVerticalScrollable(): SemanticsNode? {
        idle()
        return activity.window.decorView
            .composeRoots()
            .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
            .filter {
                SemanticsProperties.VerticalScrollAxisRange in it.config && SemanticsActions.ScrollByOffset in it.config
            }.maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
    }

    override fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    companion object {
        private val FRAME = Duration.ofMillis(16)
        private const val MAX_FRAMES = 600
        private const val BOUNDS_COLOR = 0xFFFF00FF.toInt()

        private val options = RoborazziOptions()

        private val accessibilityOptions =
            RoborazziATFAccessibilityCheckOptions(
                checker = RoborazziATFAccessibilityChecker(checks = setOf(TouchTargetSizeCheck(), SpeakableTextPresentCheck())),
                failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
            )

        fun launch(
            variant: Variant,
            heightDp: Int = variant.heightDp,
            content: @Composable () -> Unit,
        ): Shot {
            SystemBars.simulate()
            RuntimeEnvironment.setQualifiers(variant.qualifiers(heightDp))
            RuntimeEnvironment.setFontScale(variant.fontScale)
            Locale.setDefault(Locale.forLanguageTag(variant.language))
            TimeZone.setDefault(TimeZone.getTimeZone(FixtureTime.zone))
            val app = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))

            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
            val activity = controller.get()
            activity.enableEdgeToEdge()
            activity.window.isNavigationBarContrastEnforced = false
            activity.setContent { ScreenshotTheme(variant, content) }
            controller
                .start()
                .postCreate(null)
                .resume()
                .visible()
            idle()
            return Shot(controller)
        }

        private fun idle() {
            Espresso.onIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        private fun View.composeRoots(): List<ViewRootForTest> =
            when (this) {
                is ViewRootForTest -> listOf(this)
                is ViewGroup -> children.flatMap { it.composeRoots() }.toList()
                else -> emptyList()
            }

        private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
    }
}
