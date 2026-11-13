package io.github.kauri_off.kiroku.title

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.rememberHostShowing
import java.time.Duration

class TitleScreenshots : ScreenshotRegistry() {
    override val content = contentCase("airing-on-list") { Screen(TitleFixtures.of(FixtureAnime.exactToWatch)) }
    override val loading = shown { Screen(TitleState.Loading(Fixtures.reference.session)) }
    override val empty = notShown("A title page always shows its title; a title that cannot be loaded is the error case.")
    override val error = shown { Screen(TitleState.Failed(DataError.NoConnection, Fixtures.reference.session)) }

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
            case("details-loading") { Screen(TitleFixtures.of(FixtureAnime.exactToWatch, details = null)) },
            case("details-failed") {
                Screen(
                    TitleFixtures
                        .of(
                            FixtureAnime.exactToWatch,
                            details = null,
                        ).copy(detailsWait = DetailsWait.Failed(DataError.NoConnection)),
                )
            },
            case("refreshing") { Screen(TitleFixtures.of(FixtureAnime.exactToWatch).copy(refreshing = true)) },
            case("no-score") { Screen(TitleFixtures.of(FixtureAnime.premiereTimeLater)) },
            case("planned-scored") { Screen(TitleFixtures.plannedScored) },
            case("first-episode-aired") { Screen(TitleFixtures.firstEpisodeAired) },
            case("finished-with-sequel") { Screen(TitleFixtures.of(FixtureAnime.prequel)) },
            case("related-see-all-only") { Screen(TitleFixtures.relatedOutsideTheStrip) },
            case("synopsis-expanded") {
                Screen(TitleFixtures.of(FixtureAnime.exactToWatch, moment = TitleMoment(synopsisExpanded = true)))
            },
            case("session-expired") { Screen(TitleFixtures.of(FixtureAnime.exactToWatch, session = Fixtures.reference.expired)) },
            case("added-snackbar") { Screen(TitleFixtures.added, TitleFixtures.addedMessage) },
            case("completed-snackbar") { Screen(TitleFixtures.completed, TitleFixtures.completedMessage) },
            case("above-total") { Screen(TitleFixtures.aboveTotal) },
        )
}

@Composable
private fun Screen(
    state: TitleState,
    message: ShellMessage? = null,
) = TitleInShell(state, onEvent = {}, rememberHostShowing(message))

// The page with a snackbar host of its own, as the shell hands it one; the screen draws it.
@Composable
fun TitleInShell(
    state: TitleState,
    onEvent: (TitleEvent) -> Unit,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) = TitleScreen(state, onEvent, snackbars)

object TitleFixtures {
    private val set = Fixtures.reference

    private val statuses: Map<AnimeId, ListStatus> =
        set.entries.associate { it.anime.id to it.status }

    fun page(
        anime: Anime,
        entry: ListEntry? = set.entryFor(anime.id),
        airing: Airing? = set.airing[anime.id],
        details: AnimeDetails? = set.details[anime.id],
    ): TitlePage =
        TitlePage.of(
            anime = anime,
            entry = entry,
            airing = airing,
            details = details,
            listStatuses = statuses,
            language = TitleLanguage.Romaji,
            clock = set.clock,
        )

    fun of(
        anime: Anime,
        entry: ListEntry? = set.entryFor(anime.id),
        airing: Airing? = set.airing[anime.id],
        details: AnimeDetails? = set.details[anime.id],
        session: Session = set.session,
        moment: TitleMoment = TitleMoment(),
    ): TitleState.Shown = TitleState.Shown(page(anime, entry, airing, details), session, moment)

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

    // Besides the sequel, only relations the strip leaves out: the section is "See all" alone.
    val relatedOutsideTheStrip: TitleState.Shown
        get() {
            val anime = FixtureAnime.exactToWatch
            val details = checkNotNull(set.details[anime.id])
            val related =
                listOf(
                    RelatedAnime(FixtureAnime.premiereYear, RelationType.Sequel),
                    RelatedAnime(FixtureAnime.relatedCharacter, RelationType.Character),
                    RelatedAnime(FixtureAnime.relatedOther, RelationType.Other),
                )
            return of(anime, details = details.copy(related = related))
        }

    // "Add to list" was tapped: the title is Planned and the snackbar offers "Change".
    val added: TitleState.Shown
        get() {
            val anime = FixtureAnime.notOnListAiring
            val entry = ListEntry(anime, ListValues(ListStatus.Planned, 0, null, null, null), set.now)
            return of(anime, entry)
        }

    val addedMessage = ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(FixtureAnime.notOnListAiring.id))

    val completedMessage: ShellMessage
        get() {
            val entry = checkNotNull(set.entryFor(FixtureAnime.oneShortOfTotal.id))
            return ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Save(entry.anime, entry.values)))
        }

    // The stepper reached the total: the title became Completed in the same write (W7).
    val completed: TitleState.Shown
        get() = stepped(FixtureAnime.oneShortOfTotal, by = 1)

    // A count typed past MAL's total is kept as typed and completes the title as reaching the total does (W7, W9).
    val aboveTotal: TitleState.Shown
        get() = stepped(FixtureAnime.oneShortOfTotal, by = 3)

    private fun stepped(
        anime: Anime,
        by: Int,
    ): TitleState.Shown {
        val entry = checkNotNull(set.entryFor(anime.id))
        val today = set.now.atZone(set.clock.zone).toLocalDate()
        val values = entry.values.withWatched(entry.values.watchedEpisodes + by, anime.malEpisodes, today)
        return of(anime, entry.copy(values = values))
    }
}
