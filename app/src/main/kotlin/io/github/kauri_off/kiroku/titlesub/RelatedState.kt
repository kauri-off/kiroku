package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.relatedGroups

sealed interface RelatedState {
    data object Loading : RelatedState

    data class Failed(
        val error: DataError,
    ) : RelatedState

    data class Shown(
        val groups: List<RelatedGroup>,
    ) : RelatedState
}

data class RelatedGroup(
    val relation: RelationType,
    val rows: List<RelatedRow>,
)

data class RelatedRow(
    val id: AnimeId,
    val title: String,
    val posterUrl: String?,
    val mediaType: MediaType?,
    val year: Int?,
    val total: Int?,
    // The user's values when the title is on their list.
    val entry: ListValues?,
)

sealed interface RelatedEvent {
    data object Back : RelatedEvent

    data object Retry : RelatedEvent

    data class Open(
        val id: AnimeId,
    ) : RelatedEvent
}

// Every related anime, grouped and ordered by :core.
fun relatedState(
    related: List<RelatedAnime>,
    entries: Map<AnimeId, ListValues>,
    language: TitleLanguage,
): RelatedState.Shown =
    RelatedState.Shown(
        relatedGroups(related).map { (relation, items) ->
            RelatedGroup(
                relation,
                items.map {
                    val anime = it.anime
                    RelatedRow(
                        id = anime.id,
                        title = anime.titles.preferred(language),
                        posterUrl = anime.posterUrl,
                        mediaType = anime.mediaType,
                        year = anime.startDate?.year,
                        total = anime.malEpisodes,
                        entry = entries[anime.id],
                    )
                },
            )
        },
    )
