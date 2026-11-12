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
                "@Composable\nfun A() {\n    Text(\"Sign in\")\n}\n" to 1,
                "@Composable fun Banner() = Text(\"Session expired\")\n" to 1,
                "class Model { val state = State(message = \"Could not save\") }\n" to 1,
                "object Labels { const val SIGN_IN = \"Sign in\" }\n" to 1,
                "fun n(b: Builder) = b.setContentTitle(\"New episode\").build()\n" to 1,
                "@Composable\nfun A() = androidx.compose.material3.Text(\"Sign in\")\n" to 1,
                "@Suppress(\"x\") val a = \"Sign in\"\n" to 1,
                "val a = \"\"\"Sign in\"\"\"\n" to 1,
                "val a = \"\${count} episodes\"\n" to 1,
                "val a = \"\${\"Sign in\"}\"\n" to 2,
                "val a = \"\\u0041\"\n" to 1,
                "@Preview(name = list.first { it == \"a\" })\nfun P() = Unit\n" to 1,
                // Text without a letter is text all the same.
                "@Composable fun A() = TextButton(onClick = {}) { Text(\"+1\") }\n" to 1,
                "@Composable fun A() = StatNumber(\"—\", caption)\n" to 1,
                "@Composable fun A(watched: Int, total: Int) = Text(\"\$watched / \$total\")\n" to 1,
                "@Composable fun A(count: Int) = Text(\"\$count\")\n" to 1,
                "@Composable fun A() = Text(stringResource(R.string.a) + \" · \")\n" to 1,
                "val a = \"0 / 9\"\n" to 1,
                // A string no user reads still needs its mark.
                "private const val TAG = \"Kiroku\"\n" to 1,
                "val channel = NotificationChannel(\"airing\", name, importance)\n" to 1,
                "val a = notShown(\"key\") + \"Sign in\"\n" to 1,
            )
        for ((source, problems) in planted) {
            assertEquals(source, problems, stringLiteralProblems("A.kt", source).size)
        }
        assertEquals(
            listOf(
                "A.kt:3: string literal \"Sign in\" in user interface code; use a string resource, " +
                    "or notShown(…) for a string no user reads",
            ),
            stringLiteralProblems("A.kt", planted.first().first),
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
                Text(stringResource(R.string.sign_in) + "" + '"')
                Box(Modifier.testTag("row"))
                Log.d(TAG, "drawn")
            }
            private val TAG = notShown("Kiroku")
            val channel = NotificationChannel(notShown("airing"), name, importance)
            val page = Uri.parse(notShown("https://myanimelist.net/anime/${'$'}id"))
            fun sync(manager: WorkManager) = manager.enqueueUniqueWork(notShown("sync"), policy, request)
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
            "Easing { it * it }",
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
            "withFrameNanos { time = it }",
            "withFrameMillis { offset = it * speed }",
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
                "withFrameNanos {}",
            )
        for (code in allowed) {
            assertEquals(code, emptyList<String>(), motionLiteralProblems("A.kt", "val a = $code\n"))
        }
        assertEquals(emptyList<String>(), motionLiteralProblems(MOTION_SOURCE, "val a = tween<Float>(300)\n"))
        assertTrue(motionLiteralProblems("app/src/main/kotlin/other/design/theme/Motion.kt", "val a = tween<Float>(300)\n").isNotEmpty())
    }

    @Test
    fun aDelayIsAnAnimationValueOnlyInUserInterfaceCode() {
        val composable = "@Composable\nfun A() {\n    LaunchedEffect(x) { delay(250); shown = true }\n}\n"
        assertEquals(1, motionLiteralProblems("A.kt", composable).size)
        assertEquals(1, motionLiteralProblems("A.kt", "import androidx.compose.ui.Modifier\nsuspend fun a() = delay(250)\n").size)
        assertEquals(1, motionLiteralProblems("A.kt", "@Composable\nfun A() = Box(Modifier.a(durationMillis = d))\n").size)
        for (elsewhere in listOf(
            "class SearchModel : ViewModel() {\n    fun a() = query.collectLatest { delay(300); search(it) }\n}\n",
            "import androidx.compose.runtime.mutableStateOf\nclass Sync : CoroutineWorker() {\n    suspend fun a() = delay(backoff)\n}\n",
            "data class Episode(val durationMillis: Long)\n",
            "fun a(easing: Easing) = easing\n",
        )) {
            assertEquals(elsewhere, emptyList<String>(), motionLiteralProblems("A.kt", elsewhere))
        }
    }

    @Test
    fun anAnimationWithComposesOwnSpecFails() {
        for (planted in listOf(
            "AnimatedVisibility(visible) { Text(a) }",
            "AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { }",
            "AnimatedContent(target) { Text(it) }",
            "Crossfade(target) { Text(it) }",
            "Modifier.animateContentSize()",
            "Modifier.animateItem()",
            "animateFloatAsState(if (open) 1f else 0f)",
            "animateColorAsState(color, label = \"a\")",
            "transition.animateFloat(label = \"a\") { 1f }",
            "animatable.animateTo(1f)",
            "slideInVertically { it }",
            "expandVertically()",
            "scaleIn()",
        )) {
            assertTrue(planted, motionLiteralProblems("A.kt", "val a = $planted\n").isNotEmpty())
        }
        for (allowed in listOf(
            "animateFloatAsState(target, Motion.fastEffects())",
            "AnimatedVisibility(visible, enter = fadeIn(Motion.componentEnter()), exit = fadeOut(Motion.componentExit())) { }",
            "Modifier.animateContentSize(Motion.fastSpatial())",
            "animatable.animateTo(1f, Motion.defaultSpatial())",
            "state.animateScrollToItem(0)",
        )) {
            assertEquals(allowed, emptyList<String>(), motionLiteralProblems("A.kt", "val a = $allowed\n"))
        }
    }

    @Test
    fun appCodeHasNoDpValue() {
        assertEquals(emptyList<String>(), appSources().flatMap { dpLiteralProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedDpValueFails() {
        for (planted in listOf("Modifier.padding(16.dp)", "val a = 0.5.dp", "val a = Dp(48f)", "Modifier.size(48 .dp)")) {
            assertEquals(planted, 1, dpLiteralProblems("app/src/main/kotlin/A.kt", "val a = $planted\n").size)
        }
        assertEquals(emptyList<String>(), dpLiteralProblems("app/src/main/kotlin/A.kt", "val a = Spacing.l * 2 + Sizes.posterRow\n"))
        assertEquals(emptyList<String>(), dpLiteralProblems(THEME_SOURCES + "Sizes.kt", "val a = 56.dp\n"))
        assertTrue(File(projectDir, THEME_SOURCES).isDirectory)
    }

    @Test
    fun sourcesHaveNoInvisibleCharacter() {
        val sources = projectSources()
        assertTrue(sources.any { it.path.endsWith("Title.kt") })
        assertTrue(sources.any { it.path.endsWith("values-ru/strings_design.xml") })
        assertEquals(emptyList<String>(), sources.flatMap { invisibleCharacterProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedInvisibleCharacterFails() {
        for (c in listOf('\u200B', '\u00A0', '\u202F', '\u2060', '\uFEFF', '\u00AD')) {
            assertEquals(1, invisibleCharacterProblems("A.kt", "val a = \"a${c}b\"\n").size)
        }
        assertEquals(emptyList<String>(), invisibleCharacterProblems("A.kt", "val a = \"a\\u200Bb\"\n\tval b = 1\r\n"))
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
