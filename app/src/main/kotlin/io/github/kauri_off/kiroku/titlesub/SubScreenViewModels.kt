package io.github.kauri_off.kiroku.titlesub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.readAgainOnRetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// What both sub-screens read: one title, its details, and whether loading them failed. The first collection refreshes.
abstract class SubScreenViewModel(
    private val id: AnimeId,
    private val anime: AnimeRepository,
    session: SessionRepository,
) : ViewModel() {
    protected class Title(
        val anime: Anime?,
        val details: AnimeDetails?,
        val session: Session,
        val failure: DataError?,
    )

    private val failure = MutableStateFlow<DataError?>(null)
    private var refreshed = false

    protected val title: Flow<Title> =
        combine(anime.observeAnime(id), anime.observeDetails(id), session.observeSession(), failure, ::Title)
            .onStart { if (!refreshed) refresh() }

    protected fun refresh() {
        refreshed = true
        failure.value = null
        viewModelScope.launch {
            val result = anime.refresh(id)
            if (result is Outcome.Failure) failure.value = result.error
        }
    }
}

class RelatedViewModel(
    id: AnimeId,
    anime: AnimeRepository,
    list: ListRepository,
    airing: AiringRepository,
    session: SessionRepository,
    private val titleLanguage: TitleLanguage,
) : SubScreenViewModel(id, anime, session) {
    private class ListRead(
        val entries: Map<AnimeId, ListValues> = emptyMap(),
        val failure: DataError? = null,
    )

    private val retries = MutableStateFlow(0)

    // A list that cannot be read fails the screen, since the related titles' list statuses would be unknown.
    private val listRead =
        list
            .observeEntries()
            .map { list -> ListRead(list.associate { it.anime.id to it.values }) }
            .readAgainOnRetry(retries) { ListRead(failure = it) }

    val state: StateFlow<RelatedState> =
        combine(title, listRead, airing.observeAiring()) { title, list, airing ->
            val details = title.details
            val failure = title.failure
            val listFailure = list.failure
            when {
                listFailure != null -> RelatedState.Failed(listFailure, title.session)
                details != null -> relatedState(details.related, list.entries, airing, titleLanguage, title.session)
                failure != null -> RelatedState.Failed(failure, title.session)
                else -> RelatedState.Loading(title.session)
            }
        }.stateIn(viewModelScope, WhileShown, RelatedState.Loading(null))

    fun onEvent(event: RelatedEvent) {
        if (event != RelatedEvent.Retry) return
        retries.update { it + 1 }
        refresh()
    }
}

class DetailsViewModel(
    id: AnimeId,
    anime: AnimeRepository,
    session: SessionRepository,
    private val titleLanguage: TitleLanguage,
    private val clock: Clock,
) : SubScreenViewModel(id, anime, session) {
    val state: StateFlow<DetailsState> =
        title
            .map { title ->
                val anime = title.anime
                val details = title.details
                val failure = title.failure
                when {
                    anime != null && details != null -> detailsState(anime, details, titleLanguage, title.session, clock)
                    failure != null -> DetailsState.Failed(failure, title.session)
                    else -> DetailsState.Loading(title.session)
                }
            }.stateIn(viewModelScope, WhileShown, DetailsState.Loading(null))

    fun onEvent(event: DetailsEvent) {
        if (event == DetailsEvent.Retry) refresh()
    }
}
