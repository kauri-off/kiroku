package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.airingPhase
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.error.DataError
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
import java.time.ZonedDateTime

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
        val airing: AiringPhase?,
        // Null while the details are loading.
        val details: AnimeDetails?,
        val related: List<RelatedCard>,
        val moreRelated: Boolean,
        // The clock's instant in its zone; every day and time on the page is computed in this zone.
        val now: ZonedDateTime,
        val synopsisExpanded: Boolean = false,
        val snackbar: TitleSnackbar? = null,
    ) : TitleState
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

// The page's state from stored data; the total and the whole airing block come from :core.
fun titleState(
    anime: Anime,
    entry: ListEntry?,
    airing: Airing?,
    details: AnimeDetails?,
    listStatuses: Map<AnimeId, ListStatus>,
    language: TitleLanguage,
    clock: Clock,
): TitleState.Shown {
    val now = ZonedDateTime.now(clock)
    val strip = details?.let { relatedStrip(it.related) }.orEmpty()
    return TitleState.Shown(
        anime = anime,
        title = anime.titles.preferred(language),
        total = episodeTotal(anime, airing),
        entry = entry?.values,
        airing = airingPhase(anime, entry, airing, now.toLocalDate()),
        details = details,
        related = strip.map { RelatedCard(it, it.anime.titles.preferred(language), listStatuses[it.anime.id]) },
        moreRelated = details != null && details.related.size > strip.size,
        now = now,
    )
}
