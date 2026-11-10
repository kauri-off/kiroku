package io.github.kauri_off.kiroku.screenshots

import androidx.compose.runtime.Composable
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

class ScreenshotCase(
    val name: String,
    val isContent: Boolean,
    val content: @Composable () -> Unit,
)

fun contentCase(
    name: String = "content",
    content: @Composable () -> Unit,
) = ScreenshotCase(name, isContent = true, content)

fun case(
    name: String,
    content: @Composable () -> Unit,
) = ScreenshotCase(name, isContent = false, content)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotRegistry(
    private val screen: String,
) {
    abstract val cases: List<ScreenshotCase>

    @Test
    fun render() {
        assumeTrue("Screenshots are rendered only with -Proborazzi.test.record=true", isRecording)
        val problems = renderScreen(screen, cases)
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    companion object {
        val isRecording: Boolean get() = System.getProperty("roborazzi.test.record") == "true"

        val outputRoot: File get() = File(System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi")
    }
}

private val caseName = Regex("[a-z0-9]+(-[a-z0-9]+)*")

internal fun renderScreen(
    screen: String,
    cases: List<ScreenshotCase>,
): List<String> {
    require(cases.count { it.isContent } == 1) { "$screen declares ${cases.count { it.isContent }} content cases, not one" }
    require(cases.map { it.name }.toSet().size == cases.size) { "$screen declares a case twice" }
    cases.forEach { require(caseName.matches(it.name)) { "Case name '${it.name}' is not lower-case-with-hyphens" } }

    val dir = File(ScreenshotRegistry.outputRoot, screen).apply { deleteRecursively() }
    val problems = mutableListOf<String>()
    val started = System.nanoTime()
    var images = 0
    for (case in cases) {
        for (variant in Variant.forCase(case.isContent)) {
            val prefix = "${case.name}__${variant.id}"
            var extraDp = 0
            Shot.launch(variant, content = case.content).use { shot ->
                shot.capture(File(dir, "$prefix.png"))
                images++
                if (variant == Variant.EnLight) {
                    problems += shot.accessibilityProblems().map { "$screen/${case.name}: $it" }
                    if (case.isContent) {
                        shot.captureBounds(File(dir, "${case.name}__bounds.png"))
                        images++
                    }
                }
                extraDp = shot.scrollRangeDp()
            }
            if (extraDp > 0) {
                renderFull(variant, variant.heightDp + extraDp, case, File(dir, "${prefix}__full.png"))
                images++
            }
        }
    }
    val seconds = (System.nanoTime() - started) / 1e9
    println("Rendered $screen: $images images in ${"%.1f".format(Locale.ROOT, seconds)} s")
    return problems
}

private fun renderFull(
    variant: Variant,
    heightDp: Int,
    case: ScreenshotCase,
    file: File,
) {
    var height = heightDp
    repeat(3) {
        val rest =
            Shot.launch(variant, height, case.content).use { shot ->
                shot.capture(file)
                shot.scrollRangeDp()
            }
        if (rest == 0) return
        height += rest
    }
    error("${case.name}__${variant.id}: the content keeps growing with the window")
}
