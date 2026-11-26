package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.edit.MangaEditStatus
import io.github.kauri_off.kiroku.list.ListPane
import io.github.kauri_off.kiroku.list.ListScreen
import io.github.kauri_off.kiroku.list.ListState
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState

class MangaListScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen(mangaListFixture(listEntries + readingEntries).copy(canEdit = true)) }
    override val loading = shown { Screen(MangaListContent.Loading) }
    override val empty = shown { Screen(mangaListFixture(emptyList())) }
    override val error = shown { Screen(MangaListContent.Failed(DataError.NoConnection)) }

    override val cases =
        listOf(
            case("reading") { Screen(mangaListFixture()) },
            case("planned") { Screen(mangaListFixture(), tab = MangaEditStatus.Planned.readingStatus) },
            case("empty-tab") { Screen(mangaListFixture(), tab = MangaEditStatus.Dropped.readingStatus) },
            case("signed-out") { Screen(MangaListContent.SignedOut) },
            case("wide", window = WindowSize(840, 600), largeText = true) { Screen(mangaListFixture()) },
            case("expired") { Screen(mangaListFixture(), expired = true) },
            case("compact", window = WindowSize(360, 760), largeText = true) { Screen(mangaListFixture()) },
            case("filtering", window = WindowSize(360, 760), keyboard = true, largeText = true) {
                Screen(mangaListFixture(), filter = "the")
            },
            case("filtering-wide", window = WindowSize(840, 600), keyboard = true, largeText = true) {
                Screen(mangaListFixture(), filter = "the")
            },
            case("filter-no-matches", window = WindowSize(840, 600), keyboard = true) {
                Screen(mangaListFixture(), filter = "xyz")
            },
            case("sort-sheet", window = WindowSize(360, 760), largeText = true) { Screen(mangaListFixture(), sortSheetOpen = true) },
            case("sort-sheet-wide", window = WindowSize(840, 600), largeText = true) {
                Screen(mangaListFixture(), sortSheetOpen = true)
            },
            case("punctuation-status") {
                val status = MangaReadingStatus("__")
                Screen(mangaListFixture(listOf(mangaEntry.copy(status = status))), tab = status)
            },
        )

    override val transitions =
        listOf(
            Transition("planned", start = "reading", millis = MotionBounds.ENTER, lead = 16L) { changed ->
                Screen(mangaListFixture(), tab = (if (changed) MangaEditStatus.Planned else MangaEditStatus.Reading).readingStatus)
            },
            Transition("sort-sheet", start = "compact", millis = MotionBounds.SCREEN) { changed ->
                Screen(mangaListFixture(), sortSheetOpen = changed)
            },
        )
}

internal val mangaEntry =
    MangaListEntry(
        mangaFixture.id,
        mangaFixture.titles,
        MangaReadingStatus("reading"),
        posterUrl = mangaFixture.posterUrl,
        chaptersRead = 12,
        volumesRead = 2,
    )

private val listEntries =
    listOf(
        mangaEntry,
        mangaEntry.copy(id = MangaId(1), titles = Titles("The Lantern District"), status = MangaReadingStatus("completed"), score = 9),
        mangaEntry.copy(
            id = MangaId(2),
            titles = Titles("Rain at the Edge of Town"),
            status = MangaReadingStatus("plan_to_read"),
            posterUrl = null,
            chaptersRead = 0,
            volumesRead = null,
        ),
        mangaEntry.copy(
            id = MangaId(3),
            titles = Titles("Between the Tides"),
            status = MangaReadingStatus("on_hold"),
            chaptersRead = 7,
            volumesRead = null,
        ),
    )

private val readingEntries =
    (10L..23).map { id -> mangaEntry.copy(id = MangaId(id), titles = Titles("Reading fixture $id"), posterUrl = null) }

internal fun mangaListFixture(entries: List<MangaListEntry> = listEntries) = MangaListContent.Ready(entries, TitleLanguage.Romaji)

@Composable
private fun Screen(
    content: MangaListContent,
    expired: Boolean = false,
    tab: MangaReadingStatus = MangaEditStatus.Reading.readingStatus,
    filter: String? = null,
    sortSheetOpen: Boolean = false,
) {
    val matches = remember(content, filter) { mangaTitleFilter().rows(content as? MangaListContent.Ready, filter, 0) }
    ShellScreen(ShellState(Destination.List), {}) {
        ListScreen(
            ListPane.Manga(
                MangaListState(
                    content = content,
                    sessionExpired = expired,
                    tab = tab,
                    filter = ListState.Typed(filter),
                    sortTab = tab.takeIf { sortSheetOpen },
                    matches = matches,
                ),
                {},
            ),
            remember { SnackbarHostState() },
            onMode = {},
        )
    }
}
