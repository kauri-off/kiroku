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
            setOf(
                "BrowseViewModel.kt",
                "EditSheetViewModel.kt",
                "ListViewModel.kt",
                "MangaTitleViewModel.kt",
                "NotifySettingsViewModel.kt",
                "OpenedProfiles.kt",
                "ProfileViewModel.kt",
                "RecommendationsViewModel.kt",
                "SearchViewModel.kt",
                "ShellViewModel.kt",
                "SignInViewModel.kt",
                "StatisticsViewModel.kt",
                "SubScreenViewModels.kt",
                "TitleViewModel.kt",
                "UserListViewModel.kt",
            ),
            sources.filter { declaresViewModel(it.readText()) }.map { it.name }.toSet(),
        )
        assertTrue(sources.any { relative(it) == SHELL_VIEW_MODEL_SOURCE })
        assertEquals(emptyList<String>(), sources.flatMap { viewModelProblems(relative(it), it.readText()) })
        assertEquals(emptyList<String>(), sources.flatMap { collectionProblems(relative(it), it.readText()) })
    }

    @Test
    fun anotherUsersListNamesNoWriterAndNoAiring() {
        val sources = appSources().filter { it.name in USER_LIST_SOURCES }
        assertEquals(USER_LIST_SOURCES, sources.map { it.name }.toSet())
        assertEquals(emptyList<String>(), sources.flatMap { readOnlyListProblems(relative(it), it.readText()) })
        val planted =
            listOf(
                "class A(private val messages: ShellMessages) : ViewModel()\n",
                "val editor = editSheetViewModel(graph, messages)\n",
                "fun a(list: ListRepository) = Unit\n",
                "fun a() = write(ListWrite.MarkNextWatched(anime))\n",
                "val airing = graph.airing\n",
                "class A(private val airing: AiringRepository) : ViewModel()\n",
                "fun a() = viewModelScope.launch { own.sync() }\n",
                "fun a() = graph.list.write(step)\n",
                "fun a() = post(ShellMessage(text, ShellMessage.Action.Change(id)))\n",
                "val list = graph.list\n",
                "class A(private val own: ListSource) : ViewModel()\n",
            )
        for (source in planted) assertEquals(source, 1, readOnlyListProblems("A.kt", source).size)
        val clean =
            "class A(private val own: Flow<List<ListEntry>>, private val failed: (DataError) -> Unit)\n" +
                "val a = graph.list.observeEntries()\n"
        assertEquals(0, readOnlyListProblems("A.kt", clean).size)
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
        assertEquals(7, sources.sumOf { textFields(it.readText()) })
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
            "unveilIn(initialColor = scrim)",
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
            "unveilIn(motionScheme.fastEffectsSpec())",
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
    fun nothingThatShipsHoldsAWebView() {
        val shipped = projectSources().filterNot { source -> listOf("test", "testDebug", "androidTest").any { "/src/$it/" in source.path } }
        assertTrue(shipped.any { it.path.endsWith("signin/CustomTabBrowser.kt") })
        assertTrue(shipped.any { it.path.endsWith("app/src/main/AndroidManifest.xml") })
        assertEquals(emptyList<String>(), shipped.flatMap { webViewProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedWebViewFails() {
        val planted =
            listOf(
                "A.kt" to "import android.webkit.CookieManager\n",
                "A.kt" to "@Composable fun A() = AndroidView({ WebView(it) })\n",
                "A.kt" to "class A : WebViewClient()\n",
                "a.xml" to "<WebView android:id=\"@+id/a\" />\n",
            )
        for ((name, source) in planted) assertEquals(source, 1, webViewProblems(name, source).size)
        assertEquals(emptyList<String>(), webViewProblems("A.kt", "// never a WebView\nval a = notShown(\"WebView\")\n"))
    }

    @Test
    fun appCodeLeavesTheBackGestureToNavDisplay() {
        val sources = appSources()
        assertTrue(sources.any { relative(it) == BACK_HANDLER_SOURCE })
        assertEquals(emptyList<String>(), sources.flatMap { backGestureProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedBackGestureHandlerFails() {
        val planted =
            listOf(
                "PredictiveBackHandler { progress -> progress.collect { } }",
                "rememberNavigationEventState(sceneState)",
                "NavigationBackHandler(sceneState, state, onBackCompleted = back)",
                "LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher.transitionState.collect { }",
                "LocalNavAnimatedContentScope.current.transition.let { (it as SeekableTransitionState<*>).fraction }",
                "DirectNavigationEventInput().backProgressed(event)",
                "object : OnBackPressedCallback(true) { override fun handleOnBackProgressed(e: BackEventCompat) { } }",
            )
        for (source in planted) assertEquals(source, 1, backGestureProblems("A.kt", "val a = $source\n").size)
        val inTheHandlersFile =
            planted.filter { "NavigationBackHandler" !in it && "rememberNavigationEventState" !in it } +
                listOf(
                    "handled.transitionState",
                    "(handled.transitionState as InProgress).latestEvent.progress",
                    "NavigationEventHandler(handled, onBackCompleted = back)",
                    "NavDisplay(scenes, rememberNavigationEventState(scenes).also { it.transitionState })",
                )
        for (source in inTheHandlersFile) assertEquals(source, 1, backGestureProblems(BACK_HANDLER_SOURCE, "val a = $source\n").size)
        val handler = "NavigationBackHandler(scenes, rememberNavigationEventState(scenes), onBackCompleted = back)\n"
        assertEquals(emptyList<String>(), backGestureProblems(BACK_HANDLER_SOURCE, handler))
        assertEquals(
            emptyList<String>(),
            backGestureProblems("A.kt", "NavDisplayWithoutPreview(entries, transition, back)\nBackHandler(enabled) { clear() }\n"),
        )
    }

    @Test
    fun appAndDataCodeMakeNoAiringValue() {
        val sources = appSources() + dataSources()
        assertTrue(sources.any { relative(it).endsWith("list/ListState.kt") })
        assertTrue(sources.any { it.name == "ListFixtureStates.kt" })
        assertTrue(sources.any { relative(it).startsWith(DATA_SOURCES + "src/main/") })
        assertTrue(dataSources().none { relative(it).startsWith(DATA_SOURCES + "src/test") })
        assertEquals(emptyList<String>(), sources.flatMap { airingValueProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedAiringValueFails() {
        val counts =
            listOf(
                "val a = AiredCount.Exact(entry.anime.malEpisodes ?: 0)",
                "val a = AiredCount.Estimated(aired.value - values.watchedEpisodes)",
                "import io.github.kauri_off.kiroku.core.airing.AiredCount.Exact",
                "import io.github.kauri_off.kiroku.core.airing.AiredCount.*",
                "val a = toWatch.copy(value = 0)",
                "val a = toWatch.copy(\n    value = 0,\n)",
            )
        val episodes =
            listOf(
                "val a = Line3.NextEpisode(ScheduledEpisode(aired.value + 1, at))",
                "val a = next.copy(number = next.number + 1)",
                "val a = next.copy(\n    airsAt = now,\n)",
            )
        for (name in listOf("app/src/main/kotlin/A.kt", "data/src/main/kotlin/A.kt")) {
            for (source in counts) assertEquals("$name: $source", 1, airingValueProblems(name, "$source\n").size)
        }
        for (source in episodes) {
            assertEquals(source, 1, airingValueProblems("app/src/main/kotlin/A.kt", "$source\n").size)
            assertEquals(source, 0, airingValueProblems("data/src/main/kotlin/A.kt", "$source\n").size)
        }
        val clean =
            "import io.github.kauri_off.kiroku.core.airing.AiredCount\nimport io.github.kauri_off.kiroku.core.airing.Airing\n" +
                "val shown = titleAiring(anime, values, airing, today)\nval aired = (shown.phase as? AiringPhase.Aired)?.aired\n" +
                "val a = airingAt(anime, schedule, now)\nval c: (AnimeId) -> Airing? = airing::get\n" +
                "when (aired) {\n    is AiredCount.Exact -> exact(aired.value)\n" +
                "    is AiredCount.Estimated -> estimated(aired.value)\n}\n" +
                "val e = Line3.NextEpisode(episode)\nval f = entry.copy(values = values)\nfun line(episode: ScheduledEpisode): String\n"
        assertEquals(emptyList<String>(), airingValueProblems("app/src/main/kotlin/A.kt", clean))
    }

    @Test
    fun appAndDataCodeMakeNoStatistic() {
        val sources = appSources() + dataSources()
        assertTrue(sources.any { it.name == "ProfileFixtureStates.kt" })
        assertTrue(sources.any { relative(it).endsWith("data/anime/MalAnime.kt") })
        assertEquals(emptyList<String>(), sources.flatMap { statisticsValueProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedStatisticFails() {
        val planted =
            listOf(
                "val a = UserStatistics(counts, 0.0, null, emptyMap(), emptyList(), emptyList(), emptyMap())",
                "val a = io.github.kauri_off.kiroku.core.model.UserStatistics(\n    statuses = counts,\n)",
                "val a = Counted(genre, 3)",
                "val a = Counted<MediaType>(MediaType.Tv, 3)",
                "val a = StatusCounts(1, 2, 3, 4, 5)",
                "val a = StatusCounts.of(entries.map { it.status })",
                "val a = entries.map { it.status }.let(StatusCounts::of)",
                "val a = StatusCounts.Companion.of(statuses)",
                "val a = statistics.copy(meanScore = 7.0)",
                "val a = statistics.copy(titleCount = 0)",
                "val a = statistics.copy(\n    daysWatched = 0.0,\n)",
                "val a = statistics.statuses.copy(completed = done)",
                "val a = TitleStatistics(counts, members).also { StatusCounts(1, 2, 3, 4, 5) }",
                "val a = statistics.copy(episodesWatched = 0)",
                "val a = statistics.copy(\n    titleCount = 3,\n)",
                "private val UserStatistics.scored: Int get() = scores.values.sum()",
                "val a = statistics.scores.values.sumOf { it }",
            )
        for (source in planted) {
            assertEquals(source, 1, statisticsValueProblems("app/src/main/kotlin/A.kt", "$source\n").size)
            assertEquals(source, 1, statisticsValueProblems("data/src/main/kotlin/A.kt", "$source\n").size)
        }
        val clean =
            "fun a(statistics: UserStatistics): List<Counted<Genre>> = statistics.genres\n" +
                "val b = statisticsOf(entries)\nval c = statistics.statuses[ListStatus.Completed]\n" +
                "val d = TitleStatistics(StatusCounts(w, c, h, d, p), members)\n" +
                "val e = TitleStatistics(\n    StatusCounts(\n        watching = w,\n    ),\n    members,\n)\n" +
                "val f = entry.copy(values = values)\nval g = statistics.scored + statistics.titleCount\n"
        assertEquals(emptyList<String>(), statisticsValueProblems("app/src/main/kotlin/A.kt", clean))
    }

    @Test
    fun everySourceSetThatShipsIsScanned() {
        val scanned = appSources().map { relative(it) }
        assertTrue(scanned.any { it.startsWith("app/src/main/") })
        assertTrue(scanned.any { it.startsWith("app/src/debug/") })
        assertTrue(scanned.any { it.startsWith("app/src/release/") })
        assertTrue(scanned.none { it.startsWith("app/src/test") })
    }

    @Test
    fun shippedCodeMakesNoScopeButTheApplications() {
        val shipped = shippedKotlinSources()
        assertTrue(shipped.any { relative(it) == APPLICATION_SCOPE_SOURCE })
        assertTrue(shipped.any { it.path.endsWith("core/src/testFixtures/kotlin/io/github/kauri_off/kiroku/core/demo/Demo.kt") })
        assertTrue(shipped.any { it.path.endsWith("data/session/MalSession.kt") })
        assertTrue(shipped.none { "/src/test/" in it.path || "/src/contract/" in it.path })
        assertEquals(emptyList<String>(), shipped.flatMap { scopeProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedScopeFails() {
        for (planted in listOf(
            "CoroutineScope(SupervisorJob() + Dispatchers.Default)",
            "CoroutineScope(Dispatchers.IO)",
            "CoroutineScope(context)",
            "scope + Job()",
            "CoroutineScope(scope.coroutineContext + SupervisorJob())",
            "MainScope()",
            "GlobalScope.launch { sync() }",
            "object : CoroutineScope { override val coroutineContext = Dispatchers.Default }",
        )) {
            assertEquals(planted, 1, scopeProblems("A.kt", "val a = $planted\n").size)
        }
        for (derived in listOf(
            "CoroutineScope(scope.coroutineContext + io)",
            "scope + Dispatchers.IO + SupervisorJob(scope.coroutineContext[Job])",
            "rememberCoroutineScope()",
            "viewModelScope.launch { }",
            "fun a(scope: CoroutineScope) = Unit",
        )) {
            assertEquals(derived, emptyList<String>(), scopeProblems("A.kt", "val a = $derived\n"))
        }
        assertEquals(emptyList<String>(), scopeProblems(APPLICATION_SCOPE_SOURCE, "val a = CoroutineScope(SupervisorJob())\n"))
    }

    // S7 and section 2.3: work without a screen has the app as WithoutScreen alone, whose calls go through MAL's one way in.
    @Test
    fun workWithoutAScreenIsGivenTheAppAsWithoutScreenAlone() {
        val sources = appSources()
        val without = sources.filter { runsWithoutAScreen(relative(it), it.readText()) }.map { it.name }.toSet()
        val known =
            listOf("RunReceiver.kt", "HeldRuns.kt", "MarkWatchedReceiver.kt", "PeriodicWork.kt", "ScenarioTile.kt", "DeviceClock.kt")
        assertTrue("$without", without.containsAll(known))
        assertTrue("$without", "LiveGraph.kt" !in without && "ListRoute.kt" !in without && "KirokuApplication.kt" !in without)
        assertEquals(emptyList<String>(), sources.flatMap { withoutScreenProblems(relative(it), it.readText()) })
        // The type hands out neither the list nor the graph: its members are the runs, the list's two calls and the airing's,
        // reads of the list and the session on the device, and the notice that a write could not be saved.
        val seam = sources.single { it.name == "WithoutScreen.kt" }.readText()
        val members = Regex("""interface WithoutScreen \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(KotlinSource(seam).code)
        val declared = Regex("""\b(val|fun)\s+(\w+)""").findAll(checkNotNull(members).groupValues[1]).map { it.groupValues[2] }.toList()
        assertEquals(listOf("notificationRuns", "syncList", "sendToList", "updateAiring", "titleOnList", "session", "notSaved"), declared)
    }

    @Test
    fun plantedWorkWithoutAScreenThatGoesPastWithoutScreenFails() {
        val receiver = "class A : BroadcastReceiver() {\n    override fun onReceive(context: Context, intent: Intent) {\n"
        val planted =
            listOf(
                "$receiver        scope.launch { context.appGraph.list.sync() }\n    }\n}\n",
                "$receiver        scope.launch { with(context.appGraph) { list.sync() } }\n    }\n}\n",
                "$receiver        val graph = (context.applicationContext as AppGraphOwner).graph\n    }\n}\n",
                "$receiver        scope.launch { context.ownList().sync() }\n    }\n}\n",
                "$receiver        scope.launch { context.ownList().send(write) }\n    }\n}\n",
                "$receiver        scope.launch { titles.refresh(id) }\n    }\n}\n",
                "$receiver        scope.launch { context.ownList().run { sync() } }\n    }\n}\n",
                "$receiver        scope.launch { context.ownList().apply { send(write) } }\n    }\n}\n",
                "$receiver        val download = context.ownList()::sync\n    }\n}\n",
                "$receiver        scope.launch { with(context.titles()) { refreshStale(id) } }\n    }\n}\n",
                "val a = registerReceiver(object : BroadcastReceiver() {\n" +
                    "    override fun onReceive(c: Context, i: Intent) = run { c.appGraph.list.sync() }\n})\n",
                "$receiver        (context.appGraph.withoutScreen { it } as BackgroundRun.Ran).value.sync()\n    }\n}\n",
                "class A(private val list: ListRepository) : Worker(context, parameters)\n",
                "class A(\n    private val list: ListSource,\n) : CoroutineWorker(context, parameters)\n",
                "class A(private val way: MalBackground) : Service()\n",
            )
        for (source in planted) assertEquals(source, 1, withoutScreenProblems("app/src/main/kotlin/A.kt", source).size)
        // Anything in the notify package counts, whatever it declares.
        val inPackage = "suspend fun a(list: ListRepository) = list\n"
        assertEquals(1, withoutScreenProblems("app/src/main/kotlin/notify/A.kt", inPackage).size)
        val clean =
            "$receiver        scope.launch {\n" +
                "            context.withoutScreen?.sendToList(ListWrite.MarkWatched(anime, episode))\n" +
                "            context.withoutScreen?.syncList()\n            context.heldRuns?.start(RunTrigger.MarkedWatched)\n" +
                "            val title = announcement.anime.id\n        }\n    }\n}\n"
        assertEquals(emptyList<String>(), withoutScreenProblems("app/src/main/kotlin/notify/A.kt", clean))
        // A screen's code is not held to it.
        assertEquals(emptyList<String>(), withoutScreenProblems("app/src/main/kotlin/list/A.kt", "val list = graph.list\n"))
    }

    @Test
    fun everyTestThatStartsTheAppEndsIt() {
        val sources = appTestSources()
        val starting = sources.flatMap { classesStartingTheApp(it.readText()) }
        assertTrue(starting.containsAll(listOf("DebugMenuTest", "SandboxAppTest", "MainActivityTest", "ImageLoaderTest", "DebugGraphTest")))
        assertTrue("SignInRouteTest" !in starting && "PosterFadeTest" !in starting)
        assertEquals(emptyList<String>(), sources.flatMap { appEndingProblems(relative(it), it.readText()) })
    }

    @Test
    fun aPlantedTestThatStartsTheAppWithoutEndingItFails() {
        val ends = "    @get:Rule(order = AppEnds.OUTERMOST)\n    val appEnds = AppEnds()\n"

        fun test(
            body: String,
            config: String = "@Config(qualifiers = \"en\")",
        ) = "@RunWith(RobolectricTestRunner::class)\n$config\nclass ATest {\n$body    @Test\n    fun a() = Unit\n}\n"
        for (start in listOf(
            "    val g = context.appGraph\n",
            "    val s get() = context.scenarios\n",
            "    val s = context.runningSource\n",
            "    val l = SingletonImageLoader.get(context)\n",
            "    @get:Rule\n    val c = createAndroidComposeRule<MainActivity>()\n",
            "    @get:Rule\n    val c = createAndroidComposeRule<DebugMenuActivity>()\n",
            "    val a = Robolectric.buildActivity(RedirectActivity::class.java)\n",
            "    val a = ActivityScenario.launch(MainActivity::class.java)\n",
            "    val g = createGraph(context, \"\", clock, scope)\n",
            "    val g = SandboxGraph.create(context, clock) { _, _, _ -> parts }\n",
        )) {
            assertEquals(start, 1, appEndingProblems("ATest.kt", test(start)).size)
            assertEquals(start, emptyList<String>(), appEndingProblems("ATest.kt", test(ends + start)))
            assertEquals(start, emptyList<String>(), appEndingProblems("ATest.kt", test(start, "@Config(application = A::class)")))
        }
        val inner = "    @get:Rule(order = 0)\n    val appEnds = AppEnds()\n    val g = context.appGraph\n"
        assertEquals(1, appEndingProblems("ATest.kt", test(inner)).size)
        val named = "    val n = MainActivity::class.java.name\n    // context.appGraph\n"
        assertEquals(emptyList<String>(), appEndingProblems("ATest.kt", test(named)))
        assertEquals(emptyList<String>(), appEndingProblems("ATest.kt", "class ATest {\n    val g = context.appGraph\n}\n"))
        val apostrophe = "    @Test\n    fun `the screen's form`() = Unit\n    val g = context.appGraph\n"
        assertEquals(1, appEndingProblems("ATest.kt", test(apostrophe)).size)
        val second = "class Other\n\n" + test("    val g = context.appGraph\n")
        assertEquals(
            listOf("ATest.kt:5: ATest starts the app without @get:Rule(order = AppEnds.OUTERMOST) val … = AppEnds()"),
            appEndingProblems("ATest.kt", second),
        )
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
