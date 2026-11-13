package io.github.kauri_off.kiroku.screenshots

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
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
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import io.github.kauri_off.kiroku.PATIENCE
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.design.component.COUNTED_WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.scenario.MARKER_TAG
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
import kotlin.math.roundToInt

@OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
class Shot private constructor(
    private val controller: ActivityController<ComponentActivity>,
    private val keyboard: Boolean,
    private val restore: () -> Unit,
) : AutoCloseable {
    private val activity: ComponentActivity get() = controller.get()
    private val density: Float get() = activity.resources.displayMetrics.density

    fun image(): Bitmap {
        settle()
        val indicators = indeterminateIndicators()
        check(LoopingAnimations.running <= indicators.sumOf { it.loops }) {
            "${LoopingAnimations.running} looping animations run with ${indicators.size} progress indicators: " +
                "nothing but a progress indicator may loop (ARCHITECTURE section 8.5)"
        }
        val screen = RoboComponent.Screen(fetchRobolectricWindowRoots(), options).image ?: error("Nothing was drawn")
        val bitmap = screen.copy(Bitmap.Config.ARGB_8888, true)
        SystemBars.draw(Canvas(bitmap), activity.window, density, keyboard)
        return bitmap
    }

    // The window as drawn now, with nothing let to settle: a frame of a running animation.
    fun frame(): Bitmap {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val screen = RoboComponent.Screen(fetchRobolectricWindowRoots(), options).image ?: error("Nothing was drawn")
        val bitmap = screen.copy(Bitmap.Config.ARGB_8888, true)
        SystemBars.draw(Canvas(bitmap), activity.window, density, keyboard)
        return bitmap
    }

    // Under Robolectric what a wait brings is laid out only once the window is drawn.
    private fun patience() {
        advance(PATIENCE.inWholeMilliseconds + PATIENCE_FRAMES * FRAME.toMillis())
        windows().forEach { it.draw(Canvas(scratch)) }
        settle()
    }

    // Lets time pass on the main looper one frame at a time, as on a device.
    fun advance(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, FRAME.toMillis())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    fun boundsImage(): Bitmap {
        val bitmap = image()
        val canvas = Canvas(bitmap)
        val paint =
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = BOUNDS_COLOR
            }
        windows().forEach { decor ->
            val origin = decor.origin()
            decor.composeRoots().forEach { compose ->
                compose.semanticsOwner.unmergedRootSemanticsNode.descendants().drop(1).forEach { node ->
                    val r = node.boundsInWindow
                    if (r.width > 0 && r.height > 0) {
                        canvas.drawRect(origin[0] + r.left, origin[1] + r.top, origin[0] + r.right - 1, origin[1] + r.bottom - 1, paint)
                    }
                }
            }
        }
        return bitmap
    }

    fun accessibilityProblems(): List<String> = touchTargetProblems() + unlabeledProblems()

    fun unlabeledProblems(): List<String> {
        settle()
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

    // The laid-out size, not Compose's enlarged hit area. Exempt: the stock sheet's 32 dp drag handle and the debug marker.
    fun touchTargetProblems(): List<String> {
        settle()
        val minimum = TOUCH_TARGET_DP * density - 0.5f
        val dragHandle = activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description).visible()
        return windows().flatMap { decor ->
            decor.composeRoots().flatMap { compose ->
                compose.semanticsOwner.unmergedRootSemanticsNode
                    .descendants()
                    .filter { SemanticsActions.OnClick in it.config && it.layoutInfo.isPlaced && it.takesTouches() }
                    .filter { it.layoutInfo.width < minimum || it.layoutInfo.height < minimum }
                    .filterNot { it.label() == dragHandle || it.config.getOrNull(SemanticsProperties.TestTag) == MARKER_TAG }
                    .map { node ->
                        val width = node.layoutInfo.width / density
                        val height = node.layoutInfo.height / density
                        "TouchTargetSize: '${node.targetLabel()}' is ${"%.0f".format(Locale.ROOT, width)} × " +
                            "${"%.0f".format(Locale.ROOT, height)} dp, under $TOUCH_TARGET_DP dp"
                    }
            }
        }
    }

    // Every text field in every window, by its label, with whether autofill or a password manager is offered it.
    fun textFields(): List<Pair<String, Boolean>> {
        settle()
        return windows()
            .flatMap { it.composeRoots() }
            .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
            .filter { SemanticsProperties.EditableText in it.config }
            .map { node ->
                val config = node.config
                val offered =
                    config.getOrNull(SemanticsProperties.ContentDataType) != ContentDataType.None ||
                        SemanticsProperties.ContentType in config ||
                        SemanticsProperties.Password in config
                node.label() to offered
            }
    }

    // As a user drags it up: a sheet that opened half expanded is expanded, so all of it can be captured.
    fun expandSheets(): Boolean {
        val node = overlayNodes().firstOrNull { SemanticsActions.Expand in it.config } ?: return false
        node.config[SemanticsActions.Expand].action?.invoke()
        awaitOverlays()
        check(overlayNodes().none { SemanticsActions.Expand in it.config }) { "A sheet stays half expanded" }
        return true
    }

    // Draws until no open sheet or dialog moves: under Robolectric a sheet's animation advances only when drawn.
    private fun awaitOverlays() {
        var last = overlayNodes().map { it.positionInWindow }
        var still = 0
        var frames = 0
        while (last.isNotEmpty() && still < STILL_FRAMES && frames++ < MAX_FRAMES) {
            windows().forEach { it.draw(Canvas(scratch)) }
            val now = overlayNodes().map { it.positionInWindow }
            still = if (now == last) still + 1 else 0
            last = now
        }
    }

    // How far the open sheet's or dialog's content reaches below its window.
    fun overlayOverflowDp(): Float {
        val decor = windows().last()
        if (decor === activity.window.decorView) return 0f
        val bottom =
            decor
                .composeRoots()
                .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
                .filter { it.layoutInfo.isPlaced }
                .maxOfOrNull { it.positionInWindow.y + it.size.height } ?: return 0f
        return ((bottom - decor.height) / density).coerceAtLeast(0f)
    }

    private fun overlayNodes(): List<SemanticsNode> {
        settle()
        return windows()
            .filter { it !== activity.window.decorView }
            .flatMap { it.composeRoots() }
            .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
    }

    // Measured in the topmost window: a sheet or dialog when one is open, otherwise the screen.
    fun scrollRangeDp(): Int {
        val node = largestScrollable(windows().last(), SemanticsProperties.VerticalScrollAxisRange) ?: return 0
        val step = node.boundsInRoot.height
        var total = 0f
        // Content composed by the scroll is given the patience too, so a loading state in it has its indicator.
        repeat(2) {
            while (node.config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() < it.maxValue() }) {
                val moved = scrollBy(node, Offset(0f, step)).y
                if (moved < 0.5f) break
                total += moved
            }
            if (it == 0) patience()
        }
        return ceil(total / density).toInt()
    }

    fun stripCount(): Int = strips().size

    private fun indeterminateIndicators(): List<SemanticsNode> =
        windows()
            .flatMap { it.composeRoots() }
            .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
            .filter {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate ||
                    it.config.getOrNull(SemanticsProperties.TestTag) == COUNTED_WAVY_INDICATOR
            }

    private val SemanticsNode.loops: Int
        get() =
            when (config.getOrNull(SemanticsProperties.TestTag)) {
                WAVY_INDICATOR -> WAVY_LOOPS
                COUNTED_WAVY_INDICATOR -> 1
                ONE_LOOP_INDICATOR -> 1
                else -> 0
            }

    // Pages through one horizontal strip; with images, returns its pages stitched into one picture of every card.
    fun walkStrip(
        index: Int,
        withImages: Boolean,
        onPage: () -> Unit,
    ): Bitmap? {
        val segments = mutableListOf<Bitmap>()
        var consumed = 0
        while (true) {
            onPage()
            if (withImages) {
                val (decor, node) = strips()[index]
                val origin = decor.origin()
                val r = node.boundsInWindow
                val right = (origin[0] + r.right).roundToInt()
                val left = if (consumed == 0) (origin[0] + r.left).roundToInt() else right - consumed
                val top = (origin[1] + r.top).roundToInt()
                segments += Bitmap.createBitmap(image(), left, top, right - left, (origin[1] + r.bottom).roundToInt() - top)
            }
            val node = strips()[index].second
            val moved = scrollBy(node, Offset(node.boundsInRoot.width, 0f)).x
            if (moved < 0.5f) break
            consumed = moved.roundToInt()
        }
        if (!withImages) return null
        val stitched = Bitmap.createBitmap(segments.sumOf { it.width }, segments.maxOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)
        canvas.drawColor(BOUNDS_COLOR)
        var x = 0f
        segments.forEach {
            canvas.drawBitmap(it, x, 0f, null)
            x += it.width
        }
        return stitched
    }

    fun scrolledContentBottomPx(): Float =
        largestScrollable(windows().last(), SemanticsProperties.VerticalScrollAxisRange)
            ?.descendants()
            ?.drop(1)
            ?.maxOfOrNull { it.boundsInWindow.bottom } ?: 0f

    val windowHeightPx: Int get() = activity.window.decorView.height

    fun dpToPx(dp: Int): Float = dp * density

    private fun strips(): List<Pair<View, SemanticsNode>> =
        windows()
            .flatMap { decor ->
                decor
                    .composeRoots()
                    .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
                    .filter { it.isScrollable(SemanticsProperties.HorizontalScrollAxisRange) }
                    .filter { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f }
                    .map { decor to it }
            }.sortedWith(compareBy({ it.second.boundsInWindow.top }, { it.second.boundsInWindow.left }))

    private fun largestScrollable(
        decor: View,
        axis: SemanticsPropertyKey<ScrollAxisRange>,
    ): SemanticsNode? {
        settle()
        return decor
            .composeRoots()
            .flatMap { it.semanticsOwner.unmergedRootSemanticsNode.descendants() }
            .filter { it.isScrollable(axis) }
            .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
    }

    private fun scrollBy(
        node: SemanticsNode,
        offset: Offset,
    ): Offset {
        val scrollBy = node.config[SemanticsActions.ScrollByOffset]
        var consumed: Offset? = null
        CoroutineScope(AndroidUiDispatcher.Main).launch { consumed = scrollBy(offset) }
        var frames = 0
        while (consumed == null && frames++ < MAX_FRAMES) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(FRAME)
        }
        settle()
        return consumed ?: error("Scrolling did not finish")
    }

    private fun windows(): List<View> = fetchRobolectricWindowRoots().map { it.decorView }

    private fun settle() {
        idle()
        var changed = false
        windows().forEach { decor ->
            decor.window()?.let { changed = it.asOnPhone() || changed }
            if (keyboard) changed = showKeyboard(decor) || changed
        }
        if (changed) idle()
    }

    // As on a phone with gesture navigation: no scrim behind the handle, and an edge-to-edge sheet reaches the bottom.
    private fun Window.asOnPhone(): Boolean {
        var changed = false
        if (isNavigationBarContrastEnforced) {
            isNavigationBarContrastEnforced = false
            changed = true
        }
        val bars = WindowInsets.Type.systemBars()
        if (this !== activity.window && !decorFitsSystemWindows() && attributes.fitInsetsTypes and bars != 0) {
            attributes = attributes.apply { fitInsetsTypes = fitInsetsTypes and bars.inv() }
            changed = true
        }
        return changed
    }

    // Dispatched again before every image, because a later layout pass may apply the window's own insets.
    private fun showKeyboard(decor: View): Boolean {
        val insets = decor.rootWindowInsets ?: return false
        val keyboardTop = activity.window.decorView.height - dpToPx(SystemBars.KEYBOARD_DP)
        val overlap = (decor.origin()[1] + decor.height - keyboardTop).roundToInt().coerceAtLeast(0)
        val ime = WindowInsets.Type.ime()
        decor.dispatchApplyWindowInsets(
            WindowInsets
                .Builder(insets)
                .setInsets(ime, Insets.of(0, 0, 0, overlap))
                .setVisible(ime, true)
                .build(),
        )
        return true
    }

    override fun close() {
        controller.pause().stop().destroy()
        idle()
        RemoteImages.reset()
        LoopingAnimations.uninstall()
        restore()
    }

    companion object {
        const val TOUCH_TARGET_DP = 48

        // A wavy indicator runs two loops, its line and its wave, and is allowed them only when tagged as one.
        const val WAVY_LOOPS = 2

        // Marks a circular or flat indeterminate indicator, which runs one loop; the loading indicator runs none.
        const val ONE_LOOP_INDICATOR = "oneLoopIndicator"

        private val FRAME = Duration.ofMillis(16)
        private const val PATIENCE_FRAMES = 2
        private const val MAX_FRAMES = 600
        private const val STILL_FRAMES = 2
        private val scratch = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private const val BOUNDS_COLOR = 0xFFFF00FF.toInt()

        private val options = RoborazziOptions()

        fun write(
            bitmap: Bitmap,
            file: File,
        ) = bitmap.captureRoboImage(file, options)

        private val accessibilityOptions =
            RoborazziATFAccessibilityCheckOptions(
                checker = RoborazziATFAccessibilityChecker(checks = setOf(SpeakableTextPresentCheck())),
                failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
            )

        fun launch(
            variant: Variant,
            heightDp: Int = variant.heightDp,
            keyboard: Boolean = false,
            widthDp: Int = variant.widthDp,
            content: @Composable () -> Unit,
        ): Shot {
            val locale = Locale.getDefault()
            val zone = TimeZone.getDefault()
            SystemBars.simulate()
            RuntimeEnvironment.setQualifiers(variant.qualifiers(heightDp, widthDp))
            RuntimeEnvironment.setFontScale(variant.fontScale)
            Locale.setDefault(Locale.forLanguageTag(variant.language))
            TimeZone.setDefault(TimeZone.getTimeZone(FixtureTime.zone))
            val app = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            RemoteImages.install(app)
            LoopingAnimations.install()
            val restore = {
                Locale.setDefault(locale)
                TimeZone.setDefault(zone)
            }
            try {
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
                val activity = controller.get()
                activity.setKirokuContent(variant.themeSettings, variant.seed, content)
                controller
                    .start()
                    .postCreate(null)
                    .resume()
                    .visible()
                // As after a wait that outlasts the patience, and the frames that compose its indicator.
                return Shot(controller, keyboard, restore).apply {
                    settle()
                    patience()
                    awaitOverlays()
                }
            } catch (failure: Throwable) {
                RemoteImages.reset()
                LoopingAnimations.uninstall()
                restore()
                throw failure
            }
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

        private fun View.origin(): IntArray = IntArray(2).also { getLocationOnScreen(it) }

        private fun View.window(): Window? =
            generateSequence<Class<*>>(javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { Window::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(this) as? Window

        private fun Window.decorFitsSystemWindows(): Boolean =
            generateSequence<Class<*>>(javaClass) { it.superclass }
                .firstNotNullOf { type -> type.declaredFields.firstOrNull { it.name == "mDecorFitsSystemWindows" } }
                .apply { isAccessible = true }
                .getBoolean(this)

        // A click action without a click modifier serves only screen readers.
        private fun SemanticsNode.takesTouches(): Boolean =
            layoutInfo.getModifierInfo().any { touchModifier.containsMatchIn(it.modifier.javaClass.simpleName) }

        private val touchModifier = Regex("Clickable|Toggleable|Selectable")

        private fun SemanticsNode.isScrollable(axis: SemanticsPropertyKey<ScrollAxisRange>): Boolean =
            axis in config && SemanticsActions.ScrollByOffset in config

        private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

        private fun SemanticsNode.label(): String =
            descendants()
                .flatMap {
                    (it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) +
                        it.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .map { text -> text.text }
                }.joinToString(" ")
                .visible()
                .ifEmpty { UNLABELED }

        // A link inside a text has no label of its own, so it is named by its text.
        private fun SemanticsNode.targetLabel(): String =
            label().takeUnless { it == UNLABELED }
                ?: parent?.ownText()?.takeIf { it.isNotEmpty() }?.let { "link in $it" }
                ?: UNLABELED

        private fun SemanticsNode.ownText(): String =
            config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString(" ") { it.text }
                .visible()

        private const val UNLABELED = "unlabeled control"

        // Robolectric wraps some library strings in bidirectional marks.
        private fun String.visible(): String = filterNot { Character.getType(it) == Character.FORMAT.toInt() }
    }
}
