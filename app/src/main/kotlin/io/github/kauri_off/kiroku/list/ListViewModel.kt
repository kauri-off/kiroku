package io.github.kauri_off.kiroku.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

// The user's own list. Its orders are fixed at moments (the first load, Refresh, a new sort), so the list it last read
// and the orders are kept here; the pipeline that reads the list updates them while the List is shown.
class ListViewModel(
    private val list: ListRepository,
    private val airing: AiringRepository,
    session: SessionRepository,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
) : ViewModel() {
    private data class Model(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val airing: Map<AnimeId, Airing> = emptyMap(),
        // Each tab's ids in the order last computed, with titles that joined since appended; null until the first order.
        val orders: Map<ListStatus, List<AnimeId>>? = null,
        val orderings: Map<ListStatus, Int> = ListStatus.entries.associateWith { 0 },
        val failure: ListFailure? = null,
        val tab: ListStatus,
        val sorts: Map<ListStatus, ListSort>,
        val filter: String?,
        val sortSheetOpen: Boolean = false,
        val refreshing: Boolean = false,
        val session: Session? = null,
    )

    private val model =
        MutableStateFlow(
            Model(
                tab = saved.get<String>(TAB)?.let(ListStatus::valueOf) ?: ListStatus.Watching,
                sorts =
                    ListStatus.entries.associateWith { status ->
                        saved.get<String>(sortKey(status))?.let(ListSort::valueOf) ?: defaultSort(status, withAiring = true)
                    },
                filter = saved.get<String>(FILTER),
            ),
        )
    private val retries = MutableStateFlow(0)
    private var firstSyncStarted = false
    private var firstSyncDone = false
    private var entriesLoaded = false

    // Mapped from the model alone, with no combine, so a filter keystroke reaches the state before onEvent returns.
    val state: StateFlow<ListState> =
        flow {
            coroutineScope {
                launch { reading().collect() }
                launch { session.observeSession().collect { session -> model.update { it.copy(session = session) } } }
                emitAll(model.map(::build))
            }
        }.onStart { firstSync() }
            .stateIn(viewModelScope, WhileShown, build(model.value))

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SelectTab -> {
                model.update { it.copy(tab = event.status) }
                saved[TAB] = event.status.name
            }
            is ListEvent.PlusOne -> plusOne(event.id)
            ListEvent.StartFilter -> filterBy("")
            is ListEvent.FilterChanged -> filterBy(event.text)
            ListEvent.EndFilter -> filterBy(null)
            ListEvent.OpenSort -> model.update { it.copy(sortSheetOpen = true) }
            ListEvent.DismissSort -> model.update { it.copy(sortSheetOpen = false) }
            is ListEvent.SelectSort -> selectSort(event.sort)
            ListEvent.Refresh -> viewModelScope.launch { sync(fromUser = true) }
            ListEvent.Retry -> {
                retries.update { it + 1 }
                viewModelScope.launch { sync(fromUser = false) }
            }
            is ListEvent.Open, is ListEvent.Edit, ListEvent.Search, ListEvent.SignIn, ListEvent.Back -> Unit
        }
    }

    private fun firstSync() {
        if (firstSyncStarted) return
        firstSyncStarted = true
        viewModelScope.launch { sync(fromUser = false) }
    }

    private fun reading(): Flow<Unit> =
        combine(list.observeEntries(), airing.observeAiring(), ::received)
            .onStart { emit(Unit) }
            .readAgainOnRetry(retries, ::unreadable)

    private fun received(
        list: List<ListEntry>,
        schedule: Map<AnimeId, Airing>,
    ) {
        entriesLoaded = true
        model.update { model ->
            val read = model.copy(airing = schedule, entries = list.associateBy { it.anime.id })
            when {
                read.orders != null -> read.withJoinedAppended()
                list.isNotEmpty() || firstSyncDone -> read.orderedAll()
                else -> read
            }
        }
    }

    // A list that cannot be read shows the error state, whatever it showed before.
    private fun unreadable(error: DataError) {
        entriesLoaded = false
        model.update { it.copy(orders = null, entries = emptyMap(), failure = error.toListFailure()) }
    }

    private fun build(model: Model): ListState {
        val session = model.session
        val orders = model.orders
        val content =
            when {
                session == Session.SignedOut -> ListContent.SignedOut
                orders == null -> model.failure?.let(ListContent::Failed) ?: ListContent.Loading
                else -> ListContent.Tabs(ListStatus.entries.associateWith { page(model, it, orders.getValue(it)) })
            }
        return ListState(
            owner = ListOwner.Me,
            content = content,
            tab = model.tab,
            sortSheetOpen = model.sortSheetOpen,
            filter = model.filter,
            refreshing = model.refreshing,
            sessionExpired = session == Session.Expired,
            now = clock.instant(),
        )
    }

    private fun page(
        model: Model,
        status: ListStatus,
        ids: List<AnimeId>,
    ): ListPage {
        val today = today()
        val text = model.filter
        val rows =
            ids
                .mapNotNull { id -> model.entries[id]?.takeIf { it.status == status } }
                .map { listRow(it, model.airing[it.anime.id], today, titleLanguage) }
                .filter { text.isNullOrEmpty() || it.title.contains(text, ignoreCase = true) }
        return ListPage(model.sorts.getValue(status), sortsFor(status, withAiring = true), rows, model.orderings.getValue(status))
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun Model.ordered(
        status: ListStatus,
        sort: ListSort,
    ): List<AnimeId> =
        order(entries.values.filter { it.status == status }, sort, airing::get, today()) {
            it.anime.titles.preferred(titleLanguage)
        }.map { it.anime.id }

    private fun Model.orderedAll(): Model =
        copy(
            orders = ListStatus.entries.associateWith { ordered(it, sorts.getValue(it)) },
            orderings = if (orders == null) orderings else orderings.mapValues { it.value + 1 },
        )

    // A title that joined a tab since its order was computed goes at its end; one that left keeps its place for Undo.
    private fun Model.withJoinedAppended(): Model {
        val current = orders ?: return this
        val next =
            current.mapValues { (status, ids) ->
                val known = ids.toSet()
                ids + entries.values.filter { it.status == status && it.anime.id !in known }.map { it.anime.id }
            }
        return if (next != current) copy(orders = next) else this
    }

    private fun selectSort(sort: ListSort) {
        val tab = model.value.tab
        if (model.value.sorts.getValue(tab) == sort) {
            model.update { it.copy(sortSheetOpen = false) }
            return
        }
        saved[sortKey(tab)] = sort.name
        model.update { model ->
            model.copy(
                sortSheetOpen = false,
                sorts = model.sorts + (tab to sort),
                orders = model.orders?.let { it + (tab to model.ordered(tab, sort)) },
                orderings = model.orderings + (tab to model.orderings.getValue(tab) + 1),
            )
        }
    }

    private fun filterBy(text: String?) {
        model.update { it.copy(filter = text) }
        saved[FILTER] = text
    }

    // The message and its Undo come from what this write changed, so each Undo takes back its own step (W3).
    private fun plusOne(id: AnimeId) {
        val anime = model.value.entries[id]?.anime ?: return
        messages.write(ListWrite.MarkNextWatched(anime)) { change ->
            val after = change.after ?: return@write null
            val text =
                if (change.completed) {
                    ShellMessage.Text.MovedToCompleted
                } else {
                    ShellMessage.Text.EpisodeWatched(after.values.watchedEpisodes)
                }
            ShellMessage(text, ShellMessage.Action.Undo(change.undo))
        }
    }

    // Pull-to-refresh, and a first sync with nothing ordered yet, order the list from what the sync brought.
    private suspend fun sync(fromUser: Boolean) {
        model.update { it.copy(failure = null, refreshing = it.refreshing || fromUser) }
        val result = list.sync()
        firstSyncDone = true
        when (result) {
            is Outcome.Success ->
                if (fromUser || model.value.orders == null) {
                    val synced =
                        try {
                            list.observeEntries().first()
                        } catch (e: DataException) {
                            unreadable(e.error)
                            null
                        }
                    val schedule = airing.observeAiring().first()
                    // Meanwhile the list may have arrived and been ordered at start.
                    if (synced != null && (fromUser || model.value.orders == null)) {
                        entriesLoaded = true
                        model.update {
                            it.copy(entries = synced.associateBy { entry -> entry.anime.id }, airing = schedule).orderedAll()
                        }
                    }
                }
            is Outcome.Failure ->
                when {
                    model.value.orders != null ->
                        if (result.error != DataError.SessionExpired) {
                            messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(result.error)))
                        }
                    entriesLoaded && model.value.entries.isNotEmpty() -> model.update { it.orderedAll() }
                    else -> model.update { it.copy(failure = result.error.toListFailure()) }
                }
        }
        model.update { it.copy(refreshing = false) }
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
