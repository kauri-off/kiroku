package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.mangaListSorts
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.listStale
import io.github.kauri_off.kiroku.list.TitleFilter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

internal class MangaListViewModel(
    private val lists: MangaListReader,
    private val clock: Clock,
    connection: Connection,
    private val sessions: Flow<Session>,
    language: Flow<TitleLanguage>,
    private val saved: SavedStateHandle = SavedStateHandle(),
    edits: Flow<MangaEditingState> = flowOf(MangaEditingState()),
    private val onRefreshed: (MangaListSnapshot) -> Unit = {},
    private val onRefreshFailed: (DataError) -> Unit,
) : ViewModel() {
    private data class Page(
        val snapshot: MangaListSnapshot? = null,
        val error: DataError? = null,
        val refreshing: Boolean = false,
        val orderPass: Int = 0,
    )

    private val controls = mutableStateOf(saved.restoreMangaControls())
    private var ordering = MangaListOrdering()
    private var listFilter = mangaTitleFilter()
    private val page = MutableStateFlow(Page())
    private val session = MutableStateFlow<Session?>(null)
    private var load: Job? = null
    private var watch: Job? = null
    private var refreshPending = false
    private var recoveryPending = false
    private var coveredDuringRefresh = false
    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = {
                watchSession()
                read()
            },
            again = {
                recoveryPending = true
                read()
            },
            shownAgain = { read() },
            hidden = { coveredDuringRefresh = true },
            trackOutagesWhileShown = true,
        )

    val shown = loads.shown
    val state =
        combine(page, session, language, snapshotFlow { controls.value }, edits, ::present)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), MangaListState())

    private fun present(
        current: Page,
        account: Session?,
        titles: TitleLanguage,
        saved: MangaListControls,
        edits: MangaEditingState,
    ): MangaListState {
        val owner = account?.owner
        val active = saved.takeIf { it.owner == owner } ?: MangaListControls(owner)
        val snapshot = current.snapshot?.takeIf { it.owner == owner }
        val editing = edits.takeIf { it.owner == owner && it.snapshot?.owner == owner }
        val confirmed = editing?.snapshot ?: snapshot
        val error = current.error
        val content =
            when {
                account == Session.SignedOut -> MangaListContent.SignedOut
                snapshot != null && confirmed != null -> {
                    val displayed = confirmed.entries.map { editing?.displayed(it) ?: it }
                    MangaListContent.Ready(
                        entries =
                            ordering.entries(
                                displayed,
                                titles,
                                active.sorts,
                                current.orderPass,
                                snapshot.entries,
                                editing,
                            ),
                        language = titles,
                        refreshing = current.refreshing,
                        canEdit = editing?.signedIn == true && account is Session.SignedIn,
                        confirmedStatuses = confirmed.entries.mapTo(mutableSetOf(), MangaListEntry::status),
                    )
                }
                error != null -> MangaListContent.Failed(error)
                else -> MangaListContent.Loading
            }
        val ready = content as? MangaListContent.Ready
        val filter = active.filter
        return MangaListState(
            owner = owner,
            content = content,
            sessionExpired = account is Session.Expired,
            tab = active.tab,
            sorts = active.sorts,
            sortTab = active.sortTab,
            filter = filter,
            matches = listFilter.rows(ready, filter.text, filter.session),
        )
    }

    fun forDisplay(state: MangaListState): MangaListState =
        if (state.owner == controls.value.owner) state.copy(typed = controls.value.filter) else state

    fun refresh() = read(force = true)

    fun control(
        owner: SignInId,
        event: MangaListEvent,
    ) {
        val current = controls.value
        val displayed = state.value
        val content = displayed.content as? MangaListContent.Ready
        if (session.value?.owner != owner || current.owner != owner || displayed.owner != owner) return
        val tab = current.sortTab
        val choice = tab?.let(current.sorts::get) ?: defaultMangaSort
        val next =
            when (event) {
                is MangaListEvent.SelectTab ->
                    if (content != null && event.status in content.statuses) current.copy(tab = event.status) else current
                MangaListEvent.StartFilter -> current.copy(filter = current.filter.withText(""), sortTab = null)
                is MangaListEvent.FilterChanged ->
                    if (current.filter.text != null) current.copy(filter = current.filter.withText(event.text)) else current
                MangaListEvent.EndFilter -> current.copy(filter = current.filter.withText(null))
                is MangaListEvent.OpenSort ->
                    if (content != null && event.tab in content.statuses && current.filter.text == null) {
                        current.copy(sortTab = event.tab)
                    } else {
                        current
                    }
                MangaListEvent.DismissSort -> current.copy(sortTab = null)
                is MangaListEvent.SelectSort ->
                    if (tab != null && event.sort in mangaListSorts && event.sort != choice.sort) {
                        current.copy(sorts = current.sorts + (tab to TabSort(event.sort)))
                    } else {
                        current
                    }
                is MangaListEvent.SelectDirection ->
                    if (tab != null && event.direction != choice.direction) {
                        current.copy(sorts = current.sorts + (tab to choice.copy(direction = event.direction)))
                    } else {
                        current
                    }
                else -> current
            }
        if (next != current) {
            controls.value = next
            saved.saveMangaControls(next)
        }
    }

    private fun watchSession() {
        if (watch != null) return
        watch =
            viewModelScope.launch {
                sessions.collect { next ->
                    val previous = session.value
                    if (previous?.owner != next.owner) {
                        load?.cancel()
                        load = null
                        refreshPending = false
                        recoveryPending = false
                        loads.started()
                        page.value = Page()
                    }
                    if (controls.value.owner != next.owner) {
                        ordering = MangaListOrdering()
                        listFilter = mangaTitleFilter()
                        controls.value = MangaListControls(next.owner)
                        saved.saveMangaControls(controls.value)
                    }
                    session.value = next
                    if (previous != next) read()
                }
            }
    }

    private fun read(force: Boolean = false) {
        refreshPending = refreshPending || force
        if (!loads.isShowing || load?.isActive == true) return
        val owner = session.value?.owner ?: return
        load =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val stored = lists.stored(owner)
                ensureActive()
                val snapshot =
                    when (stored) {
                        is Outcome.Success -> stored.value?.takeIf { it.owner == owner }
                        is Outcome.Failure -> page.value.snapshot?.takeIf { it.owner == owner && stored.error != DataError.ListEnded }
                    }
                if (stored is Outcome.Failure && stored.error == DataError.ListEnded) {
                    page.value = Page(error = stored.error)
                    return@launch
                }
                page.value = page.value.copy(snapshot = snapshot, error = null, refreshing = false)
                if (!loads.isShowing) return@launch
                if (session.value is Session.Expired) {
                    if (snapshot == null) page.value = Page(error = DataError.SessionExpired)
                    return@launch
                }
                val forced = refreshPending
                val recovering = recoveryPending
                if (!forced && !recovering && !listStale(snapshot?.syncedAt, clock.instant())) return@launch
                refreshPending = false
                recoveryPending = false
                page.value = page.value.copy(snapshot = snapshot, refreshing = true)
                coveredDuringRefresh = false
                val result = lists.refresh(owner) { loads.isShowing }
                ensureActive()
                if (sessions.first().owner != owner || session.value?.owner != owner) return@launch
                load = null
                if (result == null) {
                    refreshPending = refreshPending || forced
                    recoveryPending = recoveryPending || recovering
                    page.value = page.value.copy(snapshot = snapshot, error = null, refreshing = false)
                    read()
                    return@launch
                }
                refreshPending = false
                when (result) {
                    is Outcome.Success -> {
                        recoveryPending = false
                        val updated = result.value.takeIf { it.owner == owner }
                        page.value =
                            if (updated != null) {
                                Page(updated, orderPass = page.value.orderPass + if (forced) 1 else 0)
                            } else {
                                Page(error = DataError.ListEnded)
                            }
                        if (updated != null) onRefreshed(updated)
                        loads.ended(null)
                    }
                    is Outcome.Failure -> {
                        if (result.error != DataError.NoConnection) recoveryPending = false
                        val kept = snapshot.takeUnless { result.error == DataError.ListEnded }
                        page.value = Page(kept, result.error, orderPass = page.value.orderPass)
                        if (kept != null) onRefreshFailed(result.error)
                        loads.ended(
                            result.error.takeUnless { recoveryPending },
                            retryAfterHiddenFailure = !recovering && (coveredDuringRefresh || !loads.isShowing),
                        )
                        if (recoveryPending) read()
                    }
                }
            }
        load?.start()
    }
}

internal fun mangaTitleFilter() =
    TitleFilter(
        rows = { content: MangaListContent.Ready -> content.statuses.flatMap { content.pages[it].orEmpty() } },
        id = MangaListEntry::id,
        title = { entry, content -> entry.titles.preferred(content.language) },
    )
