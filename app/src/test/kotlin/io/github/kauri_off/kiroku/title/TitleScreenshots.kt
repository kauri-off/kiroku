package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.TitlePicture
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.screenshots.ArrivingPoster
import io.github.kauri_off.kiroku.screenshots.RemoteImages
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.rememberHostShowing
import io.github.kauri_off.kiroku.standin.StandInPosters
import java.time.Duration
import java.time.LocalDate

private const val FRAME = 16L
private const val FILM_BETWEEN_SEASONS = 900_100L
private val SECTION_WINDOW = WindowSize(widthDp = 411, heightDp = 1180)

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
            case("unknown-status") { Screen(TitleFixtures.unknownStatus) },
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
            case("opened-from-related") { Screen(TitleFixtures.openedFromRelated) },
            case("paired-owned", largeText = true) { Screen(TitleFixtures.pairedOwned) },
            case("paired-owned-wide", window = WindowSize(840, 600)) { Screen(TitleFixtures.pairedOwned) },
            case("cover-open", largeText = true) { Screen(TitleFixtures.of(FixtureAnime.exactToWatch).copy(coverOpen = true)) },
            case("gallery-open", largeText = true) {
                val anime = FixtureAnime.exactToWatch
                val details = checkNotNull(Fixtures.reference.details[anime.id]).copy(pictures = galleryPictures)
                Screen(TitleFixtures.of(anime, details = details).copy(coverOpen = true))
            },
            case("gallery-extra") { GalleryFrame(initialPage = 1) },
            case("gallery-failed", largeText = true) { GalleryFrame(initialPage = 2) },
            case("gallery-cover") { GalleryFrame(initialPage = 0) },
            case("cover-open-wide", window = WindowSize(840, 600)) {
                Screen(TitleFixtures.of(FixtureAnime.exactToWatch).copy(coverOpen = true))
            },
            case("cover-loading") {
                ArrivingPoster.inMemory = false
                ArrivingPoster.arrived.tryEmit(false)
                val anime = FixtureAnime.exactToWatch.copy(largePosterUrl = ArrivingPoster.URL)
                Screen(TitleFixtures.of(anime).copy(coverOpen = true))
            },
            case("cover-failed", largeText = true) {
                val anime = FixtureAnime.exactToWatch.copy(posterUrl = RemoteImages.UNAVAILABLE, largePosterUrl = null)
                Screen(TitleFixtures.of(anime).copy(coverOpen = true))
            },
            case("poster-arriving") { Arriving(inMemory = false, arrived = false) },
            case("poster-arrived") { Arriving(inMemory = true, arrived = false) },
            case("chain-loading") { Screen(TitleFixtures.chainLoading) },
            case("chain-of-four") { Screen(TitleFixtures.chainOfFour) },
            case("chain-failed") { Screen(TitleFixtures.chainFailed) },
            // The first two in a window that holds the whole section, for the frames of the film arriving.
            case("chain-loading-tall", window = SECTION_WINDOW) { Screen(TitleFixtures.chainLoading) },
            case("chain-of-four-tall", window = SECTION_WINDOW) { Screen(TitleFixtures.chainOfFour) },
        )

    // The header's large picture fades in over the row's on the fast effects spring, which does most of its way early.
    override val transitions =
        listOf(
            galleryTransition(),
            // The film the walk found joins the watch order between the seasons, and the indicator leaves.
            Transition(
                "chain-of-four-tall",
                start = "chain-loading-tall",
                millis = MotionBounds.ENTER,
                at = listOf(FRAME, 2 * FRAME, 4 * FRAME, MotionBounds.ENTER),
            ) { found ->
                Screen(if (found) TitleFixtures.chainOfFour else TitleFixtures.chainLoading)
            },
            Transition(
                "poster-arrived",
                start = "poster-arriving",
                millis = MotionBounds.CHANGE,
                at = listOf(FRAME, 2 * FRAME, 4 * FRAME, MotionBounds.CHANGE),
            ) { changed ->
                Arriving(inMemory = false, arrived = changed)
            },
        )
}

