package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserLists
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.core.user.ofUser
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.title.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// Another user's list, read-only: the keeper's one read of it, which a profile opened before has usually made whole.
// Of the person's own list it gets only its entries, to read, and of the shell only a way to say a refresh failed.
class UserListViewModel(
    private val name: String,
    private val lists: UserLists,
    private val ownEntries: Flow<List<ListEntry>>,
    private val session: SessionRepository,
    connection: Connection,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    private val refreshFailed: (DataError) -> Unit,
    background: CoroutineDispatcher,
) : ViewModel() {
    private data class Model(
        val read: UserListState? = null,
        val tab: EntryStatus,
        // Each tab's sort, as chosen here; never stored, since the person's own sorts are his list's.
        val sorts: Map<EntryStatus, TabSort> = emptyMap(),
        val orderings: Map<EntryStatus, Int> = emptyMap(),
        val sortSheetOpen: Boolean = false,
        val session: Session? = null,
    )

    // Each tab's titles in the order shown: computed when a read ends or a sort is chosen; a part read meanwhile adds
    // its titles at the end (section 6.2).
    private class Orders(
        val over: List<ListEntry>,
        val whole: Boolean,
        val sorts: Map<EntryStatus, TabSort>,
        val ids: Map<EntryStatus, List<AnimeId>>,
    )

    // A row as last built, kept while it shows the same, so that no row is composed again for nothing.
    private class BuiltRow(
        val entry: ListEntry,
        val sort: TabSort,
        val row: ListRow,
    )

    private val model = MutableStateFlow(Model(tab = saved.get<String>(TAB)?.let(::tabOf) ?: ListStatus.Watching))
    private val typed = mutableStateOf(saved.get<String>(FILTER))
    private val lane = background.limitedParallelism(1)
    private var load: Job? = null
    private var reading: Job? = null
    private var orders: Orders? = null
    private var rows: Map<AnimeId, BuiltRow> = emptyMap()
    private var built: ListContent? = null

    // A read-only row shows no air time, so one moment serves the page for its life and no page is built again for it.
    private val now = clock.instant()

    // The person's own entries once read, so a title page opens on his own status; unknown until then.
    @Volatile private var own: Map<AnimeId, ListEntry>? = null

    // The filter field's text, held as Compose state so the field reads each keystroke in the same frame.
    val filter: String? get() = typed.value

    private val loads = ShownLoads(viewModelScope, connection, whenShown = { startReading() }, again = { refresh(Asked.Again) })

    // Collected while the page is shown; nothing is read before then.
    val shown: StateFlow<Unit> get() = loads.shown

    val state: StateFlow<ListState> =
        flow {
            coroutineScope {
                launch { session.observeSession().collect { session -> model.update { it.copy(session = session) } } }
                emitAll(combine(model, snapshotFlow { typed.value }, ::build))
            }
        }.flowOn(lane)
            .stateIn(viewModelScope, WhileShown, ListState(ListOwner.Other(name), ListContent.Loading, model.value.tab, now = now))

    // From the first show the read is held for the view model's life, so a title page opened on top keeps it; only
    // leaving the page for good stops it.
    private fun startReading() {
        if (reading != null) return
        reading =
            viewModelScope.launch(lane) {
                launch {
                    ownEntries
                        .catch { if (it !is DataException) throw it }
                        .collect { entries -> own = entries.associateBy { it.anime.id } }
                }
                // The end of a read again is the refresh's to report, so a failed pull stays owed whatever arrives first.
                var readAgain = false
                lists.observeList(name).collect { read ->
                    when (read) {
                        is UserListState.None -> loads.ended(read.error)
                        is UserListState.Whole -> if (read.again == null && !readAgain) loads.ended(null)
                        is UserListState.Reading -> Unit
                    }
                    readAgain = read is UserListState.Whole && read.again != null
                    model.update { it.copy(read = read) }
                }
            }
    }

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SelectTab -> {
                model.update { it.copy(tab = event.status) }
                saveTab()
            }
            ListEvent.StartFilter -> filterBy("")
            is ListEvent.FilterChanged -> filterBy(event.text)
            ListEvent.EndFilter -> filterBy(null)
            ListEvent.OpenSort -> model.update { it.copy(sortSheetOpen = true) }
            ListEvent.DismissSort -> model.update { it.copy(sortSheetOpen = false) }
            is ListEvent.SelectSort -> choose { if (it.sort == event.sort) null else TabSort(event.sort) }
            is ListEvent.SelectDirection -> choose { if (it.sort.defaultDirection == null) null else it.copy(direction = event.direction) }
            ListEvent.Refresh -> refresh(Asked.Pull)
            ListEvent.Retry -> refresh(Asked.Retry)
            // A row has no step and no edit, and the screen offers neither; one that arrived would change nothing.
            is ListEvent.PlusOne, is ListEvent.Edit -> Unit
            is ListEvent.Open, ListEvent.Search, ListEvent.SignIn, ListEvent.Back -> Unit
        }
    }

    // What a row shows of [id], with the person's own entry for it, for the title page it opens; none while his own
    // entries are not known, so the page waits for its store rather than show him without the title.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val own = own ?: return null
        val entries =
            when (val read = model.value.read) {
                is UserListState.Reading -> read.entries
                is UserListState.Whole -> read.entries
                is UserListState.None, null -> return null
            }
        val anime = entries.firstOrNull { it.anime.id == id }?.anime ?: return null
        return TitleSummary(anime, own[id], airing = null, session = model.value.session)
    }

    // A pull, a Retry or the connection back: the list is read again, or the read under way joined.
    private fun refresh(asked: Asked) {
        if (load?.isActive == true) return
        val held = model.value.read is UserListState.Whole
        load =
            viewModelScope.launch {
                loads.started()
                val error = (lists.refreshList(name) as? Outcome.Failure)?.error
                loads.ended(error)
                if (error != null && held && asked != Asked.Retry && !error.ofUser) refreshFailed(error)
            }
    }

    private fun build(
        model: Model,
        filter: String?,
    ): ListState {
        val read = model.read
        val entries =
            when (read) {
                is UserListState.Reading -> read.entries.takeIf { it.isNotEmpty() }
                is UserListState.Whole -> read.entries
                else -> null
            }
        val content =
            when {
                read is UserListState.None -> ListContent.Failed(read.error.toListFailure())
                entries == null -> ListContent.Loading
                else -> tabs(model, entries, read is UserListState.Whole, filter)
            }.also { built = it }
        val statuses = (content as? ListContent.Tabs)?.statuses
        return ListState(
            owner = ListOwner.Other(name),
            content = content,
            tab = model.tab.takeIf { statuses == null || it in statuses } ?: ListStatus.Watching,
            sortSheetOpen = model.sortSheetOpen,
            filter = filter,
            sessionExpired = model.session is Session.Expired,
            now = now,
            reading =
                when (read) {
                    is UserListState.Reading -> UserListProgress(read.entries.size, read.total)
                    is UserListState.Whole -> read.again
                    else -> null
                },
            readingAgain = read is UserListState.Whole && read.again != null,
            listSize = (read as? UserListState.Whole)?.entries?.size,
        )
    }

    // A tab whose page did not change keeps the same object, so that its page is not composed again.
    private fun tabs(
        model: Model,
        entries: List<ListEntry>,
        whole: Boolean,
        filter: String?,
    ): ListContent.Tabs {
        val byId = entries.associateBy { it.anime.id }
        val ids = ordered(model, entries, whole).ids
        val before = rows
        val after = HashMap<AnimeId, BuiltRow>(entries.size)
        val shown = (built as? ListContent.Tabs)?.pages
        val content =
            ListContent.Tabs(
                ids.mapValues { (status, tab) ->
                    val sort = sortOf(model, status)
                    val all = tab.map { id -> row(byId.getValue(id), sort, before, after) }
                    val rows = if (filter.isNullOrEmpty()) all else all.filter { it.title.contains(filter, ignoreCase = true) }
                    val page = ListPage(sort.sort, sortsFor(status, withAiring = false), rows, model.orderings[status] ?: 0, sort.direction)
                    shown?.get(status)?.takeIf { it == page } ?: page
                },
            )
        rows = after
        return content
    }

    // A read that ends, a new whole list or a tab's new sort orders by `:core`; a part read meanwhile, the first one
    // too, goes at its tabs' ends in the order it came.
    private fun ordered(
        model: Model,
        entries: List<ListEntry>,
        whole: Boolean,
    ): Orders {
        val last = orders
        val statuses = tabOrder(entries.map { it.status }.toSet())
        val sorts = statuses.associateWith { sortOf(model, it) }
        if (last != null && last.over === entries && last.whole == whole && last.sorts == sorts) return last
        val ids =
            when {
                whole && (last == null || !last.whole || last.over !== entries) -> statuses.associateWith { sorted(entries, it, sorts) }
                else -> {
                    val known =
                        last
                            ?.ids
                            ?.values
                            ?.flatten()
                            ?.toHashSet()
                            .orEmpty()
                    val joined = entries.filter { it.anime.id !in known }.groupBy({ it.status }, { it.anime.id })
                    statuses.associateWith { status ->
                        if (last != null && last.sorts[status] != sorts[status]) {
                            sorted(entries, status, sorts)
                        } else {
                            last?.ids?.get(status).orEmpty() + joined[status].orEmpty()
                        }
                    }
                }
            }
        return Orders(entries, whole, sorts, ids).also { orders = it }
    }

    private fun sorted(
        entries: List<ListEntry>,
        status: EntryStatus,
        sorts: Map<EntryStatus, TabSort>,
    ): List<AnimeId> {
        val sort = sorts.getValue(status)
        return order(
            entries.filter { it.status == status },
            sort.sort,
            { null },
            sort.direction,
        ) { it.anime.titles.preferred(titleLanguage) }
            .map { it.anime.id }
    }

    private fun row(
        entry: ListEntry,
        sort: TabSort,
        before: Map<AnimeId, BuiltRow>,
        after: MutableMap<AnimeId, BuiltRow>,
    ): ListRow {
        val id = entry.anime.id
        val built =
            before[id]?.takeIf { it.entry == entry && it.sort == sort }
                ?: BuiltRow(entry, sort, listRow(entry, null, now, titleLanguage, readOnly = true, sort = sort.sort))
        after[id] = built
        return built.row
    }

    private fun sortOf(
        model: Model,
        status: EntryStatus,
    ): TabSort = model.sorts[status] ?: TabSort(defaultSort(status, withAiring = false))

    // The shown tab's new sort, or null for none; a new sort starts in its own direction and from the page's top.
    private fun choose(change: (TabSort) -> TabSort?) {
        viewModelScope.launch(lane) {
            model.update { model ->
                val tab = model.tab
                val current = sortOf(model, tab)
                val sort = change(current)?.takeIf { it != current } ?: return@update model
                model.copy(sorts = model.sorts + (tab to sort), orderings = model.orderings + (tab to (model.orderings[tab] ?: 0) + 1))
            }
        }
    }

    private fun filterBy(text: String?) {
        typed.value = text
        saved[FILTER] = text
    }

    // A tab of a status the app does not know is not kept: after a new process it may not be there yet.
    private fun saveTab() {
        saved[TAB] = (model.value.tab as? ListStatus)?.let(::savedTab)
    }

    private enum class Asked { Retry, Pull, Again }

    private companion object {
        val TAB = notShown("userListTab")
        val FILTER = notShown("userListFilter")
    }
}
