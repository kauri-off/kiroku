package io.github.kauri_off.kiroku.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.manga.mangaFixture
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class MangaRankingScreenshots : ScreenshotRegistry() {
    private val rows =
        listOf(
            mangaFixture,
            mangaFixture.copy(id = MangaId(2), titles = Titles("Harbour of Lanterns"), format = MangaFormat.Known.Manhwa),
            mangaFixture.copy(id = MangaId(3), titles = Titles("The Letter at the End of the Rail Line"), meanScore = null),
        ).mapIndexed { index, manga -> MangaResultRow(manga, manga.titles.romaji, rank = index + 1) }

    override val content = contentCase { Screen(Paged.Shown(rows)) }
    override val loading = shown { Screen(Paged.Loading) }
    override val empty = shown { Screen(Paged.Shown(emptyList())) }
    override val error = shown { Screen(Paged.Failed(DataError.NoConnection)) }

    override val cases =
        listOf(
            case("loading-more") { Screen(Paged.Shown(rows, More.Loading)) },
            case("more-failed") { Screen(Paged.Shown(rows, More.Failed(DataError.NoConnection))) },
        )
}

@Composable
private fun Screen(rows: Paged<MangaResultRow>) {
    MangaRankingScreen(MangaRankingState(rows), {}, remember { SnackbarHostState() })
}
