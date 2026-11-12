package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.relatedGroups

// Every state has the top bar, so every state shows the session banner while the session is expired (S6).
sealed interface RelatedState {
    // Null until the session is known.
    val session: Session?

    data class Loading(
        override val session: Session?,
    ) : RelatedState

    data class Failed(
        val error: DataError,
        override val session: Session?,
    ) : RelatedState

    data class Shown(
        val groups: List<RelatedGroup>,
        override val session: Session?,
    ) : RelatedState
}

val RelatedState.sessionExpired: Boolean get() = session == Session.Expired

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

    data object SignIn : RelatedEvent

    data class Open(
        val id: AnimeId,
    ) : RelatedEvent
}

// Every related anime, grouped and ordered by :core.
fun relatedState(
    related: List<RelatedAnime>,
    entries: Map<AnimeId, ListValues>,
    airing: Map<AnimeId, Airing>,
    language: TitleLanguage,
    session: Session?,
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
                        total = episodeTotal(anime, airing[anime.id]),
                        entry = entries[anime.id],
                    )
                },
            )
        },
        session,
    )
