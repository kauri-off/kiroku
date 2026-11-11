package io.github.kauri_off.kiroku.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceRulesTest {
    private val compose = "import androidx.compose.material3.Text\n"

    private fun relative(file: File) = file.relativeTo(projectDir).path

    @Test
    fun appCodeHasNoStringLiteral() {
        val sources = appSources()
        assertTrue(sources.any { it.path.endsWith("ComponentGallery.kt") })
        assertEquals(emptyList<String>(), sources.flatMap { stringLiteralProblems(relative(it), it.readText()) })
    }

    @Test
    fun appCodeHasNoAnimationValue() {
        assertEquals(emptyList<String>(), appSources().flatMap { motionLiteralProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedStringLiteralFails() {
        val planted = compose + "@Composable\nfun A() {\n    Text(\"Sign in\")\n}\n"
        assertEquals(
            listOf("A.kt:4: string literal \"Sign in\" in user interface code; use a string resource"),
            stringLiteralProblems("A.kt", planted),
        )
        for (literal in listOf("\"\"\"Sign in\"\"\"", "\"\${count} episodes\"", "\"x\${\"Sign in\"}\"", "\"\\u0041\"")) {
            assertEquals(literal, 1, stringLiteralProblems("A.kt", compose + "val a = $literal\n").size.coerceAtMost(1))
        }
    }

    @Test
    fun codeWithoutALiteralPasses() {
        val clean =
            compose +
                """
                @Suppress("MagicNumber")
                @Composable
                fun A(count: Int) {
                    // "Sign in" in a comment
                    /* "Sign in" /* nested */ */
                    Text(stringResource(R.string.sign_in) + "${'$'}count" + " · " + '"' + "" + "0 / 9")
                }
                """.trimIndent()
        assertEquals(emptyList<String>(), stringLiteralProblems("A.kt", clean))
        assertEquals(emptyList<String>(), stringLiteralProblems("B.kt", "val a = \"not user interface code\"\n"))
    }

    @Test
    fun aPlantedAnimationValueFails() {
        for (planted in listOf(
            "tween(300)",
            "tween<Float>(durationMillis = 300)",
            "spring(dampingRatio = 0.5f)",
            "FastOutSlowInEasing",
            "delay(250)",
        )) {
            assertTrue(planted, motionLiteralProblems("A.kt", compose + "val a = $planted\n").isNotEmpty())
        }
        assertEquals(emptyList<String>(), motionLiteralProblems("A.kt", compose + "val a = Motion.fastEffects<Float>()\n"))
        assertEquals(emptyList<String>(), motionLiteralProblems("app/$MOTION_SOURCE", compose + "val a = tween<Float>(300)\n"))
    }

    // The screenshot harness renders inside the app's theme and window setup and has none of its own.
    @Test
    fun harnessHasNoThemeOrWindowCode() {
        val harness = File(projectDir, "app/src/test/kotlin/io/github/kauri_off/kiroku/screenshots")
        val forbidden = Regex("""\b(MaterialTheme\s*\(|\w*[cC]olorScheme\s*\(|enableEdgeToEdge|setContent\s*\{|SystemBarStyle)""")
        val found =
            harness
                .walk()
                .filter { it.isFile && it.extension == "kt" && it.name != "ScreenshotHarnessTest.kt" }
                .flatMap { file ->
                    KotlinSource
                        .of(file)
                        .code
                        .lines()
                        .filter { forbidden.containsMatchIn(it) }
                        .map { "${file.name}: ${it.trim()}" }
                }.toList()
        assertEquals(emptyList<String>(), found)
    }
}
