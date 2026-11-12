package io.github.kauri_off.kiroku.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.edit.EditSheetState
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

// One title's page. The page is rebuilt with TitlePage.of whenever a source changes; the moment is held apart from
// it. Navigation events are the route's.
class TitleViewModel(
    private val id: AnimeId,
    private val anime: AnimeRepository,
    list: ListRepository,
    airing: AiringRepository,
    session: SessionRepository,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
) : ViewModel() {
    private class Sources(
        val anime: Anime?,
        val details: AnimeDetails?,
        val list: ListRead,
        val airing: Map<AnimeId, Airing>,
        val session: Session,
    )

    private class ListRead(
        val entry: ListEntry? = null,
        val statuses: Map<AnimeId, ListStatus> = emptyMap(),
        val failure: DataError? = null,
    )

    private val moment = MutableStateFlow(TitleMoment(synopsisExpanded = saved[EXPANDED] ?: false))
    private val failure = MutableStateFlow<DataError?>(null)
    private val detailsWaitShown = MutableStateFlow(false)
    private val retries = MutableStateFlow(0)
    private var detailsWait: Job? = null
    private var refreshed = false

    // A list that cannot be read fails the page, since its list values would be unknown.
    private val listRead =
        list
            .observeEntries()
            .map { list -> ListRead(list.firstOrNull { it.anime.id == id }, list.associate { it.anime.id to it.status }) }
            .readAgainOnRetry(retries) { ListRead(failure = it) }

    val state: StateFlow<TitleState> =
        combine(
            combine(
                anime.observeAnime(id),
                anime.observeDetails(id),
                listRead,
                airing.observeAiring(),
                session.observeSession(),
                ::Sources,
            ),
            moment,
            failure,
            detailsWaitShown,
            ::build,
        ).onStart { if (!refreshed) refresh() }
            .stateIn(viewModelScope, WhileShown, TitleState.Loading(null))

    fun onEvent(event: TitleEvent) {
        when (event) {
            TitleEvent.AddToList -> addToList()
            is TitleEvent.SetWatched -> watched { ListWrite.SetWatched(it, event.watched) }
            is TitleEvent.StepWatched -> watched { ListWrite.StepWatched(it, event.by) }
            TitleEvent.Retry -> {
                retries.update { it + 1 }
                refresh()
            }
            else -> Unit
        }
        val next = moment.value.after(event)
        moment.value = next
        saved[EXPANDED] = next.synopsisExpanded
    }

    private fun build(
        sources: Sources,
        moment: TitleMoment,
        failure: DataError?,
        detailsWaitShown: Boolean,
    ): TitleState {
        val anime = sources.anime ?: sources.list.entry?.anime
        val session = sources.session
        val listFailure = sources.list.failure
        return when {
            listFailure != null -> TitleState.Failed(listFailure, session)
            anime == null -> failure?.let { TitleState.Failed(it, session) } ?: TitleState.Loading(session)
            sources.details == null && failure != null -> TitleState.Failed(failure, session)
            else ->
                TitleState.Shown(
                    TitlePage.of(
                        anime,
                        sources.list.entry,
                        sources.airing[id],
                        sources.details,
                        sources.list.statuses,
                        titleLanguage,
                        clock,
                    ),
                    session,
                    moment,
                    detailsWaitShown,
                )
        }
    }

    private fun shownPage(): TitlePage? = (state.value as? TitleState.Shown)?.page

    // With details already shown, a failed refresh is a message and the page stays (section 7.2).
    private fun refresh() {
        refreshed = true
        failure.value = null
        detailsWait?.cancel()
        detailsWaitShown.value = false
        detailsWait =
            viewModelScope.launch {
                delay(DETAILS_PATIENCE)
                detailsWaitShown.value = true
            }
        viewModelScope.launch {
            val result = anime.refresh(id)
            if (result !is Outcome.Failure) return@launch
            if (anime.observeDetails(id).first() != null) {
                if (result.error != DataError.SessionExpired) messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(result.error)))
            } else {
                failure.value = result.error
            }
        }
    }

    private fun addToList() {
        val page = shownPage() ?: return
        if (page.entry != null) return
        messages.write(ListWrite.Save(page.anime, EditSheetState.newEntry)) {
            ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(id))
        }
    }

    // A change that completes the title (W7) says so, with an Undo of that change alone.
    private fun watched(write: (Anime) -> ListWrite) {
        val page = shownPage() ?: return
        if (page.entry == null) return
        messages.write(write(page.anime)) { change ->
            if (change.completed) ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(change.undo)) else null
        }
    }

    private companion object {
        val EXPANDED = notShown("synopsisExpanded")

        // Within a second the flow of use is not interrupted, so a shorter wait shows no indicator.
        val DETAILS_PATIENCE = 1.seconds
    }
}
