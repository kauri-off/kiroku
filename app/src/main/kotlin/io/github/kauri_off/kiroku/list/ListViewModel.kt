package io.github.kauri_off.kiroku.list

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
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

// The user's own list. Every event that changes what the screen shows is applied in onEvent itself; navigation
// events are the route's.
class ListViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var entries by mutableStateOf<Map<AnimeId, ListEntry>>(emptyMap())
    private var airing by mutableStateOf<Map<AnimeId, Airing>>(emptyMap())
    private var session by mutableStateOf<Session?>(null)

    // Each tab's ids in the order last computed, with titles that joined since appended; null until the first order.
    private var orders by mutableStateOf<Map<ListStatus, List<AnimeId>>?>(null)
    private var orderings by mutableStateOf(ListStatus.entries.associateWith { 0 })
    private var failure by mutableStateOf<ListFailure?>(null)
    private var firstSyncDone = false
    private var entriesLoaded = false

    private var tab by mutableStateOf(saved.get<String>(TAB)?.let(ListStatus::valueOf) ?: ListStatus.Watching)
    private var sorts by mutableStateOf(
        ListStatus.entries.associateWith { status ->
            saved.get<String>(sortKey(status))?.let(ListSort::valueOf) ?: defaultSort(status, withAiring = true)
        },
    )
    private var filter by mutableStateOf(saved.get<String>(FILTER))
    private var sortSheetOpen by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var message by mutableStateOf<ListMessage?>(null)
    private var serial = 0L
    private val undo = mutableMapOf<Long, ListWrite>()

    val state: State<ListState> = derivedStateOf { build() }

    init {
        viewModelScope.launch {
            combine(graph.list.observeEntries(), graph.airing.observeAiring(), ::Pair).collect { (list, schedule) ->
                airing = schedule
                entries = list.associateBy { it.anime.id }
                entriesLoaded = true
                when {
                    orders != null -> appendJoined()
                    list.isNotEmpty() || firstSyncDone -> orderAll()
                }
            }
        }
        viewModelScope.launch { graph.session.observeSession().collect { session = it } }
        viewModelScope.launch { sync(fromUser = false) }
    }

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SelectTab -> {
                tab = event.status
                saved[TAB] = event.status.name
            }
            is ListEvent.PlusOne -> plusOne(event.id)
            ListEvent.StartFilter -> filterBy("")
            is ListEvent.FilterChanged -> filterBy(event.text)
            ListEvent.EndFilter -> filterBy(null)
            ListEvent.OpenSort -> sortSheetOpen = true
            ListEvent.DismissSort -> sortSheetOpen = false
            is ListEvent.SelectSort -> selectSort(event.sort)
            ListEvent.Refresh -> viewModelScope.launch { sync(fromUser = true) }
            ListEvent.Retry -> viewModelScope.launch { sync(fromUser = false) }
            is ListEvent.Undo -> {
                undo.remove(event.message.serial)?.let { write -> viewModelScope.launch { graph.list.write(write) } }
                clear(event.message)
            }
            is ListEvent.MessageDismissed -> {
                undo.remove(event.message.serial)
                clear(event.message)
            }
            is ListEvent.Open, is ListEvent.Edit, ListEvent.Search, ListEvent.SignIn, ListEvent.Back -> Unit
        }
    }

    private fun build(): ListState {
        val orders = orders
        val content =
            when {
                session == Session.SignedOut -> ListContent.SignedOut
                orders == null -> failure?.let(ListContent::Failed) ?: ListContent.Loading
                else -> ListContent.Tabs(ListStatus.entries.associateWith { page(it, orders.getValue(it)) })
            }
        return ListState(
            owner = ListOwner.Me,
            content = content,
            tab = tab,
            sortSheetOpen = sortSheetOpen,
            filter = filter,
            refreshing = refreshing,
            sessionExpired = session == Session.Expired,
            message = message,
            now = graph.clock.instant(),
        )
    }

    private fun page(
        status: ListStatus,
        ids: List<AnimeId>,
    ): ListPage {
        val today = today()
        val text = filter
        val rows =
            ids
                .mapNotNull { id -> entries[id]?.takeIf { it.status == status } }
                .map { listRow(it, airing[it.anime.id], today, graph.titleLanguage) }
                .filter { text.isNullOrEmpty() || it.title.contains(text, ignoreCase = true) }
        return ListPage(sorts.getValue(status), sortsFor(status, withAiring = true), rows, orderings.getValue(status))
    }

    private fun today(): LocalDate = LocalDate.now(graph.clock)

    private fun ordered(
        status: ListStatus,
        sort: ListSort,
    ): List<AnimeId> =
        order(entries.values.filter { it.status == status }, sort, airing::get, today()) {
            it.anime.titles.preferred(graph.titleLanguage)
        }.map { it.anime.id }

    private fun orderAll() {
        val first = orders == null
        orders = ListStatus.entries.associateWith { ordered(it, sorts.getValue(it)) }
        if (!first) orderings = orderings.mapValues { it.value + 1 }
    }

    // A title that joined a tab since its order was computed goes at its end; one that left keeps its place for Undo.
    private fun appendJoined() {
        val current = orders ?: return
        val next =
            current.mapValues { (status, ids) ->
                val known = ids.toSet()
                ids + entries.values.filter { it.status == status && it.anime.id !in known }.map { it.anime.id }
            }
        if (next != current) orders = next
    }

    private fun selectSort(sort: ListSort) {
        sortSheetOpen = false
        if (sorts.getValue(tab) == sort) return
        sorts = sorts + (tab to sort)
        saved[sortKey(tab)] = sort.name
        orders?.let { orders = it + (tab to ordered(tab, sort)) }
        orderings = orderings + (tab to orderings.getValue(tab) + 1)
    }

    private fun filterBy(text: String?) {
        filter = text
        saved[FILTER] = text
    }

    private fun clear(shown: ListMessage) {
        if (message == shown) message = null
    }

    private fun plusOne(id: AnimeId) {
        viewModelScope.launch {
            val before = graph.list.observeEntry(id).first() ?: return@launch
            graph.list.write(ListWrite.MarkNextWatched(before.anime))
            val after = graph.list.observeEntry(id).first() ?: return@launch
            if (after.values == before.values) return@launch
            val kind =
                if (after.status == ListStatus.Completed && before.status != ListStatus.Completed) {
                    ListMessage.Kind.MovedToCompleted
                } else {
                    ListMessage.Kind.EpisodeWatched(after.values.watchedEpisodes)
                }
            post(kind, undo = ListWrite.Save(before.anime, before.values))
        }
    }

    private fun post(
        kind: ListMessage.Kind,
        undo: ListWrite? = null,
    ) {
        val next = ListMessage(kind, ++serial)
        undo?.let { this.undo[next.serial] = it }
        message = next
    }

    // The first sync after start, a retry and pull-to-refresh; only the last two show the indicator or reorder.
    private suspend fun sync(fromUser: Boolean) {
        if (fromUser) refreshing = true
        failure = null
        val result = graph.list.sync()
        refreshing = false
        firstSyncDone = true
        when (result) {
            is Outcome.Success -> if (entriesLoaded && (fromUser || orders == null)) orderAll()
            is Outcome.Failure -> {
                val cause = result.error.toListFailure()
                when {
                    orders != null -> post(ListMessage.Kind.RefreshFailed(cause))
                    entriesLoaded && entries.isNotEmpty() -> orderAll()
                    else -> failure = cause
                }
            }
        }
    }

    private companion object {
        val TAB = notShown("tab")
        val FILTER = notShown("filter")

        fun sortKey(status: ListStatus) = notShown("sort.${status.name}")
    }
}

internal fun DataError.toListFailure(): ListFailure =
    when (this) {
        DataError.NoConnection -> ListFailure.NoConnection
        DataError.Refused -> ListFailure.Refused
        else -> ListFailure.Other
    }