internal val galleryPictures =
    listOf(
        TitlePicture(FixtureAnime.prequel.posterUrl, FixtureAnime.prequel.largePosterUrl),
        TitlePicture(RemoteImages.UNAVAILABLE, null),
    )

@Composable
private fun GalleryFrame(
    initialPage: Int,
    advancing: Boolean = false,
) {
    val anime = FixtureAnime.exactToWatch
    val pages = listOf(TitlePicture(anime.posterUrl, anime.largePosterUrl)).plus(galleryPictures).map(TitlePicture::sources)
    val pager = rememberPagerState(initialPage) { pages.size }
    val motion = MaterialTheme.motionScheme
    LaunchedEffect(advancing) {
        if (advancing) pager.animateScrollToPage(1, animationSpec = motion.defaultEffectsSpec())
    }
    TitleCoverWindow(anime.titles.romaji, gallery = true, onClose = {}) {
        CoverGallery(pages, anime.titles.romaji, pager)
    }
}

private fun galleryTransition() =
    Transition(
        "gallery-extra",
        start = "gallery-cover",
        name = "gallery-page-turn",
        millis = MotionBounds.ENTER,
        at = listOf(FRAME, 3 * FRAME, 6 * FRAME, MotionBounds.ENTER),
    ) { advancing ->
        GalleryFrame(initialPage = 0, advancing = advancing)
    }

