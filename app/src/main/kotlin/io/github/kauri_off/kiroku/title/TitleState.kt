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

// The page is rebuilt from stored data with TitlePage.of whenever any of it changes; the moment is carried over.
sealed interface TitleState {
    // Every state has the top bar, so every state shows the session banner while the session is expired (S6).
    // Null until the session is known.
    val session: Session?

    // Opened with no data at all, as from a deep link.
    data class Loading(
        override val session: Session?,
    ) : TitleState

    data class Failed(
        val error: DataError,
        override val session: Session?,
    ) : TitleState

    data class Shown(
        val page: TitlePage,
        override val session: Session?,
        val moment: TitleMoment = TitleMoment(),
        // What stands where the details would be while the page has none.
        val detailsWait: DetailsWait = DetailsWait.Loading,
        // A refresh pulled by hand is running.
        val refreshing: Boolean = false,
    ) : TitleState
}

sealed interface DetailsWait {
    // The first moment of a wait, which a page answered within it never shows.
    data object Brief : DetailsWait

    data object Loading : DetailsWait

    // Stays until a load succeeds.
    data class Failed(
        val error: DataError,
    ) : DetailsWait
}

val TitleState.sessionExpired: Boolean get() = session is Session.Expired

// What the page shows from stored data. Only [of] builds it and it has no public copy, so a derived value cannot
// disagree with its source.
@ConsistentCopyVisibility
data class TitlePage private constructor(
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
    // True when the title has a related title that neither the sequel row nor the strip shows.
    val moreRelated: Boolean,
    // The clock's instant in its zone; every day and time on the page is computed in this zone.
    val now: ZonedDateTime,
) {
    companion object {
        // The total and the whole airing block come from :core.
        fun of(
            anime: Anime,
            entry: ListEntry?,
            airing: Airing?,
            details: AnimeDetails?,
            listStatuses: Map<AnimeId, ListStatus>,
            language: TitleLanguage,
            clock: Clock,
        ): TitlePage {
            require(entry == null || entry.anime.id == anime.id)
            val now = ZonedDateTime.now(clock)
            val total = episodeTotal(anime, airing)
            val phase = airingPhase(anime, entry?.values, airing, now.toLocalDate())
            val related = details?.related.orEmpty()
            val sequel = sequelOf(related)
            val strip = relatedStrip(related - listOfNotNull(sequel).toSet())

            fun card(it: RelatedAnime) = RelatedCard(it, it.anime.titles.preferred(language), listStatuses[it.anime.id])
            return TitlePage(
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
            )
        }
    }
}

// What belongs to the user's moment on the page rather than to the data: it survives every rebuild of the page.
data class TitleMoment(
    val synopsisExpanded: Boolean = false,
) {
    // The part of an event that concerns the moment; every other event leaves it as it is.
    fun after(event: TitleEvent): TitleMoment =
        when (event) {
            TitleEvent.ExpandSynopsis -> copy(synopsisExpanded = true)
            TitleEvent.CollapseSynopsis -> copy(synopsisExpanded = false)
            else -> this
        }
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

sealed interface TitleEvent {
    data object Back : TitleEvent

    data object OpenOnMal : TitleEvent

    data object Share : TitleEvent

    data object AddToList : TitleEvent

    data object Edit : TitleEvent

    // A typed count.
    data class SetWatched(
        val watched: Int,
    ) : TitleEvent

    // The stepper's plus or minus.
    data class StepWatched(
        val by: Int,
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

    data object Refresh : TitleEvent

    data object SignIn : TitleEvent
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
