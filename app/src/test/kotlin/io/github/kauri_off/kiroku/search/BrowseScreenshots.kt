package io.github.kauri_off.kiroku.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class BrowseScreenshots : ScreenshotRegistry() {
    private fun screen(state: BrowseState) = @Composable { BrowseScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() }) }

    override val content = contentCase("top-airing", content = screen(SearchFixtureStates.browse(BrowseList.TopAiring)))
    override val loading = shown(screen(SearchFixtureStates.browseLoading))
    override val empty = shown(screen(SearchFixtureStates.browseEmpty))
    override val error = shown(screen(SearchFixtureStates.browseError))

    override val cases =
        listOf(
            case("this-season", content = screen(SearchFixtureStates.browse(BrowseList.ThisSeason))),
            case("top-rated", content = screen(SearchFixtureStates.browse(BrowseList.TopRated))),
            case("loading-more", content = screen(SearchFixtureStates.browse(BrowseList.MostPopular, More.Loading))),
            case("session-expired", content = screen(SearchFixtureStates.browse(BrowseList.TopAiring).copy(sessionExpired = true))),
        )
}
