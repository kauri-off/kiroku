package io.github.kauri_off.kiroku.title

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class TitleScreenshots : ScreenshotRegistry() {
    override val content = contentCase("airing-on-list") { Screen(TitleFixtures.of(FixtureAnime.exactToWatch)) }
    override val loading = shown { Screen(TitleState.Loading) }
    override val empty = notShown("A title page always shows its title; a title that cannot be loaded is the error case.")
    override val error = shown { Screen(TitleState.Failed(DataError.NoConnection)) }

    override val cases =
        listOf(
            case("not-on-list") { Screen(TitleFixtures.of(FixtureAnime.notOnListAiring)) },
            case("airing-estimated") { Screen(TitleFixtures.of(FixtureAnime.estimatedToWatch)) },
            case("airing-estimated-caught-up") { Screen(TitleFixtures.of(FixtureAnime.estimatedCaughtUp)) },
            case("airing-unknown") { Screen(TitleFixtures.of(FixtureAnime.airedUnknown)) },
            case("finished") { Screen(TitleFixtures.of(FixtureAnime.finishedPartWay)) },
            case("film") { Screen(TitleFixtures.of(FixtureAnime.film)) },
            case("not-yet-aired") { Screen(TitleFixtures.of(FixtureAnime.premiereTimeSoon)) },
            case("not-yet-aired-date") { Screen(TitleFixtures.of(FixtureAnime.premiereDate)) },
            case("not-yet-aired-month") { Screen(TitleFixtures.of(FixtureAnime.premiereMonth)) },
            case("not-yet-aired-year") { Screen(TitleFixtures.of(FixtureAnime.premiereYear)) },
            case("not-yet-aired-nodate") { Screen(TitleFixtures.of(FixtureAnime.premiereUnannounced)) },
            case("no-related") { Screen(TitleFixtures.of(FixtureAnime.exactCaughtUp)) },
            case("long-title") { Screen(TitleFixtures.of(FixtureAnime.longTitle)) },
            case("details-loading") { Screen(TitleFixtures.of(FixtureAnime.exactToWatch).copy(details = null)) },
            case("no-score") { Screen(TitleFixtures.of(FixtureAnime.premiereTimeLater)) },
            case("added-snackbar") { Screen(TitleFixtures.added) },
            case("completed-snackbar") { Screen(TitleFixtures.completed) },
        )
}

@Composable
private fun Screen(state: TitleState) = TitleScreen(state, onEvent = {})

object TitleFixtures {
    private val set = Fixtures.reference

    private val statuses: Map<AnimeId, ListStatus> =
        set.entries.associate { it.anime.id to it.status }

    fun of(anime: Anime): TitleState.Shown =
        titleState(
            anime = anime,
            entry = set.entryFor(anime.id),
            airing = set.airing[anime.id],
            details = set.details[anime.id],
            listStatuses = statuses,
            language = TitleLanguage.Romaji,
            clock = set.clock,
        )

    // "Add to list" was tapped: the title is Planned and the snackbar offers "Change".
    val added: TitleState.Shown
        get() {
            val anime = FixtureAnime.notOnListAiring
            val entry =
                ListEntry(
                    anime = anime,
                    values =
                        ListValues(
                            status = ListStatus.Planned,
                            watchedEpisodes = 0,
                            score = null,
                            startDate = null,
                            finishDate = null,
                        ),
                    updatedAt = set.now,
                )
            return titleState(anime, entry, set.airing[anime.id], set.details[anime.id], statuses, TitleLanguage.Romaji, set.clock)
                .copy(snackbar = TitleSnackbar.AddedToPlanned)
        }

    // The stepper reached the total: the title became Completed in the same write (W7).
    val completed: TitleState.Shown
        get() {
            val anime = FixtureAnime.oneShortOfTotal
            val entry = checkNotNull(set.entryFor(anime.id))
            val today = set.now.atZone(set.clock.zone).toLocalDate()
            val stepped = entry.copy(values = entry.values.withWatched(entry.values.watchedEpisodes + 1, anime.malEpisodes, today))
            return titleState(anime, stepped, set.airing[anime.id], set.details[anime.id], statuses, TitleLanguage.Romaji, set.clock)
                .copy(snackbar = TitleSnackbar.MovedToCompleted)
        }
}
