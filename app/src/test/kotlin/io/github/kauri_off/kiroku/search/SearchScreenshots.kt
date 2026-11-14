package io.github.kauri_off.kiroku.search

import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class SearchScreenshots : ScreenshotRegistry() {
    private fun screen(state: SearchState) = @Composable { Screen(state) }

    override val content = contentCase("results", content = screen(SearchFixtureStates.results))
    override val loading = shown(screen(SearchFixtureStates.loading))
    override val empty = shown(screen(SearchFixtureStates.noResults))
    override val error = shown(screen(SearchFixtureStates.error))

    override val cases =
        listOf(
            case("start", content = screen(SearchFixtureStates.start)),
            case("start-no-recent", content = screen(SearchFixtureStates.startNoRecent)),
            case("search-view", content = screen(SearchFixtureStates.searchView)),
            case("typing", keyboard = true, content = screen(SearchFixtureStates.typing)),
            case("digits", content = screen(SearchFixtureStates.digitsQuery)),
            case("loading-more", content = screen(SearchFixtureStates.loadingMore)),
            case("more-failed", content = screen(SearchFixtureStates.moreFailed)),
            case("session-expired", content = screen(SearchFixtureStates.sessionExpired)),
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
        )

    // The fast spatial spring has moved the field most of the way within a tenth of a second.
    private val early get() = listOf(EARLY_MILLIS, 2 * EARLY_MILLIS, 4 * EARLY_MILLIS, MotionBounds.SCREEN)
}

@Composable
private fun Screen(state: SearchState) = SearchScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })

private const val EARLY_MILLIS = 50L
