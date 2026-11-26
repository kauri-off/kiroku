package io.github.kauri_off.kiroku.list

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.MangaListRemovalResult
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.manga.mangaEntry
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ListRoot
import io.github.kauri_off.kiroku.shell.ProfileRoot
import io.github.kauri_off.kiroku.shell.SearchRoot
import io.github.kauri_off.kiroku.shell.ShellEntries
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.ShellRoute
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h600dp")
class ListModesRouteTest {
    private val compose = composeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val demo = TestGraph(Demo(Fixtures.reference, scope))
    private val entries =
        (1L..24).map {
            mangaEntry.copy(id = MangaId(it), titles = Titles("Reading title ${it.toString().padStart(2, '0')}"), posterUrl = null)
        }

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    @Test
    fun `switching to Manga leaves the pull indicator idle while a stale list refreshes`() {
        val entry = entries.first()
        val now = demo.clock.instant()
        var syncedAt = now
        var refreshes = 0
        var answer = CompletableDeferred<Unit>()
        val graph =
            object : AppGraph by demo {
                override val mangaList =
                    object : MangaListReader {
                        override suspend fun stored(owner: SignInId) = Outcome.Success(MangaListSnapshot(owner, listOf(entry), syncedAt))

                        override suspend fun refresh(
                            owner: SignInId,
                            canDispatch: () -> Boolean,
                        ): Outcome<MangaListSnapshot>? {
                            if (!canDispatch()) return null
                            refreshes++
                            answer.await()
                            syncedAt = now
                            return stored(owner)
                        }
                    }
            }
        val pullIndicator =
            compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            )
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        compose.onNodeWithText("Manga").performClick()
        val row = compose.onNodeWithText(entry.titles.romaji)
        row.assertIsDisplayed()
        pullIndicator.assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, refreshes) }

        compose.onNodeWithText("Anime").performClick()
        compose.onNodeWithText("Watching").assertIsDisplayed()
        compose.runOnIdle { syncedAt = now.minusSeconds(3600) }
        compose.onNodeWithText("Manga").performClick()
        row.assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, refreshes) }
        pullIndicator.assertDoesNotExist()
        compose.runOnIdle { answer.complete(Unit) }
        row.assertIsDisplayed()

        compose.runOnIdle { answer = CompletableDeferred() }
        compose.onNode(scroller(entry.titles.romaji)).performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.runOnIdle { assertEquals(2, refreshes) }
        pullIndicator.assertIsDisplayed()
        compose.runOnIdle { answer.complete(Unit) }
        pullIndicator.assertDoesNotExist()
    }

    @Test
    fun `the List modes restore their own controls and positions after switching and process death`() {
        var reads = 0
        val graph = graphWithManga { reads++ }
        var owner = newOwner()
        lateinit var navigator: ShellNavigator
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    navigator = rememberShellNavigator()
                    AppRoute(graph, navigator)
                }
            }
        }

        val plannedTitle = FixtureAnime.premiereTimeSoon.titles.romaji
        val planned = compose.onNodeWithText("Planned")
        val anime = compose.onNodeWithText("Anime")
        val manga = compose.onNodeWithText("Manga")
        anime.assertIsSelected()
        compose.onNodeWithText("My list").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sort").assertExists()
        planned.performClick()
        planned.assertIsSelected()
        scrollTo(plannedTitle, 3)
        val animePosition = scrollPosition(FixtureAnime.premiereMonth.titles.romaji)
        assertTrue(animePosition > 0f)
        compose.onNodeWithContentDescription("Filter by title").performClick()
        val query =
            FixtureAnime.exactToWatch.titles.romaji
                .take(3)
        val filter = compose.onNode(hasSetTextAction() and hasText(query))
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
        filter.assertIsFocused()
        anime.assertDoesNotExist()
        manga.assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear").performClick()
        assertEquals(0, reads)

        manga.performClick()
        manga.assertIsSelected()
        filter.assertDoesNotExist()
        assertEquals(listOf(ListRoot), navigator.stack)
        val mangaPlanned = "Planned title"
        scrollTo(entries.first().titles.romaji, 8)
        val readingRow = compose.onNode(hasText(entries[8].titles.romaji) and !hasSetTextAction())
        val readingTop = readingRow.getUnclippedBoundsInRoot().top
        planned.performClick()
        planned.assertIsSelected()
        scrollTo("$mangaPlanned 01", 10)
        val mangaRow = compose.onNodeWithText("$mangaPlanned 11")
        val mangaTop = mangaRow.getUnclippedBoundsInRoot().top
        compose.onNodeWithContentDescription("Filter by title").performClick()
        val mangaFilter = compose.onNode(hasSetTextAction())
        mangaFilter.assertIsFocused().performTextReplacement("reading TITLE 09")
        readingRow.assertIsDisplayed()
        compose.onNodeWithText("Reading").assertIsDisplayed()
        manga.assertDoesNotExist()
        compose.onNodeWithContentDescription("Sort").assertDoesNotExist()
        mangaFilter.performTextReplacement("there is no title by this name")
        compose.onNodeWithText("No titles match the filter").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        planned.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)

        compose.onNodeWithContentDescription("Filter by title").performClick()
        mangaFilter.performTextReplacement("Reading title 09")
        readingRow.performClick()
        assertEquals(listOf(ListRoot, MangaTitleKey(entries[8].id.value)), navigator.stack)
        compose.onNodeWithContentDescription("Back").performClick()
        mangaFilter.assertExists()
        readingRow.assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear").performClick()
        planned.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)
        compose
            .onNode(hasText("$mangaPlanned 11") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .assertDoesNotExist()

        anime.performClick()
        planned.assertIsSelected()
        assertEquals(animePosition, scrollPosition(FixtureAnime.premiereMonth.titles.romaji))
        manga.performClick()
        planned.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("Reading").performClick()
        assertEquals(readingTop, readingRow.getUnclippedBoundsInRoot().top)
        planned.performClick()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)
        mangaRow.performClick()
        assertEquals(listOf(ListRoot, MangaTitleKey(entries[10].id.value + 100)), navigator.stack)
        compose.onNodeWithContentDescription("Back").performClick()
        manga.assertIsSelected()
        planned.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)

        compose.onNodeWithText("Search").performClick()
        owner.viewModelStore.clear()
        owner = newOwner()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("List").performClick()
        manga.assertIsSelected()
        planned.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)
        anime.performClick()
        planned.assertIsSelected()
        assertEquals(animePosition, scrollPosition(FixtureAnime.premiereMonth.titles.romaji))
    }

    @Test
    fun `Manga sorts are per tab and independent of Anime and restore after recreation`() {
        val graph = graphWithManga()
        var owner = newOwner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNodeWithText("Manga").performClick()
        val sort = compose.onNodeWithContentDescription("Sort")
        sort.performClick()
        val options = compose.onNodeWithText("Sort by")
        options.assertIsDisplayed()
        compose.onNodeWithText("Last updated").assertIsSelected()
        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNodeWithText("Start date").assertDoesNotExist()
        compose.onNodeWithText("Title").performClick().assertIsSelected()
        compose.onNodeWithText("Z to A").performClick()
        options.assertIsDisplayed()
        dismissSheet()
        val first = compose.onNodeWithText("Reading title 24")
        val second = compose.onNodeWithText("Reading title 23")
        assertTrue(first.getUnclippedBoundsInRoot().top < second.getUnclippedBoundsInRoot().top)

        compose.onNodeWithText("Planned").performClick().assertIsSelected()
        sort.performClick()
        compose.onNodeWithText("Last updated").assertIsSelected()
        compose.onNodeWithText("Score").performClick().assertIsSelected()
        compose.onNodeWithText("Lowest first").performClick()
        dismissSheet()
        compose.onNodeWithText("Anime").performClick()
        sort.performClick()
        compose.onNodeWithText("Up next").assertIsSelected()
        dismissSheet()
        compose.onNodeWithText("Manga").performClick()
        compose.onNodeWithText("Planned").assertIsSelected()

        owner.viewModelStore.clear()
        owner = newOwner()
        restoration.emulateSavedInstanceStateRestore()
        sort.performClick()
        compose.onNodeWithText("Score").assertIsSelected()
        compose.onNodeWithText("Lowest first").assertIsOn()
        dismissSheet()
        compose.onNodeWithText("Reading").performClick().assertIsSelected()
        assertTrue(first.getUnclippedBoundsInRoot().top < second.getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `an early interrupted tab tap restores the page where the pager settled`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graphWithManga()) }
        }
        compose.onNodeWithText("Manga").performClick()
        val reading = compose.onNodeWithText("Reading")
        reading.assertIsSelected()
        compose.mainClock.autoAdvance = false
        val planned = compose.onNodeWithText("Planned")
        planned.performClick()
        compose.mainClock.advanceTimeByFrame()
        planned.assertIsSelected()
        compose.mainClock.advanceTimeBy(32)
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.35f, 0f))
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        reading.assertIsSelected()
        compose.onNodeWithText("Anime").performClick()
        compose.onNodeWithText("Manga").performClick()
        reading.assertIsSelected()
    }

    @Test
    fun `a refused edit keeps the selected unfamiliar tab and restores its final row`() {
        val unknown = MangaReadingStatus("rereading")
        val entry = entries.first().copy(status = unknown)
        val refuse = CompletableDeferred<Unit>()
        var requested = false
        val backend =
            object : MangaListEditor {
                override fun observe(owner: SignInId) = MutableStateFlow(Outcome.Success(snapshot(owner)))

                override suspend fun stored(owner: SignInId) = Outcome.Success(snapshot(owner))

                override suspend fun refresh(
                    owner: SignInId,
                    canDispatch: () -> Boolean,
                ) = error("The snapshot is fresh")

                override suspend fun remove(
                    owner: SignInId,
                    baseline: MangaListEntry,
                    retry: Boolean,
                ) = error("This test only edits a status")

                override suspend fun edit(
                    owner: SignInId,
                    id: MangaId,
                    changes: MangaListEdit,
                    retryFrom: MangaListEntry?,
                ): MangaListEditResult {
                    assertEquals(MangaListStatus.PlanToRead, changes.status)
                    requested = true
                    refuse.await()
                    return MangaListEditResult.Failed(DataError.Refused)
                }

                private fun snapshot(owner: SignInId) = MangaListSnapshot(owner, listOf(entry), demo.clock.instant())
            }
        val graph =
            object : AppGraph by demo {
                override val mangaList = backend
                override val mangaListEditor = backend
            }
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        compose.onNodeWithText("Manga").performClick()
        val tab = compose.onNodeWithText("Rereading")
        tab.performSemanticsAction(SemanticsActions.RequestFocus)
        tab.assertIsDisplayed().performClick()
        val title = entry.titles.romaji
        compose.onNodeWithText(title).assertIsDisplayed()
        tab.assertIsSelected()
        compose
            .onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose
            .onNode(hasText("Planned") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertTrue(requested) }
        assertTrue(compose.onAllNodesWithText("No titles in Rereading").fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { refuse.complete(Unit) }
        compose.onNodeWithText("Could not save").assertExists()
        dismissSheet()
        tab.assertIsSelected()
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    private fun dismissSheet() {
        val handle = compose.activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description)
        compose.onNodeWithContentDescription(handle).performSemanticsAction(SemanticsActions.Dismiss)
    }

    @Test
    fun `D-pad and keyboard activation keep the same focused mode segments`() {
        val graph = graphWithManga()
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
        }
        val anime = compose.onNodeWithText("Anime")
        val manga = compose.onNodeWithText("Manga")
        val ids = listOf(anime, manga).map { it.fetchSemanticsNode().id }
        anime.performSemanticsAction(SemanticsActions.RequestFocus)
        anime.assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        manga.assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        manga.assertIsSelected().assertIsFocused()
        assertEquals(ids, listOf(anime, manga).map { it.fetchSemanticsNode().id })

        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        anime.assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        anime.assertIsSelected().assertIsFocused()
        assertEquals(ids, listOf(anime, manga).map { it.fetchSemanticsNode().id })
    }

    @Test
    fun `restart does not compose Anime in the outgoing Manga entry`() {
        lateinit var navigator: ShellNavigator
        val presented = mutableSetOf<Pair<Int, ListMode>>()
        val entries: ShellEntries = { shell, _ ->
            entry<ListRoot> {
                val generation = remember { shell.restarts }
                val mode = shell.listMode
                SideEffect { presented += generation to mode }
                Text(mode.name)
            }
            entry<SearchRoot> { Text("Search screen") }
            entry<ProfileRoot> { Text("Profile screen") }
        }
        compose.setContent {
            navigator = rememberShellNavigator()
            KirokuTheme(dark = false, dynamicColor = false) { ShellRoute(navigator, entries = entries) }
        }
        compose.runOnUiThread { navigator.listMode = ListMode.Manga }
        compose.onNodeWithText("Manga").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            presented.clear()
            navigator.restart()
        }

        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnUiThread {
            assertEquals(setOf(1 to ListMode.Anime), presented)
        }
    }

    @Test
    fun `an acknowledged edit offers refresh and never resends`() = recoverEdit(pull = false, accepted = true)

    @Test
    fun `pulling My list settles an acknowledged edit after dismissal without resending`() = recoverEdit(pull = true, accepted = true)

    @Test
    fun `pulling My list settles a retryable edit after dismissal without resending`() = recoverEdit(pull = true, accepted = false)

    private fun recoverEdit(
        pull: Boolean,
        accepted: Boolean,
    ) {
        val entry = entries.first()
        val saved = MutableStateFlow(entry)
        var patches = 0
        var refreshes = 0

        fun snapshot(
            owner: SignInId,
            current: MangaListEntry,
        ) = MangaListSnapshot(owner, listOf(current), demo.clock.instant())
        val backend =
            object : MangaListEditor {
                override fun observe(owner: SignInId) = saved.map { Outcome.Success(snapshot(owner, it)) }

                override suspend fun stored(owner: SignInId) = Outcome.Success(snapshot(owner, saved.value))

                override suspend fun refresh(
                    owner: SignInId,
                    canDispatch: () -> Boolean,
                ): Outcome<MangaListSnapshot>? {
                    if (!canDispatch()) return null
                    refreshes++
                    saved.value = saved.value.copy(chaptersRead = 26)
                    return stored(owner)
                }

                override suspend fun remove(
                    owner: SignInId,
                    baseline: MangaListEntry,
                    retry: Boolean,
                ): MangaListRemovalResult = error("This test only saves changes")

                override suspend fun edit(
                    owner: SignInId,
                    id: MangaId,
                    changes: MangaListEdit,
                    retryFrom: MangaListEntry?,
                ): MangaListEditResult {
                    assertEquals(MangaListEdit(chaptersRead = 26), changes)
                    assertEquals(null, retryFrom)
                    patches++
                    return if (accepted) {
                        MangaListEditResult.Accepted(Outcome.Failure(DataError.NoConnection))
                    } else {
                        MangaListEditResult.Failed(DataError.NoConnection)
                    }
                }
            }
        val graph =
            object : AppGraph by demo {
                override val mangaList = backend
                override val mangaListEditor = backend
            }
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
        }
        compose.onNodeWithText("Manga").performClick()
        val editRow = compose.onNode(hasText(entry.titles.romaji) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        editRow.performSemanticsAction(SemanticsActions.OnLongClick)
        val chapters = compose.onNodeWithContentDescription("Chapters read")
        chapters.performTextReplacement("26")
        chapters.performImeAction()
        compose.onNodeWithText("Save").performClick()
        if (accepted) {
            compose.onNodeWithText("Could not confirm save").assertExists()
            compose.onNodeWithText("Retry").assertDoesNotExist()
        } else {
            compose.onNodeWithText("Retry").assertExists()
        }
        if (pull) {
            val dragHandle = compose.activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description)
            compose.onNodeWithContentDescription(dragHandle).performSemanticsAction(SemanticsActions.Dismiss)
            compose.onNodeWithContentDescription("Chapters read").assertDoesNotExist()
            compose.onNode(scroller(entry.titles.romaji)).performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        } else {
            compose.onNodeWithText("Refresh list").performClick()
        }
        compose.onNodeWithText("26\u00A0chapters", substring = true).assertExists()
        editRow.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Could not confirm save").assertDoesNotExist()
        compose.onNodeWithText("Could not save").assertDoesNotExist()
        assertEquals(1, patches)
        assertEquals(1, refreshes)
    }

    @Test
    fun `expiry from a queued refresh cannot restore an already removed row`() {
        val owner = SignInId("manga-owner")
        val publishedSession = MutableStateFlow<Session>(Session.SignedIn(owner))
        val saved = MutableStateFlow(entries.take(2))
        val removed = CompletableDeferred<Unit>()
        val expire = CompletableDeferred<Unit>()
        var removalStarted = false
        var refreshQueued = false

        fun snapshot(current: List<MangaListEntry>) = MangaListSnapshot(owner, current, demo.clock.instant())
        val backend =
            object : MangaListEditor {
                override fun observe(owner: SignInId) = saved.map { Outcome.Success(snapshot(it)) }

                override suspend fun stored(owner: SignInId) = Outcome.Success(snapshot(saved.value))

                override suspend fun refresh(
                    owner: SignInId,
                    canDispatch: () -> Boolean,
                ): Outcome<MangaListSnapshot>? {
                    refreshQueued = true
                    expire.await()
                    if (!canDispatch()) return null
                    publishedSession.value = Session.Expired(owner)
                    return Outcome.Failure(DataError.SessionExpired)
                }

                override suspend fun remove(
                    owner: SignInId,
                    baseline: MangaListEntry,
                    retry: Boolean,
                ): MangaListRemovalResult {
                    removalStarted = true
                    removed.await()
                    saved.value = saved.value.filter { it.id != baseline.id }
                    return MangaListRemovalResult.Removed
                }

                override suspend fun edit(
                    owner: SignInId,
                    id: MangaId,
                    changes: MangaListEdit,
                    retryFrom: MangaListEntry?,
                ) = error("The test only removes an entry")
            }
        val graph =
            object : AppGraph by demo {
                override val session =
                    object : SessionRepository by demo.session {
                        override fun observeSession() = publishedSession
                    }
                override val mangaList = backend
                override val mangaListEditor = backend
            }
        val first = entries.first().titles.romaji
        val survivor = entries[1].titles.romaji
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        compose.onNodeWithText("Manga").performClick()
        compose
            .onNode(hasText(first) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText("Remove from list").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertTrue(removalStarted) }
        val dragHandle = compose.activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description)
        compose.onNodeWithContentDescription(dragHandle).performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithContentDescription("Chapters read").assertDoesNotExist()
        compose.onNode(scroller(survivor)).performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.runOnIdle {
            assertTrue(refreshQueued)
            removed.complete(Unit)
        }
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.runOnIdle { expire.complete(Unit) }
        compose.onNodeWithText("Session expired").assertExists()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(survivor).assertExists()
        compose.onNode(hasText(survivor) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).assertDoesNotExist()
    }

    private fun graphWithManga(onRead: () -> Unit = {}): AppGraph =
        object : AppGraph by demo {
            override val mangaList =
                object : MangaListReader {
                    override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> {
                        onRead()
                        val planned =
                            entries.map { entry ->
                                entry.copy(
                                    id = MangaId(entry.id.value + 100),
                                    titles = Titles("Planned title ${entry.id.value.toString().padStart(2, '0')}"),
                                    status = MangaReadingStatus("plan_to_read"),
                                )
                            }
                        return Outcome.Success(MangaListSnapshot(owner, entries + planned, demo.clock.instant()))
                    }

                    override suspend fun refresh(
                        owner: SignInId,
                        canDispatch: () -> Boolean,
                    ): Outcome<MangaListSnapshot>? = error("The snapshot is fresh")
                }
        }

    private fun scrollTo(
        title: String,
        index: Int,
    ) = compose.onNode(scroller(title)).performScrollToIndex(index)

    private fun scrollPosition(title: String): Float =
        compose
            .onNode(scroller(title))
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun scroller(title: String) =
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText(title))

    private fun newOwner() =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
}
