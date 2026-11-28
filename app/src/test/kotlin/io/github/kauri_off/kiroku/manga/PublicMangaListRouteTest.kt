package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.user.MangaListKeeper
import io.github.kauri_off.kiroku.core.user.RemoteMangaLists
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.profile.OtherProfileKey
import io.github.kauri_off.kiroku.profile.ProfileTags
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import io.github.kauri_off.kiroku.wiring.ServedMal
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class PublicMangaListRouteTest {
    private val compose = composeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val mal = ServedMal()

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(mal).around(registered(compose) { scope.cancel() })

    private val fixtures = Fixtures.reference
    private val demo = Demo(fixtures, scope)
    private val base = TestGraph(demo)
    private val requests = mutableListOf<String>()
    private var answer: suspend (suspend (List<MangaListEntry>) -> Unit) -> Outcome<Unit> = { emit ->
        emit(listOf(mangaEntry.copy(score = 9)))
        Outcome.Success(Unit)
    }
    private val source =
        object : RemoteMangaLists {
            override suspend fun read(
                name: String,
                part: suspend (List<MangaListEntry>) -> Unit,
            ): Outcome<Unit> {
                requests += name
                return answer(part)
            }

            override fun sameUser(
                a: String,
                b: String,
            ) = a == b
        }
    private val graph = coldGraph()

    private fun coldGraph(): AppGraph =
        object : AppGraph by base {
            override val publicMangaLists = MangaListKeeper(source, scope, { throw it })
        }

    private lateinit var navigator: ShellNavigator

    @Test
    fun `private Anime profile opens public Manga without edit affordances`() {
        profile(fixtures.privateUserName)
        compose.onNodeWithText("This list is private").assertIsDisplayed()
        viewManga()
        compose.onNodeWithText("${fixtures.privateUserName}'s manga list").assertIsDisplayed()
        row(mangaEntry.titles.romaji).assert(!SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.onNodeWithText("Score\u00A09", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Your score", substring = true).assertDoesNotExist()
        assertEquals(listOf(fixtures.privateUserName), requests)
    }

    @Test
    fun `capability gates Manga and an unknown Anime profile can correct the name`() {
        profile("unknown_fixture")
        compose.onNodeWithText("View manga list").assertDoesNotExist()
        compose.onNodeWithText("Open another profile").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction() and hasText("unknown_fixture")).assertIsDisplayed()
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `unsupported graph keeps the Anime-only profile`() {
        profile(fixtures.otherUserName, base)
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("View list"))
        compose.onNodeWithText("View list").assertIsDisplayed()
        compose.onNodeWithText("View manga list").assertDoesNotExist()
    }

    @Test
    fun `unavailable Anime profile opens Manga and retries a failed cold read to true empty`() {
        demo.server.failingLoads.value = true
        answer = { Outcome.Failure(DataError.NoConnection) }
        base.connectionSays.value = false
        profile(fixtures.otherUserName)
        compose.onNodeWithText("Retry").assertIsDisplayed()
        viewManga()
        compose.onNodeWithText("No connection").assertIsDisplayed()
        compose.runOnUiThread { answer = { Outcome.Success(Unit) } }
        compose.onNodeWithText("Retry").performClick()
        val empty = compose.onAllNodesWithText("This manga list is empty")
        assertEquals(1, empty.fetchSemanticsNodes().indices.count { empty[it].isDisplayed() })
        assertEquals(List(2) { fixtures.otherUserName }, requests)
    }

    @Test
    fun `expired session keeps public Manga readable and retryable`() {
        demo.server.sessionExpired.value = true
        answer = { Outcome.Failure(DataError.ServerError) }
        profile(fixtures.privateUserName)
        viewManga()
        compose.onNodeWithText("Session expired").assertIsDisplayed()
        compose.onNodeWithText("Could not load the list").assertIsDisplayed()
        compose.runOnUiThread {
            answer = { emit ->
                emit(listOf(mangaEntry))
                Outcome.Success(Unit)
            }
        }
        compose.onNodeWithText("Retry").performClick()
        row(mangaEntry.titles.romaji).assertIsDisplayed()
    }

    @Test
    fun `signed out profile can read public Manga through the default live graph`() {
        val name = fixtures.privateUserName
        mal.publicAnime = ServedMal.answer(403, ServedMal.recorded("list_private.403.json"))
        mal.publicManga = { ServedMal.answer(200, """{"data":[],"paging":{}}""") }
        val source = LiveSource("public-manga-route", mal.hosts, aniList = ServedMal.NO_ANILIST, tenrai = ServedMal.NO_TENRAI)
        val process = CoroutineScope(scope.coroutineContext + Dispatchers.Default)
        val parts = LiveParts(RuntimeEnvironment.getApplication(), source, "0123abcd", Clock.systemUTC(), process, { throw it })
        val live = LiveGraph(parts, listStore = { InMemoryLocalList() })

        assertEquals(Session.SignedOut, runBlocking { withTimeout(5_000) { live.session.observeSession().first() } })
        profile(name, live)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This list is private").fetchSemanticsNodes().isNotEmpty() }
        viewManga()
        val empty = compose.onAllNodesWithText("This manga list is empty")
        compose.waitUntil(5_000) { empty.fetchSemanticsNodes().isNotEmpty() }
        assertEquals(PublicMangaListKey(name), compose.runOnIdle { navigator.stack.last() })
        assertEquals(1, mal.asked.count { it.startsWith("GET /v2/users/$name/mangalist?") })
        assertEquals(Session.SignedOut, runBlocking { withTimeout(5_000) { live.session.observeSession().first() } })
    }

    @Test
    fun `filtered and normal viewports survive a title and completed pagination`() {
        val finish = CompletableDeferred<Unit>()
        val first = (10L..40).map(::panel)
        val preceding = panel(1)
        answer = { emit ->
            emit(first)
            finish.await()
            emit(listOf(preceding))
            Outcome.Success(Unit)
        }
        profile(fixtures.privateUserName)
        viewManga()
        val read = compose.onNode(hasText("Read") and isSelectable())
        read.performClick()
        val initialRow = row(first[12].titles.romaji)
        compose.onNode(scroller).performScrollToIndex(12)
        val atRowStart = initialRow.getUnclippedBoundsInRoot().top
        compose.onNode(scroller).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 21f) }
        val originalTop = initialRow.getUnclippedBoundsInRoot().top
        assertNotEquals(atRowStart, originalTop)
        initialRow.performClick()
        val title = compose.runOnIdle { navigator.stack.last() as MangaTitleKey }
        assertEquals(first[12].id.value, title.id)
        assertNull(title.opened?.owner)
        assertEquals(first[12].titles, title.opened?.details?.titles)
        compose.runOnUiThread { finish.complete(Unit) }
        compose.waitForIdle()
        back()
        read.assertIsSelected()
        assertEquals(originalTop, initialRow.getUnclippedBoundsInRoot().top)

        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Panel")
        compose.onNode(scroller).performScrollToIndex(18)
        val result = row(first[17].titles.romaji)
        val filterTop = result.getUnclippedBoundsInRoot().top
        result.performClick()
        back()
        compose.onNode(hasSetTextAction() and hasText("Panel")).assertIsDisplayed()
        assertEquals(filterTop, result.getUnclippedBoundsInRoot().top)

        compose.onNodeWithContentDescription("Clear").performClick()
        read.assertIsSelected()
        assertEquals(originalTop, initialRow.getUnclippedBoundsInRoot().top)
        assertEquals(listOf(fixtures.privateUserName), requests)
    }

    @Test
    fun `process recreation restores the sorted tab to an anchor on a delayed second page`() = restoreLaterPage(filtered = false)

    @Test
    fun `process recreation restores the filtered viewport to an anchor on a delayed second page`() = restoreLaterPage(filtered = true)

    private fun restoreLaterPage(filtered: Boolean) {
        val first = (1L..20).map(::panel)
        val second = (21L..45).map(::panel)
        answer = { emit ->
            emit(first)
            emit(second)
            Outcome.Success(Unit)
        }
        var owner = routeOwner()
        var activeGraph = graph
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    navigator = rememberShellNavigator()
                    AppRoute(activeGraph, navigator, remember { SnackbarHostState() })
                }
            }
        }
        openProfile(fixtures.privateUserName)
        viewManga()
        val read = compose.onNode(hasText("Read") and isSelectable())
        read.performClick()
        val sort = compose.onNodeWithContentDescription("Sort")
        sort.performClick()
        compose.onNodeWithText("Chapters read").assertDoesNotExist()
        compose.onNodeWithText("Title").performClick()
        compose.onNodeWithText("Z to A").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)

        if (filtered) {
            compose.onNodeWithContentDescription("Filter by title").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Panel")
        }
        val anchor = row(panel(33).titles.romaji)
        compose.onNode(scroller and hasAnyDescendant(hasText(second.last().titles.romaji))).performScrollToIndex(12)
        val aligned = anchor.getUnclippedBoundsInRoot().top
        compose
            .onNode(scroller and hasAnyDescendant(hasText(panel(33).titles.romaji)))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 21f) }
        val savedTop = anchor.getUnclippedBoundsInRoot().top
        assertNotEquals(aligned, savedTop)

        val nextPage = CompletableDeferred<Outcome<Unit>>()
        compose.runOnIdle {
            owner.viewModelStore.clear()
            owner = routeOwner()
            activeGraph = coldGraph()
            answer = { emit ->
                emit(first)
                nextPage.await().also { if (it is Outcome.Success) emit(second) }
            }
        }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(PublicMangaListKey(fixtures.privateUserName), compose.runOnIdle { navigator.stack.last() })
        if (filtered) {
            compose.onNode(hasSetTextAction() and hasText("Panel")).assertIsDisplayed()
        } else {
            read.assertIsSelected()
        }
        row(first.first().titles.romaji).assertIsDisplayed()
        anchor.assertDoesNotExist()

        if (filtered) {
            val retryPage = CompletableDeferred<Unit>()
            compose.runOnUiThread {
                nextPage.complete(Outcome.Failure(DataError.ServerError))
                answer = { emit ->
                    emit(first)
                    retryPage.await()
                    emit(second)
                    Outcome.Success(Unit)
                }
            }
            compose.onNodeWithText("Retry").performClick()
            row(first.first().titles.romaji).assertIsDisplayed()
            anchor.assertDoesNotExist()
            compose.runOnUiThread { retryPage.complete(Unit) }
        } else {
            compose.runOnUiThread { nextPage.complete(Outcome.Success(Unit)) }
        }
        assertEquals(savedTop, anchor.getUnclippedBoundsInRoot().top)
        assertEquals(List(if (filtered) 3 else 2) { fixtures.privateUserName }, requests)
        if (filtered) compose.onNodeWithContentDescription("Clear").performClick()
        sort.performClick()
        compose.onNodeWithText("Title").assertIsSelected()
        compose.onNodeWithText("Z to A").assertIsOn()
    }

    private fun routeOwner(): ViewModelStoreOwner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    @Test
    fun `empty tab and filter differ from the whole list being empty`() {
        profile(fixtures.privateUserName)
        viewManga()
        compose.onNode(hasText("Read") and isSelectable()).performClick()
        compose.onNodeWithText("No titles in Read").assertIsDisplayed()
        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("no-such-title")
        compose.onNodeWithText("No titles match the filter").assertIsDisplayed()
        compose.onNodeWithText("This manga list is empty").assertDoesNotExist()
    }

    @Test
    fun `offline warm refresh keeps rows, posts a message and recovers once on reconnect`() {
        profile(fixtures.privateUserName)
        viewManga()
        val previous = row(mangaEntry.titles.romaji)
        previous.assertIsDisplayed()
        compose.runOnUiThread {
            answer = { Outcome.Failure(DataError.NoConnection) }
            base.connectionSays.value = false
        }
        compose
            .onNode(scroller and hasAnyDescendant(hasText(mangaEntry.titles.romaji)))
            .performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.onNodeWithText("No connection").assertIsDisplayed()
        previous.assertIsDisplayed()
        assertEquals(2, requests.size)

        compose.runOnUiThread {
            answer = { emit ->
                emit(listOf(panel(2)))
                Outcome.Success(Unit)
            }
            base.connectionSays.value = true
        }
        compose.onNode(hasText("Read") and isSelectable()).performClick()
        row(panel(2).titles.romaji).assertIsDisplayed()
        previous.assertDoesNotExist()
        assertEquals(3, requests.size)
    }

    @Test
    fun `confirmed Manga privacy and unknown responses show their own status without Retry`() {
        answer = { Outcome.Failure(DataError.PrivateList) }
        profile(fixtures.privateUserName)
        viewManga()
        compose.onNodeWithText("This list is private").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        back()
        compose.runOnUiThread { navigator.open(OtherProfileKey(fixtures.otherUserName)) }
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("View manga list"))
        compose.runOnUiThread { answer = { Outcome.Failure(DataError.UnknownUser) } }
        viewManga()
        compose.onNodeWithText("MyAnimeList has no user with this name").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    private fun profile(
        name: String,
        graph: AppGraph = this.graph,
    ) {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, remember { SnackbarHostState() })
            }
        }
        openProfile(name)
    }

    private fun openProfile(name: String) {
        compose.runOnIdle {
            navigator.home(Destination.Profile)
            navigator.open(OtherProfileKey(name))
        }
        compose.waitForIdle()
    }

    private fun viewManga() {
        compose.onNode(hasText("View manga list") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { navigator.back() }
        compose.waitForIdle()
    }

    private fun row(title: String) = compose.onNode(hasText(title) and hasClickAction() and !hasSetTextAction())

    private fun panel(id: Long): MangaListEntry =
        mangaEntry.copy(id = MangaId(id), titles = Titles("Panel %02d".format(id)), status = MangaReadingStatus("completed"))

    private val scroller =
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
}
