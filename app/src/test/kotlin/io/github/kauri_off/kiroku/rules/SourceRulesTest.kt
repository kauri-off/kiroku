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
    fun viewModelsTakeNeitherTheListsWriterNorTheGraph() {
        val sources = appSources()
        assertEquals(
            setOf("EditSheetViewModel.kt", "ListViewModel.kt", "ShellViewModel.kt", "SubScreenViewModels.kt", "TitleViewModel.kt"),
            sources.filter { declaresViewModel(it.readText()) }.map { it.name }.toSet(),
        )
        assertTrue(sources.any { relative(it) == SHELL_VIEW_MODEL_SOURCE })
        assertEquals(emptyList<String>(), sources.flatMap { viewModelProblems(relative(it), it.readText()) })
        assertEquals(emptyList<String>(), sources.flatMap { collectionProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedViewModelProblemFails() {
        val planted =
            listOf(
                "class A(private val list: ListRepository) : ViewModel() {\n    fun a() = viewModelScope.launch { list.write(w) }\n}\n",
                "class A(\n    private val messages: io.github.kauri_off.kiroku.core.repository.ListRepository,\n) : ViewModel()\n",
                "class A(graph: AppGraph) : ViewModel()\n",
                "class A(app: Application) : ViewModel() {\n    val list = app.appGraph.list\n}\n",
                "class A(app: Application) : ViewModel() {\n    val graph = (app as AppGraphOwner).graph\n}\n",
                "class A(engine: ListEngine) : androidx.lifecycle.ViewModel()\n",
                "class A(list: ListRepository) : BaseViewModel<AState>()\n",
                "class A(list: ListRepository) :\n    SubScreenViewModel(id, anime, session)\n",
                "class A : ViewModel() {\n    init {\n        viewModelScope.launch { }\n    }\n}\n",
                "typealias Writer = ListRepository\n",
            )
        for (source in planted) {
            assertEquals(source, 1, viewModelProblems("A.kt", source).size)
        }
        val clean =
            "class A(\n    private val list: ListSource,\n    private val messages: ShellMessages,\n) : ViewModel() {\n" +
                "    val state = list.observeEntries().stateIn(viewModelScope, WhileShown, null)\n" +
                "    fun a() = messages.write(w)\n}\n"
        assertEquals(emptyList<String>(), viewModelProblems("A.kt", clean))
        assertEquals(
            emptyList<String>(),
            viewModelProblems(SHELL_VIEW_MODEL_SOURCE, "class A(list: ListRepository) : ViewModel() {\n    init { }\n}\n"),
        )
        assertEquals(1, collectionProblems("A.kt", "@Composable fun A(vm: V) { val s by vm.state.collectAsState() }\n").size)
        assertEquals(1, collectionProblems("A.kt", "@Composable fun A(vm: V) { val s by vm.state.collectAsStateWithLifecycle() }\n").size)
        assertEquals(0, collectionProblems("A.kt", "@Composable fun A(vm: V) { val s by vm.state.collectAsScreenState() }\n").size)
        assertTrue(appSources().any { relative(it) == SCREEN_STATE_SOURCE })
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
            "withFrameNanos {}",
        )) {
            assertTrue(planted, motionLiteralProblems("A.kt", "val a = $planted\n").isNotEmpty())
        }
        val allowed =
            listOf(
                "MaterialTheme.motionScheme.fastEffectsSpec<Float>()",
                "motionScheme.defaultEffectsSpec<Float>()",
                "snapshotFlow { x }",
                "animatable.snapTo(1f)",
            )
        for (code in allowed) {
            assertEquals(code, emptyList<String>(), motionLiteralProblems("A.kt", "val a = $code\n"))
        }
        assertTrue(motionLiteralProblems(THEME_SOURCES + "Motion.kt", "val a = tween<Float>(300)\n").isNotEmpty())
        assertEquals(emptyList<String>(), motionLiteralProblems(PAGE_MOTION_SOURCE, pageMotion.joinToString("\n") { "val a = $it" }))
        for (other in listOf("tween<Float>(300)", "tween<Float>(millis, easing = FastOutSlowInEasing)", "spring<Float>()")) {
            assertTrue(other, motionLiteralProblems(PAGE_MOTION_SOURCE, "val a = $other\n").isNotEmpty())
        }
        assertTrue(pageMotion.all { allowed -> appSources().single { relative(it) == PAGE_MOTION_SOURCE }.readText().contains(allowed) })
    }

    @Test
    fun everyTextFieldTakesTheAutofillModifier() {
        val sources = appSources()
        assertEquals(4, sources.sumOf { textFields(it.readText()) })
        assertEquals(emptyList<String>(), sources.flatMap { autofillProblems(relative(it), it.readText()) })
    }

    @Test
    fun aTextFieldWithoutTheAutofillModifierFails() {
        for (planted in listOf(
            "TextField(value = a, onValueChange = {})",
            "OutlinedTextField(value = a, onValueChange = {}, modifier = Modifier.fillMaxWidth())",
            "OutlinedTextField(a, {}, modifier = Modifier.fillMaxWidth().withoutAutofill())",
            "BasicTextField(state, modifier = Modifier.padding(Spacing.s))",
            "SecureTextField(state)",
            "OutlinedSecureTextField(state, modifier = Modifier)",
            "SearchBarDefaults.InputField(query = q, onQueryChange = {}, onSearch = {}, expanded = e, onExpandedChange = {})",
            "androidx.compose.material3.TextField(a, {})",
        )) {
            assertEquals(planted, 1, autofillProblems("A.kt", "@Composable\nfun A() {\n    $planted\n}\n").size)
        }
        for (allowed in listOf(
            "TextField(value = a, onValueChange = {}, modifier = Modifier.withoutAutofill().fillMaxWidth())",
            "OutlinedTextField(a, {}, modifier = fieldModifier.withoutAutofill().weight(1f))",
            "OutlinedTextField(a, {}, label = { Text(b(c)) }, modifier =\n        Modifier\n            .withoutAutofill())",
        )) {
            assertEquals(allowed, emptyList<String>(), autofillProblems("A.kt", "@Composable\nfun A() {\n    $allowed\n}\n"))
        }
    }

    @Test
    fun theLoadingIndicatorsGraceIsTheOneDelayItsFileMayWrite() {
        val grace = "@Composable\nfun A() {\n    LaunchedEffect(Unit) { $PATIENCE_WAIT; shown = true }\n}\n"
        assertEquals(emptyList<String>(), motionLiteralProblems(PATIENCE_SOURCE, grace))
        assertEquals(1, motionLiteralProblems("A.kt", grace).size)
        assertEquals(1, motionLiteralProblems(PATIENCE_SOURCE, grace.replace(PATIENCE_WAIT, "delay(250)")).size)
        assertTrue(appSources().single { relative(it) == PATIENCE_SOURCE }.readText().contains(PATIENCE_WAIT))
    }

    @Test
    fun aMessagesTimeIsTheOneDelayTheSnackbarHostsFileMayWrite() {
        val time = "@Composable\nfun A() {\n    LaunchedEffect(x) { $MESSAGE_TIME_WAIT; data.dismiss() }\n}\n"
        assertEquals(emptyList<String>(), motionLiteralProblems(MESSAGE_TIME_SOURCE, time))
        assertEquals(1, motionLiteralProblems("A.kt", time).size)
        assertEquals(1, motionLiteralProblems(MESSAGE_TIME_SOURCE, time.replace(MESSAGE_TIME_WAIT, "delay(250)")).size)
        assertTrue(appSources().single { relative(it) == MESSAGE_TIME_SOURCE }.readText().contains(MESSAGE_TIME_WAIT))
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
            "animateFloatAsState(target, Motion.fastEffects())",
            "animatable.animateTo(1f, motionScheme)",
            "animatable.animateTo(1f, motionScheme.defaultSpatialSpec())",
            "Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())",
        )) {
            assertTrue(planted, motionLiteralProblems("A.kt", "val a = $planted\n").isNotEmpty())
        }
        for (allowed in listOf(
            "animateFloatAsState(target, MaterialTheme.motionScheme.fastEffectsSpec())",
            "AnimatedVisibility(visible, enter = fadeIn(motionScheme.defaultEffectsSpec()), exit = fadeOut(motionScheme.fastEffectsSpec())) { }",
            "Modifier.animateContentSize(motionScheme.fastEffectsSpec())",
            "animatable.animateTo(1f, motionScheme.slowEffectsSpec())",
            "state.animateScrollToItem(0)",
        )) {
            assertEquals(allowed, emptyList<String>(), motionLiteralProblems("A.kt", "val a = $allowed\n"))
        }
    }

    @Test
    fun experimentalExpressiveApiIsOptedIntoInOneFile() {
        val optIn = "ExperimentalMaterial3ExpressiveApi"
        val users = appSources().filter { optIn in it.readText() }.map { relative(it) }
        assertEquals(listOf(EXPRESSIVE_OPT_IN_SOURCE), users)
        val scripts = projectSources().filter { it.extension == "kts" }
        assertTrue(scripts.isNotEmpty() && scripts.none { optIn in it.readText() })
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
