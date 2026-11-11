package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.relatedStrip
import java.time.Clock
import java.time.Instant

sealed interface TitleState {
    // Opened with no data at all, as from a deep link.
    data object Loading : TitleState

    data class Failed(
        val error: DataError,
    ) : TitleState

    data class Shown(
        val anime: Anime,
        val title: String,
        val total: Int?,
        val entry: ListValues?,
        val airing: AiringBlock?,
        // Null while the details are loading.
        val details: AnimeDetails?,
        val related: List<RelatedCard>,
        val moreRelated: Boolean,
        val now: Instant,
        val synopsisExpanded: Boolean = false,
        val snackbar: TitleSnackbar? = null,
    ) : TitleState
}

// The airing block, from :core's values only.
sealed interface AiringBlock {
    data class Aired(
        val aired: AiredCount.Known,
        val total: Int?,
        // Present only when above zero.
        val toWatch: AiredCount.Known?,
        val next: ScheduledEpisode?,
    ) : AiringBlock

    data object CountUnknown : AiringBlock

    data class Premieres(
        val premiere: Premiere,
    ) : AiringBlock
}

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

    data class OpenRelated(
        val id: AnimeId,
    ) : TitleEvent

    data object SeeAllRelated : TitleEvent

    data object OpenDetails : TitleEvent

    data object OpenRecommendations : TitleEvent

    data object Retry : TitleEvent

    data object SnackbarAction : TitleEvent

    data object SnackbarDismissed : TitleEvent
}

// The page's state from stored data; every aired, total and "to watch" value comes from :core.
fun titleState(
    anime: Anime,
    entry: ListEntry?,
    airing: Airing?,
    details: AnimeDetails?,
    listStatuses: Map<AnimeId, ListStatus>,
    language: TitleLanguage,
    clock: Clock,
): TitleState.Shown {
    val strip = details?.let { relatedStrip(it.related) }.orEmpty()
    return TitleState.Shown(
        anime = anime,
        title = anime.titles.preferred(language),
        total = episodeTotal(anime, airing),
        entry = entry?.values,
        airing = airingBlock(anime, entry, airing, clock),
        details = details,
        related = strip.map { RelatedCard(it, it.anime.titles.preferred(language), listStatuses[it.anime.id]) },
        moreRelated = details != null && details.related.size > strip.size,
        now = clock.instant(),
    )
}

fun airingBlock(
    anime: Anime,
    entry: ListEntry?,
    airing: Airing?,
    clock: Clock,
): AiringBlock? {
    premiereOf(anime, airing, clock.instant().atZone(clock.zone).toLocalDate())?.let { return AiringBlock.Premieres(it) }
    if (anime.airingStatus != AiringStatus.Airing) return null
    val aired = airing?.aired as? AiredCount.Known ?: return AiringBlock.CountUnknown
    return AiringBlock.Aired(
        aired = aired,
        total = episodeTotal(anime, airing),
        toWatch = entry?.let { toWatch(it, airing) }?.takeIf { it.value > 0 },
        next = airing.nextEpisode,
    )
}
