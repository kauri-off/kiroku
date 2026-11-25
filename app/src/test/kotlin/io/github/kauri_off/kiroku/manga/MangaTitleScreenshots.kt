package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class MangaTitleScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen(mangaFixture.shown()) }
    override val loading = shown { Screen(MangaTitleContent.Loading) }
    override val empty = notShown("A title that cannot be loaded is the error case.")
    override val error = shown { Screen(MangaTitleContent.Failed(MangaTitleProblem.ReadFailed(DataError.NoConnection))) }

    override val cases =
        listOf(
            case("unavailable") { Screen(MangaTitleContent.Failed(MangaTitleProblem.Unavailable)) },
            case("wide", window = WindowSize(840, 600)) { Screen(mangaFixture.shown()) },
            case("without-cover-or-totals") {
                Screen(
                    mangaFixture
                        .copy(
                            posterUrl = null,
                            chapters = null,
                            volumes = null,
                            meanScore = null,
                            synopsis = null,
                            publication = MangaPublication.Known.Publishing,
                        ).shown(),
                )
            },
        )
}

internal val mangaFixture =
    MangaDetails(
        id = MangaId(FixtureAnime.exactToWatch.id.value),
        titles = Titles("Paper Observatory"),
        posterUrl = FixtureAnime.exactToWatch.posterUrl,
        largePosterUrl = null,
        synopsis =
            "A young cartographer arrives in a town where the observatory is scheduled to close. " +
                "Its last caretaker leaves her a map with blank streets and a note about the night sky. " +
                "She asks the town's residents to help fill in what is missing. " +
                "Every street they recover brings back a different account of a fire that left the hill empty. " +
                "When a new star appears over the town, she has one night to decide what belongs on the map.",
        format = MangaFormat.Known.Manga,
        publication = MangaPublication.Known.Finished,
        volumes = 18,
        chapters = 162,
        meanScore = 9.16,
    )

private fun MangaDetails.shown() = MangaTitleContent.Shown(this, titles.romaji)

@Composable
private fun Screen(content: MangaTitleContent) {
    MangaTitleScreen(MangaTitleState(content), {}, remember { SnackbarHostState() })
}
