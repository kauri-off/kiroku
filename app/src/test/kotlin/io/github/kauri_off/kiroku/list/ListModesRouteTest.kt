package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.manga.mangaEntry
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ListRoot
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val entries = (1L..24).map { mangaEntry.copy(id = MangaId(it), titles = Titles("Reading title $it"), posterUrl = null) }

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    @Test
    fun `the List modes restore their own controls and positions after switching and process death`() {
        var reads = 0
        val graph =
            object : AppGraph by demo {
                override val mangaList =
                    object : MangaListReader {
                        override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> {
                            reads++
                            return Outcome.Success(MangaListSnapshot(owner, entries, demo.clock.instant()))
                        }

                        override suspend fun refresh(
                            owner: SignInId,
                            canDispatch: () -> Boolean,
                        ): Outcome<MangaListSnapshot>? = error("The snapshot is fresh")
                    }
            }
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
        compose.onNodeWithText("Anime").assertIsSelected()
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
        assertEquals(0, reads)

        val manga = compose.onNodeWithText("Manga")
        manga.performClick()
        manga.assertIsSelected()
        filter.assertDoesNotExist()
        assertEquals(listOf(ListRoot), navigator.stack)
        scrollTo(entries.first().titles.romaji, 10)
        val mangaRow = compose.onNodeWithText(entries[10].titles.romaji)
        val mangaTop = mangaRow.getUnclippedBoundsInRoot().top

        compose.onNodeWithText("Anime").performClick()
        filter.assertExists()
        compose.onNodeWithText("Manga").performClick()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)
        mangaRow.performClick()
        assertEquals(listOf(ListRoot, MangaTitleKey(entries[10].id.value)), navigator.stack)
        compose.onNodeWithContentDescription("Back").performClick()
        manga.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)

        compose.onNodeWithText("Search").performClick()
        owner.viewModelStore.clear()
        owner = newOwner()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("List").performClick()
        manga.assertIsSelected()
        assertEquals(mangaTop, mangaRow.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("Anime").performClick()
        filter.assertExists()
        compose.onNodeWithContentDescription("Clear").performClick()
        planned.assertIsSelected()
        assertEquals(animePosition, scrollPosition(FixtureAnime.premiereMonth.titles.romaji))
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
