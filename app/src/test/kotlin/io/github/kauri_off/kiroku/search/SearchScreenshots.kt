package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.manga.mangaFixture
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState

class SearchScreenshots : ScreenshotRegistry() {
    private fun screen(state: SearchState) = @Composable { Screen(state) }

    private val landscape = WindowSize(840, 600)
    private val portrait = WindowSize(600, 960)

    override val content = contentCase("results", content = screen(SearchFixtureStates.results))
    override val loading = shown(screen(SearchFixtureStates.loading))
    override val empty = shown(screen(SearchFixtureStates.noResults))
    override val error = shown(screen(SearchFixtureStates.error))

    private fun mangaResults(
        results: Paged<MangaResultRow>,
        expanded: Boolean = false,
    ) = SearchState(TextFieldState("Paper"), expanded, SearchContent.MangaResults("Paper", results))

    private val mangaRows = listOf(MangaResultRow(mangaFixture, mangaFixture.titles.romaji))

    override val cases =
        listOf(
            case("start", content = screen(SearchFixtureStates.start)),
            case("start-signed-in", largeText = true, content = screen(SearchFixtureStates.startSignedIn)),
            case("with-manga", content = screen(SearchFixtureStates.startNoRecent.copy(content = SearchContent.Start(emptyList(), mangaAvailable = true)))),
            case("manga-start", content = screen(SearchState(TextFieldState(), false, SearchContent.MangaStart(emptyList())))),
            case("manga-results", content = screen(mangaResults(Paged.Shown(mangaRows)))),
            case("manga-expanded", content = screen(mangaResults(Paged.Shown(mangaRows), expanded = true))),
            case("manga-typing", keyboard = true, content = screen(mangaResults(Paged.Shown(mangaRows), expanded = true))),
            case("manga-loading", content = screen(mangaResults(Paged.Loading))),
            case("manga-empty", content = screen(mangaResults(Paged.Shown(emptyList())))),
            case("manga-error", content = screen(mangaResults(Paged.Failed(DataError.NoConnection)))),
            case("manga-more-failed", content = screen(mangaResults(Paged.Shown(mangaRows, More.Failed(DataError.NoConnection))))),
            case("start-no-recent", content = screen(SearchFixtureStates.startNoRecent)),
            case("search-view", content = screen(SearchFixtureStates.searchView)),
            case("typing", keyboard = true, content = screen(SearchFixtureStates.typing)),
            case("results-landscape", window = landscape) {
                WideResults(SearchFixtureStates.results)
            },
            case("search-view-landscape", window = landscape) {
                WideResults(SearchFixtureStates.results.copy(expanded = true))
            },
            case("typing-portrait", window = portrait, keyboard = true) {
                WideResults(SearchFixtureStates.results.copy(expanded = true))
            },
            case("digits", content = screen(SearchFixtureStates.digitsQuery)),
            case("loading-more", content = screen(SearchFixtureStates.loadingMore)),
            case("more-failed", content = screen(SearchFixtureStates.moreFailed)),
            case("session-expired", content = screen(SearchFixtureStates.sessionExpired)),
            // The open view shows the session banner and the shell's messages itself.
            case("search-view-session-expired", content = screen(SearchFixtureStates.searchView.copy(sessionExpired = true))),
            case("typing-snackbar", keyboard = true) { Screen(SearchFixtureStates.typing, snackbar = true) },
        )

    // A tap on the bar opens the search view and Back closes it, the stock component's own motion.
    override val transitions =
        listOf(
            Transition("search-view", start = "start", millis = MotionBounds.SCREEN, at = early) { changed ->
                val bar = rememberContainedSearchBarState(SearchBarValue.Collapsed)
                SearchScreen(SearchFixtureStates.start, onEvent = {}, snackbars = remember { SnackbarHostState() }, rememberBar = { bar })
                LaunchedEffect(changed) { if (changed) bar.animateToExpanded() }
            },
            Transition("start", start = "search-view", millis = MotionBounds.SCREEN, at = early) { changed ->
                val bar = rememberContainedSearchBarState(SearchBarValue.Expanded)
                SearchScreen(SearchFixtureStates.start, onEvent = {}, snackbars = remember { SnackbarHostState() }, rememberBar = { bar })
                LaunchedEffect(changed) { if (changed) bar.animateToCollapsed() }
            },
            Transition("manga-expanded", start = "manga-results", millis = MotionBounds.SCREEN, at = early) { changed ->
                val bar = rememberContainedSearchBarState(SearchBarValue.Collapsed)
                SearchScreen(mangaResults(Paged.Shown(mangaRows)), {}, remember { SnackbarHostState() }, rememberBar = { bar })
                LaunchedEffect(changed) { if (changed) bar.animateToExpanded() }
            },
            Transition("search-view-landscape", start = "results-landscape", millis = MotionBounds.SCREEN, at = early) { changed ->
                val bar = rememberContainedSearchBarState(SearchBarValue.Collapsed)
                ShellScreen(ShellState(Destination.Search), {}) {
                    SearchScreen(SearchFixtureStates.results, {}, remember { SnackbarHostState() }, rememberBar = { bar })
                }
                LaunchedEffect(changed) { if (changed) bar.animateToExpanded() }
            },
        )

    // The fast spatial spring has moved the field most of the way within a tenth of a second.
    private val early get() = listOf(EARLY_MILLIS, 2 * EARLY_MILLIS, 4 * EARLY_MILLIS, MotionBounds.SCREEN)
}

@Composable
private fun WideResults(state: SearchState) {
    ShellScreen(ShellState(Destination.Search), {}) { Screen(state) }
}

@Composable
private fun Screen(
    state: SearchState,
    snackbar: Boolean = false,
) {
    val snackbars = remember { SnackbarHostState() }
    if (snackbar) {
        val message = stringResource(R.string.shell_could_not_save)
        val retry = stringResource(R.string.design_retry)
        LaunchedEffect(Unit) { snackbars.showSnackbar(message, actionLabel = retry) }
    }
    SearchScreen(state, onEvent = {}, snackbars = snackbars)
}

private const val EARLY_MILLIS = 50L
