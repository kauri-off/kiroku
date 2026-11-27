package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.orderManga
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.user.NamedListState
import io.github.kauri_off.kiroku.core.user.PublicMangaLists
import io.github.kauri_off.kiroku.core.user.ofUser
import io.github.kauri_off.kiroku.list.ListState
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class PublicMangaListViewModel(
    private val name: String,
    private val lists: PublicMangaLists,
    connection: Connection,
    titleLanguage: Flow<TitleLanguage>,
    sessions: Flow<Session>,
    private val saved: SavedStateHandle,
    background: CoroutineDispatcher,
    private val refreshFailed: (DataError) -> Unit,
) : ViewModel() {
    private val controls =
        mutableStateOf(
            MangaListControls(
                tab = saved.get<String>(TAB)?.let(::MangaReadingStatus) ?: MangaListControls().tab,
                filter = ListState.Typed(saved.get<String>(FILTER), saved.get<Int>(FILTER_SESSION) ?: 0),
            ),
        )
    private val read = MutableStateFlow<NamedListState<MangaListEntry>?>(null)
    private val listFilter = mangaTitleFilter()
    private var reading: Job? = null
    private var load: Job? = null
    private val loads = ShownLoads(viewModelScope, connection, whenShown = { observe() }, again = ::refresh)

    val shown get() = loads.shown
    val typed get() = controls.value.filter

    private val input = snapshotFlow { controls.value }
    private val sorts = input.map { it.sorts }.distinctUntilChanged()
    private val entries = read.map { if (it is NamedListState.Whole) it.copy(again = null) else it }.distinctUntilChanged()
    private val refreshing = read.map { it is NamedListState.Whole && it.again != null }.distinctUntilChanged()
    private val content =
        combine(entries, titleLanguage, sorts) { current, language, choices ->
            when (current) {
                null -> MangaListContent.Loading
                is NamedListState.Reading ->
                    if (current.entries.isEmpty()) {
                        MangaListContent.Loading
                    } else {
                        MangaListContent.Ready(current.entries, language, streaming = true)
                    }
                is NamedListState.None -> MangaListContent.Failed(current.error)
                is NamedListState.Whole -> {
                    val entries =
                        current.entries.groupBy(MangaListEntry::status).flatMap { (status, entries) ->
                            orderManga(entries, choices[status] ?: defaultMangaSort, language)
                        }
                    MangaListContent.Ready(entries, language)
                }
            }
        }

    val state =
        combine(content, input, refreshing, sessions) { content, controls, refreshing, session ->
            val ready = content as? MangaListContent.Ready
            val tab = controls.tab
            MangaListState(
                content = if (ready != null && refreshing) ready.copy(refreshing = true) else content,
                sessionExpired = session is Session.Expired,
                tab = if (ready == null || tab in ready.statuses) tab else MangaListControls().tab,
                sorts = controls.sorts,
                sortTab = controls.sortTab?.takeIf { ready != null && it in ready.statuses },
                filter = controls.filter,
                matches = listFilter.rows(ready, controls.filter.text, controls.filter.session),
            )
        }.flowOn(background)
            .stateIn(viewModelScope, SharingStarted.Lazily, MangaListState())

    private fun observe() {
        if (reading != null) return
        reading =
            viewModelScope.launch {
                lists.observeList(name).collect {
                    val before = read.value
                    read.value = it
                    if (load?.isActive != true) {
                        when (it) {
                            is NamedListState.None -> loads.ended(it.error, retryAfterHiddenFailure = !loads.isShowing)
                            is NamedListState.Whole -> if (before !is NamedListState.Whole) loads.loadedElsewhere()
                            is NamedListState.Reading -> Unit
                        }
                    }
                }
            }
    }

    fun onEvent(event: MangaListEvent) {
        when (event) {
            MangaListEvent.Refresh, MangaListEvent.Retry -> refresh()
            else -> {
                val next = controls.value.after(event, state.value.content as? MangaListContent.Ready)
                if (next == controls.value) return
                controls.value = next
                saved[TAB] = next.tab.code
                saved[FILTER] = next.filter.text
                saved[FILTER_SESSION] = next.filter.session
            }
        }
    }

    private fun refresh() {
        if (load?.isActive == true) return
        val held = read.value is NamedListState.Whole
        load =
            viewModelScope.launch {
                loads.started()
                val error = (lists.refreshList(name) as? Outcome.Failure)?.error
                loads.ended(error, retryAfterHiddenFailure = !loads.isShowing)
                if (held && error != null && !error.ofUser) refreshFailed(error)
            }
    }

    private companion object {
        val TAB = notShown("publicMangaTab")
        val FILTER = notShown("publicMangaFilter")
        val FILTER_SESSION = notShown("publicMangaFilterSession")
    }
}
