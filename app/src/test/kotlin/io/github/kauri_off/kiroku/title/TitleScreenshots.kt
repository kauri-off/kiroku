package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
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
import java.time.Duration

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
            case("planned-scored") { Screen(TitleFixtures.plannedScored) },
            case("first-episode-aired") { Screen(TitleFixtures.firstEpisodeAired) },
            case("added-snackbar") { Screen(TitleFixtures.added) },
            case("completed-snackbar") { Screen(TitleFixtures.completed) },
        )
}

@Composable
private fun Screen(state: TitleState) = TitleInShell(state, onEvent = {})

// The page as the shell shows it: the shell draws the one snackbar host, above the gesture bar.
@Composable
fun TitleInShell(
    state: TitleState,
    onEvent: (TitleEvent) -> Unit,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    Box(Modifier.fillMaxSize()) {
        TitleScreen(state, onEvent, snackbars)
        SnackbarHost(
            snackbars,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        )
    }
}

object TitleFixtures {
    private val set = Fixtures.reference

    private val statuses: Map<AnimeId, ListStatus> =
        set.entries.associate { it.anime.id to it.status }

    fun of(
        anime: Anime,
        entry: ListEntry? = set.entryFor(anime.id),
        airing: Airing? = set.airing[anime.id],
    ): TitleState.Shown =
        titleState(
            anime = anime,
            entry = entry,
            airing = airing,
            details = set.details[anime.id],
            listStatuses = statuses,
            language = TitleLanguage.Romaji,
            clock = set.clock,
        )

    // The longest status name beside the longest score text.
    val plannedScored: TitleState.Shown
        get() {
            val anime = FixtureAnime.exactToWatch
            val entry = checkNotNull(set.entryFor(anime.id))
            return of(anime, entry.copy(values = entry.values.copy(status = ListStatus.Planned, score = 10)))
        }

    // Episode 1 aired an hour ago and MAL still says "not yet aired": the stored count decides.
    val firstEpisodeAired: TitleState.Shown
        get() {
            val anime = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.NotYetAired)
            val entry = ListEntry(anime, ListValues(ListStatus.Watching, 0, null, null, null), set.now)
            val airing =
                Airing(
                    aired = AiredCount.Exact(1),
                    total = anime.malEpisodes,
                    nextEpisode = ScheduledEpisode(2, set.now.plus(Duration.ofDays(7)).minus(Duration.ofHours(1))),
                    latestAiredAt = set.now.minus(Duration.ofHours(1)),
                )
            return of(anime, entry, airing)
        }

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
