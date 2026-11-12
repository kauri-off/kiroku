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
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.edit.EditSheetState
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.launch

// One title's page. The page is rebuilt with TitlePage.of whenever a source changes; the moment is held apart from
// it. Navigation events are the route's.
class TitleViewModel(
    private val graph: AppGraph,
    private val id: AnimeId,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
) : ViewModel() {
    private var anime by mutableStateOf<Anime?>(null)
    private var entry by mutableStateOf<ListEntry?>(null)
    private var details by mutableStateOf<AnimeDetails?>(null)
    private var airing by mutableStateOf<Map<AnimeId, Airing>>(emptyMap())
    private var statuses by mutableStateOf<Map<AnimeId, ListStatus>>(emptyMap())
    private var session by mutableStateOf<Session?>(null)
    private var failure by mutableStateOf<DataError?>(null)
    private var moment by mutableStateOf(TitleMoment(synopsisExpanded = saved[EXPANDED] ?: false))

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
            is TitleEvent.SetWatched -> watched(ListWrite.SetWatched(shownAnime() ?: return, event.watched))
            is TitleEvent.StepWatched -> watched(ListWrite.StepWatched(shownAnime() ?: return, event.by))
            TitleEvent.Retry -> refresh()
            else -> Unit
        }
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

    // With details already shown, a failed refresh is a message and the page stays (section 7.2).
    private fun refresh() {
        failure = null
        viewModelScope.launch {
            val result = graph.anime.refresh(id)
            if (result !is Outcome.Failure) return@launch
            if (details != null) {
                if (result.error != DataError.SessionExpired) messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(result.error)))
            } else {
                failure = result.error
            }
        }
    }

    private fun addToList() {
        val anime = shownAnime() ?: return
        if (entry != null) return
        messages.write(ListWrite.Save(anime, EditSheetState.newEntry)) {
            ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(id))
        }
    }

    // A change that completes the title (W7) says so, with an Undo of that change alone.
    private fun watched(write: ListWrite) {
        if (entry == null) return
        messages.write(write) { change ->
            if (change.completed) ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(change.undo)) else null
        }
    }

    private companion object {
        val EXPANDED = notShown("synopsisExpanded")
    }
}
