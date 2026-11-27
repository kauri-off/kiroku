package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.readingStatus
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.edit.MangaEditTotals
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.MangaChange
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.rememberHostShowing

class MangaTitleScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen(mangaFixture.shown()) }
    override val loading = shown { Screen(MangaTitleContent.Loading) }
    override val empty = notShown("A title that cannot be loaded is the error case.")
    override val error = shown { Screen(MangaTitleContent.Failed(MangaTitleProblem.ReadFailed(DataError.NoConnection))) }

    override val cases =
        listOf(
            case("add-to-list", largeText = true) { Screen(mangaFixture.shown(), addAction = MangaAddAction.Available) },
            case("add-unconfirmed", largeText = true) { Screen(mangaFixture.shown(), reconcilingAdd = true) },
            case("adding") { Screen(mangaFixture.shown(), addAction = MangaAddAction.Saving) },
            case("added-to-planned", largeText = true) { Added() },
            case("membership-offline", largeText = true) { Screen(mangaFixture.shown(), membershipError = DataError.NoConnection) },
            case("on-personal-list", largeText = true) { Screen(mangaFixture.shown(), mangaEntry.copy(score = 8)) },
            case("partial-stats") { Screen(mangaFixture.copy(meanScore = null, rank = null).shown()) },
            case("paired-owned", largeText = true) { Screen(pairedManga.shown(), mangaEntry.copy(score = 8)) },
            case("paired-owned-wide", window = WindowSize(840, 600)) { Screen(pairedManga.shown(), mangaEntry.copy(score = 8)) },
            case("opened-with-summary") { Screen(openingSummary) },
            case("opened-with-summary-offline") { Screen(openingSummary.copy(detailsError = DataError.NoConnection)) },
            case("unavailable") { Screen(MangaTitleContent.Failed(MangaTitleProblem.Unavailable)) },
            case("cover-open", largeText = true) { Screen(mangaFixture.shown(), coverOpen = true) },
            case("cover-open-landscape", window = WindowSize(760, 360)) {
                Screen(mangaFixture.shown(), coverOpen = true)
            },
            case("from-catalogue") { Screen(mangaFixture.copy(synopsis = null).shown()) },
            case("wide", window = WindowSize(840, 600)) { Screen(mangaFixture.shown()) },
            case("without-cover-or-totals") {
                Screen(
                    mangaFixture
                        .copy(
                            posterUrl = null,
                            chapters = null,
                            volumes = null,
                            meanScore = null,
                            rank = null,
                            members = null,
                            synopsis = null,
                            publication = MangaPublication.Known.Publishing,
                        ).shown(),
                )
            },
        )

    override val transitions =
        listOf(
            Transition("added-to-planned", start = "adding", millis = 2 * MotionBounds.ENTER) { added ->
                if (added) Added() else Screen(mangaFixture.shown(), addAction = MangaAddAction.Saving)
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
        rank = 18,
        members = 299_750,
    )

private val openingSummary =
    mangaFixture
        .copy(
            synopsis = null,
            format = null,
            publication = null,
            volumes = null,
            chapters = null,
            meanScore = null,
            rank = null,
            members = null,
        ).let { MangaTitleContent.Shown(it, it.titles.romaji) }

private val pairedManga =
    FixtureAnime.exactToWatch.let { anime ->
        mangaFixture.copy(
            titles = anime.titles,
            largePosterUrl = anime.largePosterUrl,
            chapters = anime.malEpisodes,
            volumes = 6,
            meanScore = anime.meanScore,
            rank = anime.rank,
            members =
                Fixtures.reference.details[anime.id]
                    ?.statistics
                    ?.members,
            synopsis = Fixtures.reference.details[anime.id]?.synopsis,
        )
    }

private fun MangaDetails.shown() = MangaTitleContent.Shown(this, titles.romaji, detailsRead = true)

@Composable
private fun Added() {
    val entry = mangaEntry.copy(status = MangaListStatus.PlanToRead.readingStatus, chaptersRead = 0, volumesRead = 0)
    val change = MangaChange(entry.id, SignInId("fixture"), entry.titles.romaji, MangaEditTotals())
    Screen(mangaFixture.shown(), entry, message = ShellMessage(ShellMessage.Text.AddedToPlanned, change))
}

@Composable
private fun Screen(
    content: MangaTitleContent,
    entry: MangaListEntry? = null,
    membershipError: DataError? = null,
    addAction: MangaAddAction? = null,
    message: ShellMessage? = null,
    reconcilingAdd: Boolean = false,
    coverOpen: Boolean = false,
) {
    MangaTitleScreen(
        MangaTitleState(
            content,
            listEntry = entry,
            membershipError = membershipError,
            addAction = addAction,
            reconcilingAdd = reconcilingAdd,
            coverOpen = coverOpen,
        ),
        {},
        rememberHostShowing(message),
    )
}
