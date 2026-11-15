package io.github.kauri_off.kiroku.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.PATIENCE
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.chainWalk
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.edit.EditSheetState
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.stale
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// One title's page. The page is rebuilt with TitlePage.of whenever a source changes; the moment is held apart from
// it. Navigation events are the route's.
class TitleViewModel(
    private val id: AnimeId,
    private val anime: AnimeRepository,
    list: ListSource,
    airing: AiringRepository,
    session: SessionRepository,
    connection: Connection,
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
        // The title's own store could not be read.
        val storeFailure: DataError? = null,
        // The opener knew the title's page was read; the store's details say so once they are read.
        val pageRead: Boolean = false,
        val chain: Map<AnimeId, AnimeDetails> = emptyMap(),
    ) {
        // The store's answers are not known yet; the summary stands in for them in build.
        constructor(opened: TitleSummary) : this(
            null,
            null,
            ListRead(opened.knownEntries()),
            opened.knownAiring(),
            opened.session,
            pageRead = opened.pageRead,
        )
    }

    private class ListRead(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val failure: DataError? = null,
    )

    private class Stored(
        val anime: Anime? = null,
        val details: AnimeDetails? = null,
        // The stored pages of the chain of sequels.
        val chain: Map<AnimeId, AnimeDetails> = emptyMap(),
        val failure: DataError? = null,
    )

    private class Around(
        val list: ListRead,
        val airing: Map<AnimeId, Airing>,
    )

    private val moment = MutableStateFlow(TitleMoment(synopsisExpanded = saved[EXPANDED] ?: false))
    private val failure = MutableStateFlow<DataError?>(null)
    private val waitShown = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)
    private val chainWaitShown = MutableStateFlow(false)

    // The chain was walked since the page opened, or since a pull asked for it again.
    private var walked = false
    private var walking: Job? = null

    // A walk was stopped, or not started, because the page was not shown; it goes on when the page is shown again.
    private var cut = false
    private val retries = MutableStateFlow(0)
    private var load: Job? = null

    // A store that cannot be read fails the page, as a list that cannot be read does, since its values would be unknown.
    private val stored =
        combine(anime.observeAnime(id), anime.observeDetails(id), anime.chainPagesOf(id), ::Stored)
            .readAgainOnRetry(retries) { Stored(failure = it) }

    // Of the list and the airing, only the titles the page shows: a change to any other title rebuilds nothing.
    private val around =
        stored.withRead({ shownTitles(id, it.details, it.chain) }) { ids ->
            combine(
                list.entriesOf(ids).map { ListRead(it) }.readAgainOnRetry(retries) { ListRead(failure = it) },
                airing.airingOf(ids),
                ::Around,
            )
        }

    // Starts from what the opening screen showed, so the page needs no read for its first frame; build falls back to it.
    private val sources: StateFlow<Sources?> =
        combine(around, session.observeSession()) { (stored, around), session ->
            Sources(stored.anime, stored.details, around.list, around.airing, session, stored.failure, chain = stored.chain)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let(::Sources))

    val state: StateFlow<TitleState> =
        combine(sources.filterNotNull(), moment, failure, combine(waitShown, chainWaitShown, ::Pair), refreshing) {
            sources,
            moment,
            failure,
            (waitShown, chainWaitShown),
            refreshing,
            ->
            build(sources, moment, failure, waitShown, refreshing, chainWaitShown)
        }.stateIn(
            viewModelScope,
            WhileShown,
            sources.value?.let { build(it, moment.value, null, false, false, false) } ?: TitleState.Loading(null),
        )

    // A failure shown in place is tried again as a Retry would be; any other load runs as on opening.
    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            ::refreshIfStale,
            again = { refresh(if (failure.value != null) Asked.Retry else Asked.Shown) },
            showing = ::showingChanged,
        )

    // Collected while the page is shown; showing it refreshes stale details.
    val shown: StateFlow<Unit> get() = loads.shown

    // Only a shown page walks, so the page left for another one, another tab or a sign-out asks nothing more.
    private fun showingChanged(showing: Boolean) {
        if (!showing && walking?.isActive == true) {
            walking?.cancel()
            cut = true
        } else if (showing && cut && load?.isActive != true) {
            walkIfOwed()
        }
    }

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
        val named = (listOfNotNull(details) + sources.chain.values).flatMap { it.related }
        val anime = entry?.anime ?: named.firstOrNull { it.anime.id == id }?.anime ?: return null
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
        chainWaitShown: Boolean,
    ): TitleState {
        val entry = sources.list.entries[id]
        val anime = animeOf(sources)
        val details = sources.details
        val session = sources.session
        val readFailure = sources.list.failure ?: sources.storeFailure
        return when {
            readFailure != null -> TitleState.Failed(readFailure, session)
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
                        pageRead = details != null || sources.pageRead,
                        chainPages = sources.chain,
                    ),
                    session,
                    moment,
                    when {
                        failure != null -> DetailsWait.Failed(failure)
                        waitShown -> DetailsWait.Loading
                        else -> DetailsWait.Brief
                    },
                    refreshing,
                    chainLoading = chainWaitShown && details != null,
                )
        }
    }

    private fun shownPage(): TitlePage? = (state.value as? TitleState.Shown)?.page

    // A load that started while the store was read stands; a fresh page walks its chain if it has not.
    private suspend fun refreshIfStale() {
        val stale = anime.stale(id, opened?.anime, clock.instant())
        if (load?.isActive == true) return
        if (stale) refresh(Asked.Shown) else walkIfOwed()
    }

    // A wait the person asked for shows at once; one the page started only after a second (section 9.4).
    private fun refresh(asked: Asked) {
        load?.cancel()
        walking?.cancel()
        failure.value = null
        waitShown.value = asked == Asked.Retry
        refreshing.value = asked == Asked.Pull
        loads.started()
        load =
            viewModelScope.launch {
                val wait =
                    if (asked == Asked.Shown) {
                        launch {
                            delay(PATIENCE)
                            waitShown.value = true
                        }
                    } else {
                        null
                    }
                val result = anime.refresh(id)
                wait?.cancel()
                waitShown.value = false
                refreshing.value = false
                loads.ended((result as? Outcome.Failure)?.error)
                if (result !is Outcome.Failure) {
                    if (asked == Asked.Pull) walked = false
                    walkIfOwed()
                    return@launch
                }
                val shown =
                    sources.filterNotNull().first().let { it.list.failure == null && it.storeFailure == null && animeOf(it) != null }
                if (storedDetails() == null) failure.value = result.error
                // Section 7.2: a Retry fails where it was tapped; any other failure is also said on the first screen.
                if (shown && asked != Asked.Retry && result.error != DataError.SessionExpired) {
                    messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(result.error)))
                }
            }
    }

    // After the page's own load, never beside it; a page not shown owes the walk to its next show.
    private fun walkIfOwed() {
        if (walked || walking?.isActive == true) return
        cut = !loads.isShowing
        if (cut) return
        walking = viewModelScope.launch { walkChain() }
    }

    // Reads from MAL, one after another, each title of the chain whose stored page is missing or stale, and stops at
    // the first that fails, which leaves the page as it was; the chain is read from the store throughout.
    private suspend fun walkChain() {
        coroutineScope {
            val wait =
                launch {
                    delay(PATIENCE)
                    chainWaitShown.value = true
                }
            try {
                walked = walkedWhole()
            } finally {
                wait.cancel()
                chainWaitShown.value = false
            }
        }
    }

    private suspend fun walkedWhole(): Boolean {
        val done = mutableSetOf(id)
        while (true) {
            val pages =
                try {
                    anime.chainPagesOf(id).first()
                } catch (_: DataException) {
                    return false
                }
            if (id !in pages) return false
            val next = chainWalk(id, pages.mapValues { it.value.related }).firstOrNull { it !in done } ?: return true
            done += next
            if (next in pages && !anime.stale(next, null, clock.instant())) continue
            if (anime.refresh(next) is Outcome.Failure) return false
        }
    }

    private suspend fun storedDetails(): AnimeDetails? =
        try {
            anime.observeDetails(id).first()
        } catch (_: DataException) {
            null
        }

    private fun addToList() {
        val page = shownPage() ?: return
        if (page.entry != null) return
        messages.write(ListWrite.Save(page.anime, EditSheetState.newEntry)) {
            ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(id))
        }
    }

    // A change that completes the title (W7) says so, with an Undo of that change alone when it has one.
    private fun watched(write: (Anime) -> ListWrite) {
        val page = shownPage() ?: return
        if (page.entry == null) return
        messages.write(write(page.anime)) { change ->
            if (change.completed) ShellMessage(ShellMessage.Text.MovedToCompleted, change.undo?.let(ShellMessage.Action::Undo)) else null
        }
    }

    private enum class Asked { Shown, Retry, Pull }

    private companion object {
        val EXPANDED = notShown("synopsisExpanded")
    }
}
