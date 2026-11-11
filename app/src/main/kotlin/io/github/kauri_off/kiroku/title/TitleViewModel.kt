package io.github.kauri_off.kiroku.title

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.edit.EditSheetState
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.launch
import java.time.LocalDate

// One title's page. The page is rebuilt with TitlePage.of whenever a source changes; the moment is held apart from
// it. Navigation events are the route's.
class TitleViewModel(
    private val graph: AppGraph,
    private val id: AnimeId,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var anime by mutableStateOf<Anime?>(null)
    private var entry by mutableStateOf<ListEntry?>(null)
    private var details by mutableStateOf<AnimeDetails?>(null)
    private var airing by mutableStateOf<Map<AnimeId, Airing>>(emptyMap())
    private var statuses by mutableStateOf<Map<AnimeId, ListStatus>>(emptyMap())
    private var session by mutableStateOf<Session>(Session.SignedOut)
    private var failure by mutableStateOf<DataError?>(null)
    private var moment by mutableStateOf(TitleMoment(synopsisExpanded = saved[EXPANDED] ?: false))

    // What Undo of "Moved to Completed" puts back.
    private var undo: ListValues? = null

    val state: State<TitleState> = derivedStateOf { build() }

    init {
        viewModelScope.launch { graph.anime.observeAnime(id).collect { anime = it } }
        viewModelScope.launch { graph.anime.observeDetails(id).collect { details = it } }
        viewModelScope.launch { graph.list.observeEntry(id).collect { entry = it } }
        viewModelScope.launch {
            graph.list.observeEntries().collect { list -> statuses = list.associate { it.anime.id to it.status } }
        }
        viewModelScope.launch { graph.airing.observeAiring().collect { airing = it } }
        viewModelScope.launch { graph.session.observeSession().collect { session = it } }
        refresh()
    }

    fun onEvent(event: TitleEvent) {
        when (event) {
            TitleEvent.AddToList -> addToList()
            is TitleEvent.SetWatched -> setWatched(event.watched)
            TitleEvent.SnackbarAction ->
                if (moment.snackbar == TitleSnackbar.MovedToCompleted) {
                    val values = undo
                    val anime = shownAnime()
                    if (values != null && anime != null) write(ListWrite.Save(anime, values))
                }
            TitleEvent.Retry -> refresh()
            else -> Unit
        }
        if (event == TitleEvent.SnackbarAction || event == TitleEvent.SnackbarDismissed) undo = null
        moment = moment.after(event)
        saved[EXPANDED] = moment.synopsisExpanded
    }

    private fun build(): TitleState {
        val anime = shownAnime()
        val failure = failure
        return when {
            anime == null -> failure?.let { TitleState.Failed(it, session) } ?: TitleState.Loading(session)
            details == null && failure != null -> TitleState.Failed(failure, session)
            else ->
                TitleState.Shown(
                    TitlePage.of(anime, entry, airing[id], details, statuses, graph.titleLanguage, graph.clock),
                    session,
                    moment,
                )
        }
    }

    private fun shownAnime(): Anime? = anime ?: entry?.anime

    private fun refresh() {
        failure = null
        viewModelScope.launch {
            val result = graph.anime.refresh(id)
            if (result is Outcome.Failure) failure = result.error
        }
    }

    private fun addToList() {
        val anime = shownAnime() ?: return
        if (entry != null) return
        write(ListWrite.Save(anime, EditSheetState.newEntry))
        moment = moment.copy(snackbar = TitleSnackbar.AddedToPlanned)
    }

    // A count of any size is accepted (W9); reaching or passing MAL's total completes the title (W7).
    private fun setWatched(watched: Int) {
        val current = entry ?: return
        val before = current.values
        val after = before.withWatched(watched, current.anime.malEpisodes, LocalDate.now(graph.clock))
        if (after == before) return
        write(ListWrite.Save(current.anime, after))
        if (after.status == ListStatus.Completed && before.status != ListStatus.Completed) {
            undo = before
            moment = moment.copy(snackbar = TitleSnackbar.MovedToCompleted)
        }
    }

    private fun write(write: ListWrite) {
        viewModelScope.launch { graph.list.write(write) }
    }

    private companion object {
        val EXPANDED = notShown("synopsisExpanded")
    }
}
