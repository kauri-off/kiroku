package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.relatedStrip
import io.github.kauri_off.kiroku.core.model.sequelChain
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
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
        // The walk along the chain of sequels has run for a second and goes on.
        val chainLoading: Boolean = false,
        val scores: ScoresBlock = ScoresBlock.NotAsked,
    ) : TitleState
}

// MAL's score distribution of the title, asked of a service apart from the page's own: how that went is its block's
// alone, never the page's (section 2.3).
sealed interface ScoresBlock {
    // Not in view yet, so not asked.
    data object NotAsked : ScoresBlock

    data object Loading : ScoresBlock

    data class Shown(
        val distribution: ScoreDistribution,
    ) : ScoresBlock

    // The service has no record of the title.
    data object NotKnown : ScoresBlock

    data class Failed(
        val error: DataError,
    ) : ScoresBlock
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
    val score: MalScore,
    val entry: ListValues?,
    val airing: AiringPhase?,
    // Null while the details are loading.
    val details: AnimeDetails?,
    // The watch order, this title included; empty for a title with no sequel and no prequel.
    val chain: List<ChainCard>,
    // The strip leaves out the titles of the watch order.
    val related: List<RelatedCard>,
    // True when the title has a related title that neither the watch order nor the strip shows.
    val moreRelated: Boolean,
    // The clock's instant in its zone; every day and time on the page is computed in this zone.
    val now: ZonedDateTime,
) {
    companion object {
        // The total, the whole airing block and the watch order come from :core. [chainPages]: the stored pages of the
        // other titles of the chain of sequels.
        fun of(
            anime: Anime,
            entry: ListEntry?,
            airing: Airing?,
            details: AnimeDetails?,
            listStatuses: Map<AnimeId, EntryStatus>,
            language: TitleLanguage,
            clock: Clock,
            pageRead: Boolean = details != null,
            chainPages: Map<AnimeId, AnimeDetails> = emptyMap(),
        ): TitlePage {
            require(entry == null || entry.anime.id == anime.id)
            val now = ZonedDateTime.now(clock)
            val shown = titleAiring(anime, entry?.values, airing, now.toInstant())
            val total = shown.total
            val phase = shown.phase
            val related = details?.related.orEmpty()
            val chain =
                details
                    ?.let { sequelChain(anime, chainPages.mapValues { it.value.related } + (anime.id to it.related)) }
                    ?.takeIf { it.shown }
            val inChain = chain?.walk.orEmpty().toSet()
            val strip = relatedStrip(related.filter { it.anime.id !in inChain })

            fun card(it: RelatedAnime) = RelatedCard(it, it.anime.titles.preferred(language), listStatuses[it.anime.id])
            return TitlePage(
                anime = anime,
                title = anime.titles.preferred(language),
                total = total,
                facts = headerFacts(anime, total, onList = entry != null, phase),
                score = anime.meanScore?.let(MalScore::Given) ?: if (pageRead) MalScore.None else MalScore.NotKnownYet,
                entry = entry?.values,
                airing = phase,
                details = details,
                chain =
                    chain?.order.orEmpty().mapIndexed { index, it ->
                        ChainCard(
                            it,
                            it.titles.preferred(language),
                            listStatuses[it.id],
                            current = it.id == anime.id,
                            place = (index + 1).takeIf { chain?.whole == true },
                        )
                    },
                related = strip.map(::card),
                moreRelated = related.size > strip.size + related.count { it.anime.id in inChain },
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

// MAL's score as the header states it; only the title's own page, once read, says MAL gives none.
sealed interface MalScore {
    data class Given(
        val value: Double,
    ) : MalScore

    data object None : MalScore

    data object NotKnownYet : MalScore
}

// The header's facts line. Each fact the page states elsewhere is left out: the episode count when the list block
// shows it, the year when the premiere states it.
data class HeaderFacts(
    val type: MediaType?,
    val year: Int?,
    val episodes: Int?,
)

// A title of the watch order. The page's own title is [current], and its row opens nothing.
data class ChainCard(
    val anime: Anime,
    val title: String,
    // The user's status for that title, when it is on their list.
    val status: EntryStatus?,
    val current: Boolean,
    // From 1, only once the whole chain is known, so a place shown is never taken back.
    val place: Int?,
)

data class RelatedCard(
    val related: RelatedAnime,
    val title: String,
    // The user's status for that title, when it is on their list.
    val status: EntryStatus?,
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

    // The statistics or the scores came into view: the scores are asked once for the page.
    data object ScoresInView : TitleEvent

    data object RetryScores : TitleEvent

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
