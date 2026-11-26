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
import io.github.kauri_off.kiroku.list.ListPane
import io.github.kauri_off.kiroku.list.ListScreen
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState

class MangaListScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen(mangaListFixture().copy(canEdit = true)) }
    override val loading = shown { Screen(MangaListContent.Loading) }
    override val empty = shown { Screen(mangaListFixture(emptyList())) }
    override val error = shown { Screen(MangaListContent.Failed(DataError.NoConnection)) }

    override val cases =
        listOf(
            case("signed-out") { Screen(MangaListContent.SignedOut) },
            case("wide", window = WindowSize(840, 600), largeText = true) { Screen(mangaListFixture()) },
            case("expired") { Screen(mangaListFixture(), expired = true) },
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

internal fun mangaListFixture(entries: List<MangaListEntry> = listEntries) = MangaListContent.Ready(entries, TitleLanguage.Romaji)

@Composable
private fun Screen(
    content: MangaListContent,
    expired: Boolean = false,
) {
    ShellScreen(ShellState(Destination.List), {}) {
        ListScreen(
            ListPane.Manga(MangaListState(content = content, sessionExpired = expired), {}),
            remember { SnackbarHostState() },
            onMode = {},
        )
    }
}