// The header with the row's picture in memory and the large one on its way, there at once, or arriving now.
@Composable
private fun Arriving(
    inMemory: Boolean,
    arrived: Boolean,
) {
    val anime = FixtureAnime.exactToWatch.copy(largePosterUrl = ArrivingPoster.URL)
    ArrivingPoster.inMemory = inMemory
    ArrivingPoster.arrived.tryEmit(arrived)
    val loader = SingletonImageLoader.get(LocalPlatformContext.current)
    remember(loader) {
        MemoryCache.Value(StandInPosters.draw(anime.id.value).asImage()).also { row ->
            loader.memoryCache?.set(MemoryCache.Key(checkNotNull(anime.posterUrl)), row)
        }
    }
    Screen(TitleFixtures.of(anime))
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

    private val statuses: Map<AnimeId, EntryStatus> =
        set.entries.associate { it.anime.id to it.status }

    // Every page of the set is stored, so a page shows its whole chain of sequels.
    fun page(
        anime: Anime,
        entry: ListEntry? = set.entryFor(anime.id),
        airing: Airing? = set.airing[anime.id],
        details: AnimeDetails? = set.details[anime.id],
        chainPages: Map<AnimeId, AnimeDetails> = set.details,
    ): TitlePage =
        TitlePage.of(
            anime = anime,
            entry = entry,
            airing = airing,
            details = details,
            listStatuses = statuses,
            language = TitleLanguage.Romaji,
            clock = set.clock,
            chainPages = chainPages,
        )

    fun of(
        anime: Anime,
        entry: ListEntry? = set.entryFor(anime.id),
        airing: Airing? = set.airing[anime.id],
        details: AnimeDetails? = set.details[anime.id],
        session: Session = set.session,
        moment: TitleMoment = TitleMoment(),
    ): TitleState.Shown = TitleState.Shown(page(anime, entry, airing, details), session, moment)

    val pairedOwned: TitleState.Shown
        get() {
            val anime = FixtureAnime.exactToWatch
            val entry = checkNotNull(set.entryFor(anime.id))
            return of(anime, entry.copy(values = entry.values.copy(status = ListStatus.Watching, watchedEpisodes = 12, score = 8)))
        }

    // The longest status name beside the longest score text.
    val plannedScored: TitleState.Shown
        get() {
            val anime = FixtureAnime.exactToWatch
            val entry = checkNotNull(set.entryFor(anime.id))
            return of(anime, entry.copy(values = entry.values.copy(status = ListStatus.Planned, score = 10)))
        }

    // A status MAL gave that the app does not know, named by its code.
    val unknownStatus: TitleState.Shown
        get() {
            val anime = FixtureAnime.onHoldAiring
            val entry = checkNotNull(set.entryFor(anime.id))
            return of(anime, entry.copy(values = entry.values.copy(status = Fixtures.unknownListStatus)))
        }

    // Episode 1 aired an hour ago and MAL still says "not yet aired": the stored count decides.
    val firstEpisodeAired: TitleState.Shown
        get() {
            val anime = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.NotYetAired)
            val entry = ListEntry(anime, ListValues(ListStatus.Watching, 0, null, null, null), set.now)
            val episodes = FixtureTime.weekly(1, set.now.minus(Duration.ofHours(1)), 1..2)
            return of(anime, entry, airingAt(anime, AiringSchedule(episodes, anime.malEpisodes), set.now))
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

    // A related row's title, neither on the list nor stored, its page loading for over a second; the row gave no
    // score, rank or episode count.
    val openedFromRelated: TitleState.Shown
        get() {
            val anime = FixtureAnime.relatedFullStory.copy(meanScore = null, rank = null, malEpisodes = null)
            return of(anime, entry = null, airing = null, details = null).copy(detailsWait = DetailsWait.Loading)
        }

    // A film between the second and the third season: the second season's page names only the third, whose page names
    // the film as its prequel.
    val filmBetweenSeasons: Anime =
        FixtureAnime.film.copy(
            id = AnimeId(FILM_BETWEEN_SEASONS),
            titles =
                Titles(
                    "Kagerou Toshokan Movie: Natsu no Owari",
                    "The Heat Haze Library the Movie: The End of Summer",
                    "劇場版 陽炎図書館 夏の終わり",
                ),
            posterUrl = "${FixtureAnime.POSTER_URL}$FILM_BETWEEN_SEASONS.jpg",
            largePosterUrl = null,
            startDate = PartialDate.Day(LocalDate.parse("2026-08-21")),
        )

    private val chainOfFourPages: Map<AnimeId, AnimeDetails>
        get() {
            val third = checkNotNull(set.details[FixtureAnime.premiereYear.id])
            val film = checkNotNull(set.details[FixtureAnime.film.id])
            return set.details +
                mapOf(
                    FixtureAnime.premiereYear.id to third.copy(related = listOf(RelatedAnime(filmBetweenSeasons, RelationType.Prequel))),
                    filmBetweenSeasons.id to
                        film.copy(
                            id = filmBetweenSeasons.id,
                            related =
                                listOf(
                                    RelatedAnime(FixtureAnime.exactToWatch, RelationType.Prequel),
                                    RelatedAnime(FixtureAnime.premiereYear, RelationType.Sequel),
                                ),
                        ),
                )
        }

    // The walk has read every page of the chain: the film comes between the seasons.
    val chainOfFour: TitleState.Shown
        get() = chainOfFourWith()

    // The same with the title's own entry and page as given, as "Add to list" or a refresh changes them.
    fun chainOfFourWith(
        entry: ListEntry? = set.entryFor(FixtureAnime.exactToWatch.id),
        details: AnimeDetails? = set.details[FixtureAnime.exactToWatch.id],
    ): TitleState.Shown =
        TitleState.Shown(page(FixtureAnime.exactToWatch, entry = entry, details = details, chainPages = chainOfFourPages), set.session)

    // The walk has run for a second: the chain is what the title's own page tells, with the indicator under it.
    val chainLoading: TitleState.Shown
        get() = TitleState.Shown(page(FixtureAnime.exactToWatch, chainPages = emptyMap()), set.session, chainLoading = true)

    // The walk's first request failed: the page stays as the title's own page tells it, and says nothing.
    val chainFailed: TitleState.Shown
        get() = TitleState.Shown(page(FixtureAnime.exactToWatch, chainPages = emptyMap()), set.session)

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
