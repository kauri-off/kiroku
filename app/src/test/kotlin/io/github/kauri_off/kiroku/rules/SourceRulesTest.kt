package io.github.kauri_off.kiroku.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceRulesTest {
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
        val planted =
            listOf(
                "@Composable\nfun A() {\n    Text(\"Sign in\")\n}\n",
                "@Composable fun Banner() = Text(\"Session expired\")\n",
                "class Model { val state = State(message = \"Could not save\") }\n",
                "object Labels { const val SIGN_IN = \"Sign in\" }\n",
                "fun n(b: Builder) = b.setContentTitle(\"New episode\").build()\n",
                "@Composable\nfun A() = androidx.compose.material3.Text(\"Sign in\")\n",
                "@Suppress(\"x\") val a = \"Sign in\"\n",
                "val a = \"\"\"Sign in\"\"\"\n",
                "val a = \"\${count} episodes\"\n",
                "val a = \"1\${\"Sign in\"}\"\n",
                "val a = \"\\u0041\"\n",
                "@Preview(name = list.first { it == \"a\" })\nfun P() = Unit\n",
            )
        for (source in planted) {
            assertEquals(source, 1, stringLiteralProblems("A.kt", source).size)
        }
        assertEquals(
            listOf("A.kt:3: string literal \"Sign in\" in user interface code; use a string resource"),
            stringLiteralProblems("A.kt", planted.first()),
        )
    }

    @Test
    fun codeWithoutALiteralPasses() {
        val clean =
            """
            @file:Suppress("MagicNumber")
            @Suppress("MagicNumber")
            @Preview(name = "Dark", locale = "ru")
            @Composable
            fun A(count: Int) {
                // "Sign in" in a comment
                /* "Sign in" /* nested */ */
                Text(stringResource(R.string.sign_in) + "${'$'}count" + " · " + '"' + "" + "0 / 9")
                Box(Modifier.testTag("row"))
                Log.d(TAG, "drawn")
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), stringLiteralProblems("A.kt", clean))
    }

    @Test
    fun aPlantedAnimationValueFails() {
        for (planted in listOf(
            "tween(300)",
            "tween<Float>(durationMillis = 300)",
            "spring(dampingRatio = 0.5f)",
            "FastOutSlowInEasing",
            "EaseInOutCubic",
            "delay(250)",
            "TweenSpec(300)",
            "SpringSpec(0.5f, 200f)",
            "SnapSpec(100)",
            "KeyframesSpec(config)",
            "RepeatableSpec(3, spec)",
            "InfiniteRepeatableSpec(spec)",
            "keyframes { }",
            "exponentialDecay(frictionMultiplier = 2f)",
            "splineBasedDecay<Float>(density)",
            "rememberSplineBasedDecay<Float>()",
            "AnimationConstants.DefaultDurationMillis",
            "Spring.StiffnessLow",
            "StiffnessMediumLow",
            "androidx.compose.animation.core.tween<Float>(300)",
            "withInfiniteAnimationFrameNanos { }",
        )) {
            assertTrue(planted, motionLiteralProblems("A.kt", "val a = $planted\n").isNotEmpty())
        }
        val allowed =
            listOf(
                "Motion.componentEnter<Float>()",
                "Motion.fastSpatial<Float>()",
                "Motion.Easing.standard",
                "snapshotFlow { x }",
                "animatable.snapTo(1f)",
            )
        for (code in allowed) {
            assertEquals(code, emptyList<String>(), motionLiteralProblems("A.kt", "val a = $code\n"))
        }
        assertEquals(emptyList<String>(), motionLiteralProblems(MOTION_SOURCE, "val a = tween<Float>(300)\n"))
        assertTrue(motionLiteralProblems("app/src/main/kotlin/other/design/theme/Motion.kt", "val a = tween<Float>(300)\n").isNotEmpty())
    }

    @Test
    fun motionSourceExists() = assertTrue(File(projectDir, MOTION_SOURCE).isFile)

    @Test
    fun everySourceSetThatShipsIsScanned() {
        val scanned = appSources().map { relative(it) }
        assertTrue(scanned.any { it.startsWith("app/src/main/") })
        assertTrue(scanned.any { it.startsWith("app/src/debug/") })
        assertTrue(scanned.any { it.startsWith("app/src/release/") })
        assertTrue(scanned.none { it.startsWith("app/src/test") })
    }

    // The screenshot harness renders inside the app's theme and window setup and has none of its own.
    @Test
    fun harnessHasNoThemeOrWindowCode() {
        val harness = File(projectDir, "app/src/test/kotlin/io/github/kauri_off/kiroku/screenshots")
        val found =
            harness
                .walk()
                .filter { it.isFile && it.extension == "kt" && it.name != "ScreenshotHarnessTest.kt" }
                .flatMap { themeOrWindowProblems(it.name, it.readText()) }
                .toList()
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun plantedThemeOrWindowCodeFails() {
        for (planted in listOf(
            "MaterialTheme(colorScheme = s) { }",
            "lightColorScheme()",
            "MaterialTheme(typography = Typography(bodyLarge = style)) { }",
            "Shapes(small = shape)",
            "CompositionLocalProvider(LocalContentColor provides c) { }",
            "CompositionLocalProvider(LocalTextStyle provides t) { }",
            "Surface(color = c) { KirokuTheme(dark = true, dynamicColor = false) { } }",
            "activity.setContent { }",
            "activity.setContent(content = c)",
            "window.statusBarColor = 0",
            "window.navigationBarColor = 0",
            "controller.isAppearanceLightStatusBars = true",
            "WindowCompat.setDecorFitsSystemWindows(window, false)",
            "WindowInsetsControllerCompat(window, view)",
            "enableEdgeToEdge()",
            "MaterialExpressiveTheme(colorScheme = s) { }",
        )) {
            assertEquals(planted, 1, themeOrWindowProblems("A.kt", "val a = $planted\n").size)
        }
        assertEquals(
            emptyList<String>(),
            themeOrWindowProblems(
                "A.kt",
                "val a = if (controller.isAppearanceLightStatusBars) 1 else 2\nactivity.setKirokuContent(s, seed, c)\n",
            ),
        )
    }
}
