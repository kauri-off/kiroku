package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.Composable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class ScreenshotCase internal constructor(
    val name: String,
    val isContent: Boolean,
    val keyboard: Boolean,
    val window: WindowSize?,
    val content: @Composable () -> Unit,
)

// A window size that replaces the variant's own, such as a wide window; language, theme and font scale stay.
data class WindowSize(
    val widthDp: Int,
    val heightDp: Int,
)

fun contentCase(
    name: String = "content",
    keyboard: Boolean = false,
    window: WindowSize? = null,
    content: @Composable () -> Unit,
) = ScreenshotCase(name, isContent = true, keyboard, window, content)

fun case(
    name: String,
    keyboard: Boolean = false,
    window: WindowSize? = null,
    content: @Composable () -> Unit,
) = ScreenshotCase(name, isContent = false, keyboard, window, content)

sealed interface StateCase

class Shown internal constructor(
    val content: @Composable () -> Unit,
) : StateCase

class NotShown internal constructor(
    val reason: String,
) : StateCase

fun shown(content: @Composable () -> Unit): StateCase = Shown(content)

fun notShown(reason: String): StateCase = NotShown(reason)

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
sealed class RegistryBase {
    val screen: String get() = screenName(javaClass)

    internal abstract fun shape(): RegistryShape

    // The transitions this screen animates, each rendered as a frames image next to its screenshots.
    open val transitions: List<Transition> = emptyList()

    @Test
    fun structure() = assertEquals(emptyList<String>(), shape().problems())

