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
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.chainWalk
import io.github.kauri_off.kiroku.core.repository.AdultSearchChoice
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.edit.EditSheetState
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.recommendationsNeedRefresh
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.stale
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val airing: AiringRepository,
    session: SessionRepository,
    connection: Connection,
    private val clock: Clock,
    titleLanguage: Flow<TitleLanguage>,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
    private val opened: TitleSummary? = null,
    private val adultSearchChoice: Flow<AdultSearchChoice> = flowOf(AdultSearchChoice()),
    private val owner: SignInId? = null,
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

    // Section 6.2: the page's airing is a part of it, read again at its next pull or Retry when it could not be read.
    private val airingReads = MutableStateFlow(0)
    private var load: Job? = null
    private var replaceObsolete = false
    private var deferredOwnerRetry: Asked? = null
    private var freshnessOwed = false
    private var checkingFreshness = false

    // The page's own airing ask, apart from its load: AniList's pace never holds the page's indicator or its walk.
    private var airingAsk: Job? = null

    // A store that cannot be read fails the page, as a list that cannot be read does, since its values would be unknown.
    private val stored =
        combine(anime.observeAnime(id), anime.observeDetails(id), anime.chainPagesOf(id), ::Stored)
            .readAgainOnRetry(retries) { Stored(failure = it) }

    // Of the list and the airing, only the titles the page shows, the airing with the page's own title once known (A12).
    private val around =
        stored
            .withRead({ shownTitles(id, it.details, it.chain) }) { ids ->
                list.entriesOf(ids, owner).map { ListRead(it) }.readAgainOnRetry(retries) { ListRead(failure = it) }
            }.withRead({ (stored, _) -> shownTitles(id, stored.details, stored.chain) to (stored.anime ?: opened?.anime) }) { (ids, page) ->
                airing.airingOf(ids, page).readAgainOnRetry(airingReads) { emptyMap() }
            }.map { (read, airing) -> read.first to Around(read.second, airing) }

    // Starts from what the opening screen showed, so the page needs no read for its first frame; build falls back to it.
    private val sources: StateFlow<Sources?> =
        combine(around, session.observeSession()) { (stored, around), session ->
            Sources(stored.anime, stored.details, around.list, around.airing, session, stored.failure, chain = stored.chain)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let(::Sources))

    private var previousFilter: AdultSearchChoice? = null

    val state: StateFlow<TitleState> =
        combine(
            sources.filterNotNull(),
            moment,
            failure,
            combine(waitShown, chainWaitShown, ::Pair),
            combine(refreshing, titleLanguage, adultSearchChoice.onEach(::filterChanged), ::Triple),
        ) {
            sources,
            moment,
            failure,
            (waitShown, chainWaitShown),
            (refreshing, language, adult),
            ->
            build(sources, moment, failure, waitShown, refreshing, chainWaitShown, language, adult.enabled)
        }.stateIn(
            viewModelScope,
            WhileShown,
            sources.value?.let { build(it, moment.value, null, false, false, false, TitleLanguage.Romaji, false) }
                ?: TitleState.Loading(null),
        )

    // A failure shown in place is tried again as a Retry would be; any other load runs as on opening.
    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            ::refreshIfStale,
            again = { refresh(if (failure.value != null) Asked.Retry else Asked.Shown) },
            hidden = ::hidden,
            shownAgain = ::shownAgain,
        )

    private fun filterChanged(choice: AdultSearchChoice) {
        val previous = previousFilter
        previousFilter = choice
        if (previous != null && previous != choice) {
            failure.value = null
            if (loads.isShowing) refresh(Asked.Filter)
        }
    }

    // Collected while the page is shown; showing it refreshes stale details.
    val shown: StateFlow<Unit> get() = loads.shown

    // Only a shown page walks, so the page left for another one, another tab or a sign-out asks nothing more.
    private fun hidden() {
        if (walking?.isActive != true) return
        walking?.cancel()
        cut = true
    }

    // Shown again past the stop timeout, the page goes on through refreshIfStale, after its own page if that is stale.
    private fun shownAgain() {
        if (checkingFreshness) return
        if (freshnessOwed) {
            viewModelScope.launch { refreshIfStale() }
            return
        }
        if (cut && deferredOwnerRetry == null && load?.isActive != true) walkIfOwed()
        viewModelScope.launch {
            val needed = replaceObsolete || anime.recommendationsNeedRefresh(id, adultSearchChoice.first().enabled)
            if (!loads.isShowing || load?.isActive == true || resumeOwnerRetry()) return@launch
            if (needed) refresh(Asked.Filter)
        }
    }

    private fun resumeOwnerRetry(): Boolean {
        val asked = deferredOwnerRetry ?: return false
        refresh(asked, ownerRetry = true)
        return true
    }

    private fun deferOwnerRetry(asked: Asked) {
        waitShown.value = false
        refreshing.value = false
        deferredOwnerRetry = asked
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
            is TitleEvent.SetWatched -> watched { ListWrite.SetWatched(it, event.watched, madeIn = owner) }
            is TitleEvent.StepWatched -> watched { ListWrite.StepWatched(it, event.by, madeIn = owner) }
            TitleEvent.Retry -> {
                retries.update { it + 1 }
                airingReads.update { it + 1 }
                refresh(Asked.Retry)
            }
            TitleEvent.Refresh -> {
                airingReads.update { it + 1 }
                refresh(Asked.Pull)
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
        waitShown: Boolean,
        refreshing: Boolean,
        chainWaitShown: Boolean,
        language: TitleLanguage,
        adult: Boolean,
    ): TitleState {
        val entry = sources.list.entries[id]
        val anime = animeOf(sources)
        val details =
            sources.details?.let { page ->
                if (page.recommendationsIncludeAdult == adult) page else page.copy(recommendations = emptyList())
            }
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
                        language,
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
        if (checkingFreshness) return
        checkingFreshness = true
        try {
            val stale = anime.stale(id, opened?.anime, clock.instant())
            val otherRecommendations =
                !stale && (replaceObsolete || anime.recommendationsNeedRefresh(id, adultSearchChoice.first().enabled))
            if (load?.isActive == true) return
            freshnessOwed = !loads.isShowing
            if (freshnessOwed || resumeOwnerRetry()) return
            if (stale) {
                refresh(Asked.Shown)
            } else {
                if (otherRecommendations) refresh(Asked.Filter)
                walkIfOwed()
                viewModelScope.launch { (knownTitle() ?: storedTitle())?.let { askAiring(it, Asked.Shown) } }
            }
        } finally {
            checkingFreshness = false
        }
    }

    // The page's title as known now, for what its airing is asked for with.
    private fun knownTitle(): Anime? = sources.value?.let(::animeOf) ?: opened?.anime

    private suspend fun storedTitle(): Anime? =
        try {
            anime.observeAnime(id).first()
        } catch (_: DataException) {
            null
        }

    // A12: a pull asks for the page's airing, a show only when it is stale; what comes of it is never a message (section 2.3).
    private fun askAiring(
        page: Anime,
        asked: Asked,
    ) {
        airingAsk?.cancel()
        airingAsk = viewModelScope.launch { if (asked == Asked.Pull) airing.refresh(page) else airing.refreshStale(page) }
    }

    // A wait the person asked for shows at once; one the page started only after a second.
    private fun refresh(
        asked: Asked,
        ownerRetry: Boolean = false,
    ) {
        load?.cancel()
        replaceObsolete = false
        deferredOwnerRetry = null
        freshnessOwed = false
        if (asked != Asked.Filter) walking?.cancel()
        failure.value = null
        waitShown.value = asked == Asked.Retry
        refreshing.value = asked == Asked.Pull
        loads.started()
        load =
            viewModelScope.launch {
                // The airing beside the details, or once they are stored when the title is not known before.
                val known = knownTitle()
                if (known != null && asked != Asked.Filter) askAiring(known, asked)
                if (loadDetails(asked, ownerRetry) && known == null) storedTitle()?.let { askAiring(it, asked) }
            }
    }

    // Whether the details were loaded.
    private suspend fun loadDetails(
        asked: Asked,
        ownerRetry: Boolean,
    ): Boolean =
        coroutineScope {
            val wait =
                if (asked == Asked.Shown) {
                    launch {
                        delay(PATIENCE)
                        waitShown.value = true
                    }
                } else {
                    null
                }
            val choice = adultSearchChoice.first()
            if (ownerRetry && !loads.isShowing) {
                wait?.cancel()
                deferOwnerRetry(asked)
                return@coroutineScope false
            }
            val result = anime.refresh(id)
            wait?.cancel()
            val error = (result as? Outcome.Failure)?.error
            if (error == DataError.ListEnded && owner == null && !ownerRetry) {
                currentCoroutineContext().ensureActive()
                if (loads.isShowing) return@coroutineScope loadDetails(asked, ownerRetry = true)
                deferOwnerRetry(asked)
                return@coroutineScope false
            }
            val obsoleteBeforeRead = adultSearchChoice.first() != choice
            val shown =
                error != null &&
                    !obsoleteBeforeRead &&
                    sources.filterNotNull().first().let { it.list.failure == null && it.storeFailure == null && animeOf(it) != null }
            val missingDetails = error != null && !obsoleteBeforeRead && storedDetails() == null
            val obsolete = obsoleteBeforeRead || (error != null && adultSearchChoice.first() != choice)
            currentCoroutineContext().ensureActive()
            if (obsolete) {
                waitShown.value = false
                refreshing.value = false
                if (error != DataError.ListEnded) {
                    if (loads.isShowing) refresh(Asked.Filter) else replaceObsolete = true
                }
                return@coroutineScope false
            }
            waitShown.value = false
            refreshing.value = false
            loads.ended(error)
            if (error == null) {
                if (asked == Asked.Pull) walked = false
                walkIfOwed()
                return@coroutineScope true
            }
            if (missingDetails) failure.value = error
            // Section 7.2: a Retry fails where it was tapped; any other failure is also said on the first screen.
            if (shown && asked != Asked.Retry && error != DataError.SessionExpired) {
                messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(error)))
            }
            false
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
        messages.write(ListWrite.Save(page.anime, EditSheetState.newEntry, madeIn = owner)) {
            ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(id, owner))
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

    private enum class Asked { Shown, Retry, Pull, Filter }

    private companion object {
        val EXPANDED = notShown("synopsisExpanded")
    }
}
