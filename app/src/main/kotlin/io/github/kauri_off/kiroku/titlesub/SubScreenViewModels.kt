package io.github.kauri_off.kiroku.titlesub

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.launch

// What both sub-screens read: one title, its details, and whether loading them failed.
abstract class SubScreenViewModel(
    protected val graph: AppGraph,
    protected val id: AnimeId,
) : ViewModel() {
    protected var anime by mutableStateOf<Anime?>(null)
    protected var details by mutableStateOf<AnimeDetails?>(null)
    protected var session by mutableStateOf<Session>(Session.SignedOut)
    protected var failure by mutableStateOf<DataError?>(null)

    init {
        viewModelScope.launch { graph.anime.observeAnime(id).collect { anime = it } }
        viewModelScope.launch { graph.anime.observeDetails(id).collect { details = it } }
        viewModelScope.launch { graph.session.observeSession().collect { session = it } }
        refresh()
    }

    protected fun refresh() {
        failure = null
        viewModelScope.launch {
            val result = graph.anime.refresh(id)
            if (result is Outcome.Failure) failure = result.error
        }
    }
}

class RelatedViewModel(
    graph: AppGraph,
    id: AnimeId,
) : SubScreenViewModel(graph, id) {
    private var entries by mutableStateOf<Map<AnimeId, ListValues>>(emptyMap())
    private var airing by mutableStateOf<Map<AnimeId, Airing>>(emptyMap())

    val state: State<RelatedState> =
        derivedStateOf {
            val details = details
            val failure = failure
            when {
                details != null -> relatedState(details.related, entries, airing, graph.titleLanguage, session)
                failure != null -> RelatedState.Failed(failure, session)
                else -> RelatedState.Loading(session)
            }
        }

    init {
        viewModelScope.launch {
            graph.list.observeEntries().collect { list -> entries = list.associate { it.anime.id to it.values } }
        }
        viewModelScope.launch { graph.airing.observeAiring().collect { airing = it } }
    }

    fun onEvent(event: RelatedEvent) {
        if (event == RelatedEvent.Retry) refresh()
    }
}

class DetailsViewModel(
    graph: AppGraph,
    id: AnimeId,
) : SubScreenViewModel(graph, id) {
    val state: State<DetailsState> =
        derivedStateOf {
            val anime = anime
            val details = details
            val failure = failure
            when {
                anime != null && details != null -> detailsState(anime, details, graph.titleLanguage, session, graph.clock)
                failure != null -> DetailsState.Failed(failure, session)
                else -> DetailsState.Loading(session)
            }
        }

    fun onEvent(event: DetailsEvent) {
        if (event == DetailsEvent.Retry) refresh()
    }
}
