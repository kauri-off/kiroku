package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.airingPhase
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.relatedStrip
import io.github.kauri_off.kiroku.core.model.sequelOf
import java.time.Clock
import java.time.ZonedDateTime

sealed interface TitleState {
    // Opened with no data at all, as from a deep link.
    data object Loading : TitleState

    data class Failed(
        val error: DataError,
        val sessionExpired: Boolean = false,
    ) : TitleState

    data class Shown(
        val anime: Anime,
        val title: String,
        val total: Int?,
        val facts: HeaderFacts,
        val entry: ListValues?,
        val airing: AiringPhase?,
        // Null while the details are loading.
        val details: AnimeDetails?,
        val sequel: RelatedCard?,
        // The strip leaves out the sequel, which has its own row.
        val related: List<RelatedCard>,
        val moreRelated: Boolean,
        // The clock's instant in its zone; every day and time on the page is computed in this zone.
        val now: ZonedDateTime,
        val sessionExpired: Boolean,
        val synopsisExpanded: Boolean = false,
        val snackbar: TitleSnackbar? = null,
    ) : TitleState
}

// The header's facts line. Each fact the page states elsewhere is left out: the episode count when the list block
// shows it, the year when the premiere states it.
data class HeaderFacts(
    val type: MediaType?,
    val year: Int?,
    val episodes: Int?,
)

data class RelatedCard(
    val related: RelatedAnime,
    val title: String,
    // The user's status for that title, when it is on their list.
    val status: ListStatus?,
)

enum class TitleSnackbar { AddedToPlanned, MovedToCompleted }

sealed interface TitleEvent {
    data object Back : TitleEvent

    data object OpenOnMal : TitleEvent

    data object Share : TitleEvent

    data object AddToList : TitleEvent

    data object Edit : TitleEvent

    data class SetWatched(
        val watched: Int,
    ) : TitleEvent

    data object ExpandSynopsis : TitleEvent

    data object CollapseSynopsis : TitleEvent

    data class OpenRelated(
        val id: AnimeId,
    ) : TitleEvent

    data object SeeAllRelated : TitleEvent

    data object OpenDetails : TitleEvent

    data object OpenRecommendations : TitleEvent

    data object Retry : TitleEvent

    data object SignIn : TitleEvent

    data object SnackbarAction : TitleEvent

    data object SnackbarDismissed : TitleEvent
}

// The page's state from stored data; the total and the whole airing block come from :core.
fun titleState(
    anime: Anime,
    entry: ListEntry?,
    airing: Airing?,
    details: AnimeDetails?,
    listStatuses: Map<AnimeId, ListStatus>,
    language: TitleLanguage,
    session: Session,
    clock: Clock,
): TitleState.Shown {
    val now = ZonedDateTime.now(clock)
    val total = episodeTotal(anime, airing)
    val phase = airingPhase(anime, entry, airing, now.toLocalDate())
    val related = details?.related.orEmpty()
    val sequel = sequelOf(related)
    val strip = relatedStrip(related - listOfNotNull(sequel).toSet())

    fun card(it: RelatedAnime) = RelatedCard(it, it.anime.titles.preferred(language), listStatuses[it.anime.id])
    return TitleState.Shown(
        anime = anime,
        title = anime.titles.preferred(language),
        total = total,
        facts = headerFacts(anime, total, onList = entry != null, phase),
        entry = entry?.values,
        airing = phase,
        details = details,
        sequel = sequel?.let(::card),
        related = strip.map(::card),
        moreRelated = related.size > strip.size + listOfNotNull(sequel).size,
        now = now,
        sessionExpired = session == Session.Expired,
    )
}

private fun headerFacts(
    anime: Anime,
    total: Int?,
    onList: Boolean,
    phase: AiringPhase?,
): HeaderFacts {
    val premiere = (phase as? AiringPhase.NotYetAired)?.premiere
    val premiereHasYear = premiere is Premiere.OnDay || premiere is Premiere.InMonth || premiere is Premiere.InYear
    return HeaderFacts(
        type = anime.mediaType,
        year = anime.startDate?.year?.takeUnless { premiereHasYear },
        episodes = total?.takeIf { !onList && it > 1 },
    )
}
