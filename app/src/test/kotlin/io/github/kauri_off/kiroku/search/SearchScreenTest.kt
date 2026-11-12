package io.github.kauri_off.kiroku.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.screenshots.Shot
import io.github.kauri_off.kiroku.screenshots.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SearchScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<SearchEvent>()

    private val shown = mutableStateOf<SearchState?>(null)

    private fun show(state: SearchState) {
        val first = shown.value == null
        shown.value = state
        if (first) {
            compose.setContent {
                KirokuTheme(
                    dark = false,
                    dynamicColor = false,
                ) { shown.value?.let { SearchScreen(it, { events += it }, SnackbarHostState()) } }
            }
        }
        compose.waitForIdle()
    }

    private fun results(
        query: String,
        results: Paged<ResultRow>,
    ) = SearchFixtureStates.state(query, SearchContent.Results(query, results))

    @Test
    fun `only a query of digits alone names a title id`() {
        assertEquals(AnimeId(52991), queriedId("52991"))
        assertEquals(AnimeId(52991), queriedId(" 52991 "))
        for (query in listOf("", " ", "86 Eighty-Six", "5299l", "+52991", "0", "-5", "99999999999999999999")) {
            assertNull(query, queriedId(query))
        }
    }

    @Test
    fun `a query of digits has the row that opens its title above the results, in every state of them`() {
        val id = FixtureAnime.notOnListAiring.id
        val digits = id.value.toString()
        for (paged in listOf(Paged.Shown(emptyList()), Paged.Loading, Paged.Failed(DataError.NoConnection))) {
            show(results(digits, paged))
            compose.onNodeWithText("Open title #$digits").assertExists()
        }
        compose.onNodeWithText("Open title #$digits").performClick()
        compose.waitForIdle()
        assertEquals(SearchEvent.Open(id), events.last())
    }

    @Test
    fun `a query with a letter has no such row, and an empty result says so`() {
        show(SearchFixtureStates.noResults)
        compose.onNodeWithText("Open title", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Nothing found", substring = true).assertExists()
    }

    @Test
    fun `before a query the recent searches come first, then the four browse lists, each doing what it says`() {
        show(SearchFixtureStates.start)
        for (query in SearchFixtureStates.recent) compose.onNodeWithText(query).assertExists()
        for (name in listOf("This season", "Top airing", "Top rated", "Most popular")) compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText(SearchFixtureStates.recent.first()).performClick()
        compose.onNodeWithText("Top rated").performClick()
        compose.waitForIdle()
        assertEquals(listOf(SearchEvent.SearchAgain(SearchFixtureStates.recent.first()), SearchEvent.Browse(BrowseList.TopRated)), events)
    }

    @Test
    fun `without recent searches the browse lists stand alone`() {
        show(SearchFixtureStates.startNoRecent)
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
        compose.onNodeWithText("This season").assertExists()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `at most five recent searches are shown`() {
        SearchContent.Start(List(MAX_RECENT_SEARCHES + 1) { it.toString() })
    }

    @Test
    fun `a further page is asked for when the end of the results comes into view, and once`() {
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query).take(1)
        show(results(SearchFixtureStates.query, Paged.Shown(rows, More.Ready)))
        compose.waitForIdle()
        assertEquals(listOf(SearchEvent.LoadMore), events)
    }

    @Test
    fun `a failed further page keeps the rows and offers Retry`() {
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query).take(1)
        show(results(SearchFixtureStates.query, Paged.Shown(rows, More.Failed(DataError.Refused))))
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji).assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(SearchEvent.Retry, events.last())
    }
}

// The search field, under the bar and in the search view's own window, is offered neither to autofill nor to a
// password manager.
@RunWith(io.github.kauri_off.kiroku.screenshots.ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchFieldAutofillTest {
    @Test
    fun theSearchFieldIsNotOfferedToAutofillInEitherWindow() {
        for (state in listOf(SearchFixtureStates.results, SearchFixtureStates.typing)) {
            val variant = Variant.EnLight
            Shot
                .launch(variant, variant.heightDp, keyboard = state.expanded, widthDp = variant.widthDp) {
                    SearchScreen(state, {}, SnackbarHostState())
                }.use { shot ->
                    val fields = shot.textFields()
                    assertTrue("${state.expanded}: $fields", fields.isNotEmpty())
                    assertEquals(emptyList<String>(), fields.filter { it.second }.map { it.first })
                }
        }
    }
}
