package io.github.kauri_off.kiroku.search

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaCatalogue
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.manga.mangaFixture
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class MangaCatalogueRouteTest {
    private val main = StandardTestDispatcher()
    private val compose = composeRule()
    private val scope = CoroutineScope(SupervisorJob() + main)

    @get:Rule
    val rule: TestRule = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose) { scope.cancel() })

    private val demo = TestGraph(Demo(Fixtures.reference, scope))
    private val searchOffsets = mutableListOf<Int>()
    private val rankingAttempts = mutableListOf<Int>()
    private val rankingOffsets = mutableListOf<Int>()
    private val detailIds = mutableListOf<MangaId>()
    private val detail = CompletableDeferred<Outcome<StoredManga>>()
    private val firstPage = (1..20).map { row(it) }
    private val secondPage = listOf(mangaFixture.copy(synopsis = null)) + (22..40).map { row(it) }
    private val title = mangaFixture.titles.romaji
    private val inDialog = hasAnyAncestor(isDialog())
    private val scrollable = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)

    @Test
    fun `manga search retries its first page and Back keeps its query and scrolled continuation`() {
        catalogue()
        compose.onNode(hasSetTextAction() and !inDialog).performClick()
        settle()
        compose.onNode(hasSetTextAction() and isFocused() and inDialog).performTextInput("paper")
        settle()
        compose.onNode(hasText("Could not search") and inDialog).assertExists()
        compose.onNode(hasText("Retry") and inDialog).performClick()
        settle()
        compose.onNode(scrollable and inDialog).performScrollToIndex(19)
        settle()
        compose.onNode(scrollable and inDialog).performScrollToIndex(20)
        compose.onNode(hasText(title) and inDialog).performClick()
        settle()

        compose.onNodeWithText(title).assertIsDisplayed()
        assertEquals(listOf(mangaFixture.id), detailIds)
        assertTrue(demo.requested.isEmpty())
        detail.complete(Outcome.Failure(DataError.ServerError))
        settle()
        compose.onNodeWithText(title).assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        compose.onNode(hasSetTextAction() and hasText("paper") and !inDialog).assertExists()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(firstPage.first().titles.romaji).assertDoesNotExist()
        assertEquals(listOf(0, 0, 20), searchOffsets)
        assertTrue(rankingOffsets.isEmpty())
    }

    @Test
    fun `ranking retries its continuation and Back keeps the rank and loaded pages`() {
        catalogue()
        compose.onNodeWithText("Manga ranking").performClick()
        settle()
        compose.onNode(scrollable).performScrollToIndex(19)
        settle()
        compose.onNode(scrollable).performScrollToIndex(20)
        compose.onNodeWithText("Could not load this list").assertExists()
        compose.onNodeWithText("Retry").performClick()
        settle()
        compose.onNode(scrollable).performScrollToIndex(20)
        val rankedTitle = "21.\u00a0$title"
        compose.onNodeWithText(rankedTitle).performClick()
        settle()
        compose.onNodeWithText(title).assertIsDisplayed()
        assertEquals(listOf(mangaFixture.id), detailIds)
        detail.complete(Outcome.Success(StoredManga(mangaFixture, demo.clock.instant())))
        settle()
        compose.onNodeWithText("Synopsis").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        compose.onNodeWithText(rankedTitle).assertIsDisplayed()
        assertEquals(listOf(0, 20, 20), rankingOffsets)
        assertTrue(searchOffsets.isEmpty())
    }

    @Test
    fun `opening a result pauses a deferred ranking Retry until Back`() {
        val ready = CompletableDeferred<Unit>()
        catalogue(beforeRankingRetry = ready)
        compose.onNodeWithText("Manga ranking").performClick()
        settle()
        compose.onNode(scrollable).performScrollToIndex(19)
        settle()
        compose.onNode(scrollable).performScrollToIndex(20)
        compose.onNodeWithText("Retry").performClick()
        settle()
        assertEquals(listOf(0, 20, 20), rankingAttempts)
        assertEquals(listOf(0, 20), rankingOffsets)

        val last = firstPage.last().titles.romaji
        compose.onNodeWithText("20.\u00a0$last").performClick()
        settle()
        compose.onNodeWithText(last).assertIsDisplayed()
        ready.complete(Unit)
        settle()
        assertEquals(listOf(0, 20), rankingOffsets)

        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        assertEquals(listOf(0, 20, 20, 20), rankingAttempts)
        assertEquals(listOf(0, 20, 20), rankingOffsets)
        compose.onNode(scrollable).performScrollToIndex(20)
        compose.onNodeWithText("21.\u00a0$title").assertIsDisplayed()
    }

    @Test
    fun `a card opened with sparse fresh details shows both synopsis and row score`() {
        val sparse = firstPage.first().copy(synopsis = checkNotNull(mangaFixture.synopsis), meanScore = null)
        catalogue(stored = StoredManga(sparse, demo.clock.instant()))
        compose.onNodeWithText("Manga ranking").performClick()
        settle()
        compose.onNodeWithText("1.\u00a0${sparse.titles.romaji}").performClick()
        settle()

        compose.onNodeWithText("Synopsis").assertExists()
        compose.onNodeWithText("MAL score 9.16").assertExists()
        assertTrue(detailIds.isEmpty())
    }

    @Test
    fun `IME Search keeps text committed in the same turn before debounce`() {
        catalogue()
        compose.onNode(hasSetTextAction() and !inDialog).performClick()
        settle()
        val input = compose.onNode(hasSetTextAction() and isFocused() and inDialog).fetchSemanticsNode().config
        compose.runOnUiThread {
            assertTrue(input[SemanticsActions.SetText].action?.invoke(AnnotatedString("paper")) == true)
            assertTrue(input[SemanticsActions.OnImeAction].action?.invoke() == true)
        }
        compose.onNode(hasContentDescription("Clear search") and inDialog).performClick()
        compose.onNode(hasText("paper") and hasClickAction() and inDialog).assertExists()
        assertTrue(searchOffsets.isEmpty())
    }

    @Test
    fun `collapsed and expanded search fields identify manga in English`() {
        checksSearchLabel("Search manga")
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun `collapsed and expanded search fields identify manga in Russian`() {
        checksSearchLabel("Поиск манги")
    }

    private fun checksSearchLabel(label: String) {
        catalogue()
        val field = hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label))
        compose.onNode(field and !inDialog).performClick()
        settle()
        compose.onNode(field and inDialog and isFocused()).assertExists()
    }

    private fun catalogue(
        stored: StoredManga? = null,
        beforeRankingRetry: CompletableDeferred<Unit>? = null,
    ) {
        val graph =
            object : AppGraph by demo {
                override val madeUp = false
                override val mangaCatalogue =
                    object : MangaCatalogue {
                        override suspend fun search(
                            query: String,
                            offset: Int,
                            includeAdult: Boolean,
                        ): Outcome<Page<MangaDetails>> {
                            assertEquals("paper", query)
                            searchOffsets += offset
                            return when {
                                searchOffsets.size == 1 -> Outcome.Failure(DataError.ServerError)
                                offset == 0 -> Outcome.Success(Page(firstPage, 20))
                                else -> Outcome.Success(Page(secondPage, null))
                            }
                        }

                        override suspend fun ranking(
                            offset: Int,
                            includeAdult: Boolean,
                            canDispatch: () -> Boolean,
                        ): Outcome<Page<Ranked<MangaDetails>>>? {
                            rankingAttempts += offset
                            if (rankingAttempts.size >= 3) beforeRankingRetry?.await()
                            if (!canDispatch()) return null
                            rankingOffsets += offset
                            if (rankingOffsets.size == 2) return Outcome.Failure(DataError.ServerError)
                            val rows = if (offset == 0) firstPage else secondPage
                            return Outcome.Success(
                                Page(rows.mapIndexed { index, manga -> Ranked(offset + index + 1, manga) }, 20.takeIf { offset == 0 }),
                            )
                        }
                    }

                override val manga =
                    object : MangaRepository {
                        override fun observeOwner() = demo.session.observeSession().map { SessionOwner(it.owner) }

                        override suspend fun stored(
                            id: MangaId,
                            owner: SessionOwner,
                        ): Outcome<StoredManga?> = Outcome.Success(stored?.takeIf { it.details.id == id })

                        override suspend fun refresh(
                            id: MangaId,
                            owner: SessionOwner,
                            canDispatch: () -> Boolean,
                        ): Outcome<StoredManga>? {
                            if (!canDispatch()) return null
                            detailIds += id
                            return detail.await()
                        }
                    }
            }
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        settle()
        val activity = compose.activity
        compose.onNodeWithText(activity.getString(R.string.shell_search)).performClick()
        settle()
        compose.onNodeWithText(activity.getString(R.string.search_suggestions)).assertExists()
        compose.onNodeWithText(activity.getString(R.string.manga_catalogue)).performClick()
        settle()
        assertTrue(searchOffsets.isEmpty())
        assertTrue(rankingOffsets.isEmpty())
    }

    private fun settle() =
        repeat(4) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }

    private fun row(index: Int) =
        mangaFixture.copy(id = MangaId(10_000L + index), titles = Titles("Manga volume $index"), posterUrl = null, synopsis = null)
}