    @Test
    fun render() {
        assumeTrue("Screenshots are rendered only with -Proborazzi.test.record=true", isRecording)
        val shape = shape()
        val structure = shape.problems()
        if (structure.isNotEmpty()) fail(structure.joinToString("\n"))
        val problems =
            renderScreen(screen, shape.cases, File(outputRoot, screen), notShown = shape.notShown) +
                transitions.flatMap { renderTransition(it, shape.cases, File(outputRoot, screen)) }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    // Robolectric drops what a test leaves on the main looper, and Compose's dispatcher then stalls in every later class.
    @After
    fun runMainLooper() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    companion object {
        val isRecording: Boolean get() = System.getProperty("roborazzi.test.record") == "true"

        val outputRoot: File get() = File(System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi")
    }
}

// A screen declares its loading, empty and error cases, or says why it has none.
abstract class ScreenshotRegistry : RegistryBase() {
    abstract val content: ScreenshotCase
    abstract val loading: StateCase
    abstract val empty: StateCase
    abstract val error: StateCase
    open val cases: List<ScreenshotCase> = emptyList()

    final override fun shape(): RegistryShape {
        val states = listOf("loading" to loading, "empty" to empty, "error" to error)
        return RegistryShape(
            screen = screen,
            showcase = false,
            cases =
                listOf(content) +
                    states.mapNotNull { (name, state) -> (state as? Shown)?.let { case(name, content = it.content) } } +
                    cases,
            extraCases = cases,
            notShown = states.mapNotNull { (name, state) -> (state as? NotShown)?.let { name to it.reason } },
        )
    }
}

// Only the sample screen and the component gallery, which are not screens of the app, may skip the state cases.
abstract class ShowcaseRegistry : RegistryBase() {
    abstract val cases: List<ScreenshotCase>

    final override fun shape() = RegistryShape(screen, showcase = true, cases = cases, extraCases = cases, notShown = emptyList())
}

internal class RegistryShape(
    val screen: String,
    val showcase: Boolean,
    val cases: List<ScreenshotCase>,
    val extraCases: List<ScreenshotCase>,
    val notShown: List<Pair<String, String>>,
) {
    fun problems(): List<String> =
        buildList {
            if (!screenPattern.matches(screen)) add("The registry's class name does not end in Screenshots: '$screen'")
            if (showcase && screen !in showcases) add("$screen is a screen: extend ScreenshotRegistry, not ShowcaseRegistry")
            if (!showcase && extraCases.any { it.isContent }) add("$screen declares its content case in 'cases'")
            if (!showcase && extraCases.any { it.name in stateNames }) add("$screen declares a state case in 'cases'")
            if (cases.count { it.isContent } != 1) add("$screen declares ${cases.count { it.isContent }} content cases, not one")
            if (cases.map { it.name }.toSet().size != cases.size) add("$screen declares a case twice")
            cases.filterNot { caseName.matches(it.name) }.forEach { add("Case name '${it.name}' is not lower-case-with-hyphens") }
            for ((state, reason) in notShown.filterNot { isReason(it.second) }) {
                add("$screen gives no reason for having no $state case: '$reason'")
            }
        }

    companion object {
        val showcases = setOf("sample", "gallery")
        private val stateNames = setOf("loading", "empty", "error")
        private val caseName = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val screenPattern = caseName
        private val placeholder = Regex("""\b(todo|tbd|fixme|xxx|n/a)\b""", RegexOption.IGNORE_CASE)

        // A sentence a reviewer can judge: at least two words and no placeholder.
        fun isReason(text: String): Boolean =
            text.split(Regex("""\s+""")).count { word -> word.count(Char::isLetter) >= 2 } >= 2 && !placeholder.containsMatchIn(text)
    }
}

const val NOT_SHOWN_FILE = "NOT_SHOWN.txt"
const val ACCESSIBILITY_FILE = "ACCESSIBILITY.txt"

internal fun screenName(registry: Class<*>): String {
    val base = registry.simpleName.removeSuffix("Screenshots")
    if (base == registry.simpleName) return ""
    return base.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "-").lowercase(Locale.ROOT)
}

internal fun renderScreen(
    screen: String,
    cases: List<ScreenshotCase>,
    dir: File,
    variantsOf: (ScreenshotCase) -> List<Variant> = { Variant.forCase(it.isContent) },
    notShown: List<Pair<String, String>> = emptyList(),
    write: (Bitmap, File) -> Unit = Shot::write,
): List<String> {
    dir.deleteRecursively()
    if (notShown.isNotEmpty()) {
        dir.mkdirs()
        File(dir, NOT_SHOWN_FILE).writeText(notShown.joinToString("") { (state, reason) -> "$state: $reason\n" })
    }
    val problems = mutableListOf<String>()
    val started = System.nanoTime()
    var images = 0
    for (case in cases) {
        for (variant in variantsOf(case)) {
            val prefix = "${case.name}__${variant.id}"
            val finish = { shot: Shot, full: Boolean ->
                val overflow = shot.overlayOverflowDp()
                check(overflow < 1f) { "$screen/$prefix: the open sheet or dialog runs ${overflow.roundToInt()} dp past the window" }
                if (full) {
                    write(shot.image(), File(dir, "${prefix}__full.png"))
                    images++
                }
                images += finishCase(shot, case, variant, File(dir, prefix), write) { problems += "$screen/${case.name}: $it" }
            }
            var extraDp = 0
            val widthDp = case.window?.widthDp ?: variant.widthDp
            val heightDp = case.window?.heightDp ?: variant.heightDp
            Shot.launch(variant, heightDp, case.keyboard, widthDp, case.content).use { shot ->
                write(shot.image(), File(dir, "$prefix.png"))
                images++
                if (variant == Variant.EnLight && case.isContent) {
                    write(shot.boundsImage(), File(dir, "${case.name}__bounds.png"))
                    images++
                }
                val expanded = shot.expandSheets()
                extraDp = shot.scrollRangeDp()
                if (extraDp == 0) finish(shot, expanded)
            }
            if (extraDp > 0) renderFull(variant, widthDp, heightDp + extraDp, case) { finish(it, true) }
        }
    }
    val seconds = (System.nanoTime() - started) / 1e9
    println("Rendered $screen: $images images in ${"%.1f".format(Locale.ROOT, seconds)} s")
    val found = problems.distinct()
    dir.mkdirs()
    File(dir, ACCESSIBILITY_FILE).writeText(
        if (found.isEmpty()) {
            "passed; cases checked: ${cases.size}; touch targets in every variant, labels in en-light\n"
        } else {
            found.joinToString("\n", postfix = "\n")
        },
    )
    return found
}

// Runs on the shot that shows everything: the touch-target check in every variant, the label check in en-light,
// and each strip, stitched for the content case.
private fun finishCase(
    shot: Shot,
    case: ScreenshotCase,
    variant: Variant,
    prefix: File,
    write: (Bitmap, File) -> Unit,
    report: (String) -> Unit,
): Int {
    val check = {
        val found = if (variant == Variant.EnLight) shot.accessibilityProblems() else shot.touchTargetProblems()
        found.forEach { report("${variant.id}: $it") }
    }
    check()
    var images = 0
    repeat(shot.stripCount()) { index ->
        val stitched = shot.walkStrip(index, withImages = case.isContent, onPage = check)
        if (stitched != null) {
            write(stitched, File(prefix.parentFile, "${prefix.name}__strip-${index + 1}.png"))
            images++
        }
    }
    return images
}

// Grows the window until nothing is left to scroll, then hands over the shot that shows everything.
private fun renderFull(
    variant: Variant,
    widthDp: Int,
    heightDp: Int,
    case: ScreenshotCase,
    finish: (Shot) -> Unit,
) {
    var height = heightDp
    repeat(3) {
        val rest =
            Shot.launch(variant, height, case.keyboard, widthDp, case.content).use { shot ->
                shot.expandSheets()
                shot.scrollRangeDp().also { if (it == 0) finish(shot) }
            }
        if (rest == 0) return
        height += rest
    }
    error("${case.name}__${variant.id}: the content keeps growing with the window")
}
