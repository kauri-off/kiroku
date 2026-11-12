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
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
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
    list: ListSource,
    airing: AiringRepository,
    session: SessionRepository,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
    private val opened: TitleSummary? = null,
) : ViewModel() {
    private class Sources(
        val anime: Anime?,
        val details: AnimeDetails?,
        val list: ListRead,
        val airing: Map<AnimeId, Airing>,
        val session: Session?,
    ) {
        // The store's answers are not known yet; the summary stands in for them in build.
        constructor(opened: TitleSummary) : this(null, null, ListRead(opened.knownEntries()), opened.knownAiring(), opened.session)
    }

    private class ListRead(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
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
            .map { list -> ListRead(list.associateBy { it.anime.id }) }
            .readAgainOnRetry(retries) { ListRead(failure = it) }

    // Starts from what the opening screen showed, so the page needs no read for its first frame; build falls back to it.
    private val sources: StateFlow<Sources?> =
        combine(
            anime.observeAnime(id),
            anime.observeDetails(id),
            listRead,
            airing.observeAiring(),
            session.observeSession(),
            ::Sources,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let(::Sources))

    val state: StateFlow<TitleState> =
        combine(sources.filterNotNull(), moment, failure, detailsWaitShown, ::build)
            .onStart { if (!refreshed) refresh() }
            .stateIn(viewModelScope, WhileShown, sources.value?.let { build(it, moment.value, null, false) } ?: TitleState.Loading(null))

    // What the page shows of [id], itself or a related title, for the screen it opens.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val sources = sources.value ?: return null
        if (sources.list.failure != null) return null
        val entry = sources.list.entries[id]
        val details = detailsOf(sources)
        if (id == this.id) {
            val page = details?.let { pageSummary(it, sources.list.entries, sources.airing) }
            return TitleSummary(animeOf(sources) ?: return null, entry, sources.airing[id], sources.session, page)
        }
        // A related row knows less of a title than its list entry does.
        val anime = entry?.anime ?: details?.related?.firstOrNull { it.anime.id == id }?.anime ?: return null
        return TitleSummary(anime, entry, sources.airing[id], sources.session)
    }

    // The fullest of the store's title, the list entry's and the summary's.
    private fun animeOf(sources: Sources): Anime? = sources.anime ?: sources.list.entries[id]?.anime ?: opened?.anime

    private fun detailsOf(sources: Sources): AnimeDetails? = sources.details ?: opened?.page?.details

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
        val entry = sources.list.entries[id]
        val anime = animeOf(sources)
        val details = detailsOf(sources)
        val session = sources.session
        val listFailure = sources.list.failure
        return when {
            listFailure != null -> TitleState.Failed(listFailure, session)
            anime == null -> failure?.let { TitleState.Failed(it, session) } ?: TitleState.Loading(session)
            details == null && failure != null -> TitleState.Failed(failure, session)
            else ->
                TitleState.Shown(
                    TitlePage.of(
                        anime,
                        entry,
                        sources.airing[id],
                        details,
                        sources.list.entries.mapValues { it.value.status },
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

    // With details already shown, or a summary, a failed refresh is a message and the page stays (section 7.2).
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
            if (opened != null || anime.observeDetails(id).first() != null) {
                detailsWait?.cancel()
                detailsWaitShown.value = false
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
