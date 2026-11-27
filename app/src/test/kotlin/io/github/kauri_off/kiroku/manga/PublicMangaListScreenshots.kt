package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.edit.MangaEditStatus
import io.github.kauri_off.kiroku.list.ListState
import io.github.kauri_off.kiroku.profile.ProfileFixtureStates
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class PublicMangaListScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen(MangaListState(content = mangaListFixture())) }
    override val loading = shown { Screen(MangaListState()) }
    override val empty = shown { Screen(MangaListState(content = mangaListFixture(emptyList()))) }
    override val error = shown { Screen(MangaListState(content = MangaListContent.Failed(DataError.NoConnection))) }

    override val cases =
        listOf(
            case("streaming") { Screen(MangaListState(content = mangaListFixture().copy(streaming = true))) },
            case("refreshing") { Screen(MangaListState(content = mangaListFixture().copy(refreshing = true))) },
            case("private") { Screen(MangaListState(content = MangaListContent.Failed(DataError.PrivateList))) },
            case("unknown") { Screen(MangaListState(content = MangaListContent.Failed(DataError.UnknownUser))) },
            case("read") {
                Screen(MangaListState(content = mangaListFixture(), tab = MangaEditStatus.Completed.readingStatus))
            },
            case("empty-tab") {
                Screen(MangaListState(content = mangaListFixture(), tab = MangaEditStatus.Dropped.readingStatus))
            },
            case("filter-no-matches", keyboard = true) {
                Screen(MangaListState(content = mangaListFixture(), filter = ListState.Typed("xyz"), matches = emptyList()))
            },
            case("sort-sheet", largeText = true) {
                Screen(MangaListState(content = mangaListFixture(), sortTab = MangaEditStatus.Reading.readingStatus))
            },
        )

    override val transitions =
        listOf(
            Transition("read", start = "content", millis = MotionBounds.ENTER, lead = 16L) { changed ->
                val tab = if (changed) MangaEditStatus.Completed else MangaEditStatus.Reading
                Screen(MangaListState(content = mangaListFixture(), tab = tab.readingStatus))
            },
            Transition("sort-sheet", start = "content", millis = MotionBounds.SCREEN) { changed ->
                val tab = MangaEditStatus.Reading.readingStatus
                Screen(MangaListState(content = mangaListFixture(), sortTab = tab.takeIf { changed }))
            },
        )
}

@Composable
private fun Screen(state: MangaListState) {
    PublicMangaListScreen(ProfileFixtureStates.otherName, state, remember { SnackbarHostState() }) {}
}
