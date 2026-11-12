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
    private val waitShown = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)
    private val retries = MutableStateFlow(0)
    private var load: Job? = null
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
        combine(sources.filterNotNull(), moment, failure, waitShown, refreshing, ::build)
            .onStart { if (!refreshed) refresh(Asked.Opening) }
            .stateIn(
                viewModelScope,
                WhileShown,
                sources.value?.let { build(it, moment.value, null, false, false) } ?: TitleState.Loading(null),
            )

    // What the page shows of [id], itself or a related title, for the screen it opens.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val sources = sources.value ?: return null
        if (sources.list.failure != null) return null
        val entry = sources.list.entries[id]
        val details = sources.details
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

    fun onEvent(event: TitleEvent) {
        when (event) {
            TitleEvent.AddToList -> addToList()
            is TitleEvent.SetWatched -> watched { ListWrite.SetWatched(it, event.watched) }
            is TitleEvent.StepWatched -> watched { ListWrite.StepWatched(it, event.by) }
            TitleEvent.Retry -> {
                retries.update { it + 1 }
                refresh(Asked.Retry)
            }
            TitleEvent.Refresh -> refresh(Asked.Pull)
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
        waitShown: Boolean,
        refreshing: Boolean,
    ): TitleState {
        val entry = sources.list.entries[id]
        val anime = animeOf(sources)
        val details = sources.details
        val session = sources.session
        val listFailure = sources.list.failure
        return when {
            listFailure != null -> TitleState.Failed(listFailure, session)
            anime == null -> failure?.let { TitleState.Failed(it, session) } ?: TitleState.Loading(session)
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
                    when {
                        failure != null -> DetailsWait.Failed(failure)
                        waitShown -> DetailsWait.Loading
                        else -> DetailsWait.Brief
                    },
                    refreshing,
                )
        }
    }

    private fun shownPage(): TitlePage? = (state.value as? TitleState.Shown)?.page

    // A wait the person asked for shows at once; the one on opening only after a second (section 9.4).
    private fun refresh(asked: Asked) {
        refreshed = true
        load?.cancel()
        failure.value = null
        waitShown.value = asked == Asked.Retry
        refreshing.value = asked == Asked.Pull
        load =
            viewModelScope.launch {
                val wait =
                    if (asked == Asked.Opening) {
                        launch {
                            delay(DETAILS_PATIENCE)
                            waitShown.value = true
                        }
                    } else {
                        null
                    }
                val result = anime.refresh(id)
                wait?.cancel()
                waitShown.value = false
                refreshing.value = false
                if (result !is Outcome.Failure) return@launch
                val shown = sources.filterNotNull().first().let { it.list.failure == null && animeOf(it) != null }
                if (anime.observeDetails(id).first() == null) failure.value = result.error
                // Section 7.2: a Retry fails where it was tapped; any other failure is also said on the first screen.
                if (shown && asked != Asked.Retry && result.error != DataError.SessionExpired) {
                    messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(result.error)))
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

    private enum class Asked { Opening, Retry, Pull }

    private companion object {
        val EXPANDED = notShown("synopsisExpanded")

        // Within a second the flow of use is not interrupted, so a shorter wait shows no indicator.
        val DETAILS_PATIENCE = 1.seconds
    }
}
