package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

class ScreenshotCase internal constructor(
    val name: String,
    val isContent: Boolean,
    val keyboard: Boolean,
    val content: @Composable () -> Unit,
)

fun contentCase(
    name: String = "content",
    keyboard: Boolean = false,
    content: @Composable () -> Unit,
) = ScreenshotCase(name, isContent = true, keyboard, content)

fun case(
    name: String,
    keyboard: Boolean = false,
    content: @Composable () -> Unit,
) = ScreenshotCase(name, isContent = false, keyboard, content)

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
abstract class RegistryBase internal constructor() {
    val screen: String get() = screenName(javaClass)

    internal abstract fun shape(): RegistryShape

    @Test
    fun structure() = assertEquals(emptyList<String>(), shape().problems())

    @Test
    fun render() {
        assumeTrue("Screenshots are rendered only with -Proborazzi.test.record=true", isRecording)
        val shape = shape()
        val structure = shape.problems()
        if (structure.isNotEmpty()) fail(structure.joinToString("\n"))
        shape.notShown.forEach { (state, reason) -> println("$screen has no $state case: $reason") }
        val problems = renderScreen(screen, shape.cases, File(outputRoot, screen))
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

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
            notShown.filter { it.second.isBlank() }.forEach { add("$screen gives no reason for having no ${it.first} case") }
        }

    companion object {
        val showcases = setOf("sample", "gallery")
        private val stateNames = setOf("loading", "empty", "error")
        private val caseName = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val screenPattern = caseName
    }
}

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
    write: (Bitmap, File) -> Unit = Shot::write,
): List<String> {
    dir.deleteRecursively()
    val problems = mutableListOf<String>()
    val started = System.nanoTime()
    var images = 0
    for (case in cases) {
        for (variant in variantsOf(case)) {
            val prefix = "${case.name}__${variant.id}"
            val finish = { shot: Shot ->
                images += finishCase(shot, case, variant, File(dir, prefix), write) { problems += "$screen/${case.name}: $it" }
            }
            var extraDp = 0
            Shot.launch(variant, keyboard = case.keyboard, content = case.content).use { shot ->
                write(shot.image(), File(dir, "$prefix.png"))
                images++
                if (variant == Variant.EnLight && case.isContent) {
                    write(shot.boundsImage(), File(dir, "${case.name}__bounds.png"))
                    images++
                }
                extraDp = shot.scrollRangeDp()
                if (extraDp == 0) finish(shot)
            }
            if (extraDp > 0) {
                renderFull(variant, variant.heightDp + extraDp, case) { shot ->
                    write(shot.image(), File(dir, "${prefix}__full.png"))
                    finish(shot)
                }
                images++
            }
        }
    }
    val seconds = (System.nanoTime() - started) / 1e9
    println("Rendered $screen: $images images in ${"%.1f".format(Locale.ROOT, seconds)} s")
    return problems.distinct()
}

// Runs on the image that shows everything: the accessibility check in en-light, and each strip of the content case.
private fun finishCase(
    shot: Shot,
    case: ScreenshotCase,
    variant: Variant,
    prefix: File,
    write: (Bitmap, File) -> Unit,
    report: (String) -> Unit,
): Int {
    val checked = variant == Variant.EnLight
    if (checked) shot.accessibilityProblems().forEach(report)
    var images = 0
    if (!checked && !case.isContent) return images
    repeat(shot.stripCount()) { index ->
        val stitched = shot.walkStrip(index, withImages = case.isContent) { if (checked) shot.accessibilityProblems().forEach(report) }
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
    heightDp: Int,
    case: ScreenshotCase,
    finish: (Shot) -> Unit,
) {
    var height = heightDp
    repeat(3) {
        val rest =
            Shot.launch(variant, height, case.keyboard, case.content).use { shot ->
                shot.scrollRangeDp().also { if (it == 0) finish(shot) }
            }
        if (rest == 0) return
        height += rest
    }
    error("${case.name}__${variant.id}: the content keeps growing with the window")
}
