package io.github.kauri_off.kiroku.search

import android.content.res.Configuration
import androidx.compose.animation.core.snap
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.screenshots.Shot
import io.github.kauri_off.kiroku.screenshots.SystemBars
import io.github.kauri_off.kiroku.screenshots.Variant
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState
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

    @Test
    fun `browse loads an empty page's continuation only while on top and follows the changing offset`() {
        val events = mutableListOf<BrowseEvent>()
        val onTop = mutableStateOf(false)
        val offset = mutableStateOf(10)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                BrowseScreen(
                    BrowseState(BrowseList.ThisSeason, Paged.Shown(emptyList(), More.Ready(offset.value))),
                    { events += it },
                    SnackbarHostState(),
                    autoLoadMore = onTop.value,
                )
            }
        }
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            onTop.value = true
        }
        compose.runOnIdle {
            assertEquals(listOf(BrowseEvent.LoadMore), events)
            offset.value = 20
        }
        compose.runOnIdle { assertEquals(listOf(BrowseEvent.LoadMore, BrowseEvent.LoadMore), events) }
    }

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
        show(results(digits, Paged.Shown(emptyList())))
        compose.onNodeWithText("Nothing found for “$digits”").assertExists()
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

    @Test
    fun `the search bar's edges are the rows' content edges`() {
        show(SearchFixtureStates.results)
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val row = compose.onNode(hasText(FixtureAnime.exactToWatch.titles.romaji) and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals(row.left + Spacing.l, field.left)
        assertEquals(row.right - Spacing.l, field.right)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `at most five recent searches are shown`() {
        SearchContent.Start(List(MAX_RECENT_SEARCHES + 1) { it.toString() })
    }

    @Test
    fun `a further page is asked for when the end of the results comes into view, and once`() {
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query).take(1)
        show(results(SearchFixtureStates.query, Paged.Shown(rows, More.Ready(20))))
        compose.waitForIdle()
        assertEquals(listOf(SearchEvent.LoadMore), events)
    }

    @Test
    fun `only the dialog requests the next page while search is expanded`() {
        val query = SearchFixtureStates.query
        val rows = SearchFixtureStates.rows(query).take(1)
        val state = results(query, Paged.Shown(rows))
        show(state)
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertIsFocused()
        show(results(query, Paged.Shown(rows, More.Ready(20))))
        assertEquals(listOf(SearchEvent.LoadMore), events)
    }

    @Test
    fun `an advanced offset asks again when a page adds no new rows`() {
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query)
        show(results(SearchFixtureStates.query, Paged.Shown(rows.take(1), More.Ready(20))))
        show(results(SearchFixtureStates.query, Paged.Shown(rows.take(1), More.Ready(40))))
        assertEquals(listOf(SearchEvent.LoadMore, SearchEvent.LoadMore), events)
    }

    @Test
    fun `a new query that ends on the same row asks for its own next page`() {
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query)
        show(results(SearchFixtureStates.query, Paged.Shown(rows.take(1), More.Ready(20))))
        // A shorter query whose first page is the same title.
        show(results(SearchFixtureStates.query.take(5), Paged.Shown(rows.take(1), More.Ready(20))))
        assertEquals(listOf(SearchEvent.LoadMore, SearchEvent.LoadMore), events)
    }

    @Test
    fun `the same page shown again asks for nothing more`() {
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query).take(1)
        show(results(SearchFixtureStates.query, Paged.Shown(rows, More.Ready(20))))
        show(results(SearchFixtureStates.query, Paged.Shown(rows.toList(), More.Ready(20))))
        assertEquals(listOf(SearchEvent.LoadMore), events)
    }

    @Test
    fun `a search view closed by leaving for a browse list fades its content in again when it next opens`() =
        leavingClosesTheView(SearchFixtureStates.start, "Top rated", SearchEvent.Browse(BrowseList.TopRated))

    @Test
    fun `a search view closed by opening a title fades its content in again when it next opens`() {
        val state = SearchFixtureStates.digitsQuery
        val id = checkNotNull(queriedId(state.query.text.toString()))
        leavingClosesTheView(state, "Open title #${id.value}", SearchEvent.Open(id))
    }

    // Opens the view by its field, taps [row] in it, then takes the screen out of composition and back as the
    // navigation does: the view is closed and its content hidden, so it fades in again.
    private fun leavingClosesTheView(
        state: SearchState,
        row: String,
        leaves: SearchEvent,
    ) {
        val composed = mutableStateOf(true)
        lateinit var bar: SearchBarState
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val holder = rememberSaveableStateHolder()
                if (composed.value) {
                    holder.SaveableStateProvider(SEARCH) {
                        SearchScreen(state, { events += it }, SnackbarHostState(), rememberBar = {
                            rememberContainedSearchBarState().also { bar = it }
                        })
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1f, 1f), saved(bar))
        compose.onNode(hasText(row) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(listOf(leaves), events)
        composed.value = false
        compose.waitForIdle()
        composed.value = true
        compose.waitForIdle()
        assertEquals(listOf(0f, 0f), saved(bar))
    }

    @Test
    fun `signing in from the banner of the open search view closes the view`() {
        lateinit var bar: SearchBarState
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SearchScreen(SearchFixtureStates.sessionExpired, { events += it }, SnackbarHostState(), rememberBar = {
                    rememberContainedSearchBarState().also { bar = it }
                })
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1f, 1f), saved(bar))
        compose.onNode(hasText("Sign in") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(listOf<SearchEvent>(SearchEvent.SignIn), events)
        assertEquals(listOf(0f, 0f), saved(bar))
    }

    // The bar's progress and its content's, as the state saves them.
    private fun saved(bar: SearchBarState): Any? = with(SearchBarState.Saver(snap(), snap())) { SaverScope { true }.save(bar) }

    @Test
    fun `a long press on a result, or its spoken action, asks to edit it, on the list or not`() {
        show(SearchFixtureStates.results)
        val rows = SearchFixtureStates.rows(SearchFixtureStates.query)
        val listed = rows.first { it.entry != null }
        val notListed = rows.first { it.entry == null }
        compose.onNode(hasText(listed.title) and hasClickAction()).performTouchInput { longClick() }
        val row = compose.onNode(hasText(notListed.title) and hasClickAction()).fetchSemanticsNode()
        val edit = row.config[SemanticsActions.OnLongClick]
        assertEquals("Edit", edit.label)
        compose.runOnIdle { edit.action!!() }
        compose.waitForIdle()
        assertEquals(listOf(SearchEvent.Edit(listed.id), SearchEvent.Edit(notListed.id)), events)
    }

    @Test
    fun `with no list to edit a result offers no edit`() {
        show(SearchFixtureStates.results.copy(editable = false))
        val title = FixtureAnime.exactToWatch.titles.romaji
        compose.onNode(hasText(title) and hasClickAction()).performTouchInput { longClick() }
        compose
            .onNode(hasText(title) and hasClickAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        assertTrue(events.toString(), events.none { it is SearchEvent.Edit })
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

// On a wide window the bar and the rows share the centred content width.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w840dp-h600dp")
class SearchScreenWideTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val inView = hasAnyAncestor(isDialog())
    private val field = hasSetTextAction()
    private val result = hasText(FixtureAnime.exactToWatch.titles.romaji) and hasClickAction()

    private fun openSearch() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                ShellScreen(ShellState(Destination.Search), {}) {
                    SearchScreen(SearchFixtureStates.results, {}, SnackbarHostState())
                }
            }
        }
        val collapsed = compose.onNode(field and !inView)
        val profile = compose.onNodeWithText("Profile").getUnclippedBoundsInRoot()
        assertTrue(profile.right < collapsed.getUnclippedBoundsInRoot().left)
        collapsed.performClick()
    }

    @Test
    fun `the expanded field shares the centred width of the results`() {
        openSearch()
        val expanded = compose.onNode(field and inView).assertIsFocused().getUnclippedBoundsInRoot()
        val row = compose.onNode(result and inView).getUnclippedBoundsInRoot()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        assertEquals("expanded field and row bounds: $expanded and $row", row.left + Spacing.l, expanded.left)
        assertEquals(row.right - Spacing.l, expanded.right)
        assertEquals(Sizes.contentMaxWidth, row.right - row.left)
        assertEquals(840.dp, row.left + row.right)
    }

    @Test
    @Config(qualifiers = "en-w600dp-h960dp")
    fun `a narrower expanded field uses the stock width and Back keeps the query`() {
        openSearch()
        val query = hasText(SearchFixtureStates.query)
        val expanded = compose.onNode(field and inView and query).assertIsFocused().getUnclippedBoundsInRoot()
        val row = compose.onNode(result and inView).getUnclippedBoundsInRoot()
        assertEquals(600.dp, row.right - row.left)
        assertEquals(row.left + Spacing.s, expanded.left)
        assertEquals(row.right - Spacing.s, expanded.right)
        compose.onNode(hasContentDescription("Back") and inView).performClick()
        compose.onNode(field and inView).assertDoesNotExist()
        compose.onNode(field and !inView and query).assertExists()
    }

    @Test
    fun `the search bar's edges are the rows' content edges`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { SearchScreen(SearchFixtureStates.results, {}, SnackbarHostState()) }
        }
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val row = compose.onNode(hasText(FixtureAnime.exactToWatch.titles.romaji) and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals(row.left + Spacing.l, field.left)
        assertEquals(row.right - Spacing.l, field.right)
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

// The search view's rows lie on the view's own container, so the view reads as one surface from the bar down.
@RunWith(io.github.kauri_off.kiroku.screenshots.ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchViewRowsTest {
    @Test
    fun theRowsOfTheSearchViewHaveTheViewsColour() {
        for (state in listOf(SearchFixtureStates.searchView, SearchFixtureStates.typing)) assertOneColour(state)
    }

    // The page under the bar keeps its rows on the page's own colour.
    @Test
    fun thePageRowsHaveThePagesColour() {
        for (state in listOf(SearchFixtureStates.start, SearchFixtureStates.results)) assertOneColour(state)
    }

    // A line down the screen inside the rows' start padding, from under the field: rows, headers and the space
    // after them are one colour.
    private fun assertOneColour(state: SearchState) {
        for (variant in listOf(Variant.EnLight, Variant.EnDark)) {
            Shot.launch(variant) { SearchScreen(state, {}, SnackbarHostState()) }.use { shot ->
                val image = shot.image()
                val x = shot.dpToPx(EDGE_DP).toInt()
                val top = shot.dpToPx(BELOW_FIELD_DP).toInt()
                val colours = (top until image.height * 3 / 4).map { image.getPixel(x, it) }.toSet()
                assertEquals("${variant.id} ${state.content}: ${colours.map { Integer.toHexString(it) }}", 1, colours.size)
            }
        }
    }

    private companion object {
        const val BELOW_FIELD_DP = 100
        const val EDGE_DP = 8
    }
}

// With the keyboard up in the search view, the end of a long result list scrolls to just above the keyboard.
@RunWith(io.github.kauri_off.kiroku.screenshots.ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchViewKeyboardTest {
    @Test
    fun theLastResultEndsAtTheKeyboard() {
        val query = SearchFixtureStates.query
        val state =
            SearchFixtureStates.state(
                query,
                SearchContent.Results(query, Paged.Shown(SearchFixtureStates.rows(query))),
                expanded = true,
            )
        val variant = Variant.EnLight
        Shot.launch(variant, keyboard = true) { SearchScreen(state, {}, SnackbarHostState()) }.use { shot ->
            assertTrue(shot.scrollRangeDp() > 0)
            val keyboardTop = shot.windowHeightPx - shot.dpToPx(SystemBars.KEYBOARD_DP)
            val gap = keyboardTop - shot.scrolledContentBottomPx()
            assertTrue("the last row ends ${gap / shot.dpToPx(1)} dp above the keyboard", gap >= 0f && gap <= shot.dpToPx(MAX_GAP_DP))
        }
    }

    private companion object {
        const val MAX_GAP_DP = 16
    }
}

private const val SEARCH = "search"
