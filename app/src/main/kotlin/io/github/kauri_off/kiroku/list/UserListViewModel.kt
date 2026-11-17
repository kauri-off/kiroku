package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
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
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserLists
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.core.user.ofUser
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.title.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// Another user's list, read-only: the keeper's one read of it, which a profile opened before has usually made whole.
// It takes no writer and no airing: nothing of the list reaches the person's own list or AniList.
class UserListViewModel(
    private val name: String,
    private val lists: UserLists,
    private val own: ListSource,
    private val session: SessionRepository,
    connection: Connection,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    // Posts a message to the shell; nothing else of the shell's messages, which can write.
    private val post: (ShellMessage) -> Unit,
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

    // One tab ordered over one list object: a part that arrives orders it again, in place, with no new ordering.
    private class Ordered(
        val entries: List<ListEntry>,
        val sort: TabSort,
        val rows: List<ListRow>,
    )

    private val model = MutableStateFlow(Model(tab = saved.get<String>(TAB)?.let(::tabOf) ?: ListStatus.Watching))
    private val typed = mutableStateOf(saved.get<String>(FILTER))
    private val shownOnce = MutableStateFlow(false)
    private val lane = background.limitedParallelism(1)
    private var load: Job? = null
    private var ordered: Map<EntryStatus, Ordered> = emptyMap()
    private var built: ListContent? = null

    // A read-only row shows no air time, so one moment serves the page for its life and no page is built again for it.
    private val now = clock.instant()

    // The person's own entries, so a title page opens on his own status from its first frame.
    @Volatile private var ownEntries: Map<AnimeId, ListEntry> = emptyMap()

    // The filter field's text, held as Compose state so the field reads each keystroke in the same frame.
    val filter: String? get() = typed.value

    private val loads = ShownLoads(viewModelScope, connection, whenShown = { shownOnce.value = true }, again = { refresh(Asked.Again) })

    // Collected while the page is shown; nothing is read before then.
    val shown: StateFlow<Unit> get() = loads.shown

    val state: StateFlow<ListState> =
        flow {
            coroutineScope {
                launch { session.observeSession().collect { session -> model.update { it.copy(session = session) } } }
                launch {
                    own
                        .observeEntries()
                        .catch { if (it !is DataException) throw it }
                        .collect { entries -> ownEntries = entries.associateBy { it.anime.id } }
                }
                launch { reading() }
                emitAll(combine(model, snapshotFlow { typed.value }, ::build))
            }
        }.flowOn(lane)
            .stateIn(viewModelScope, WhileShown, ListState(ListOwner.Other(name), ListContent.Loading, model.value.tab, now = now))

    // The end of a read again is the refresh's to report, so a failed pull stays owed whatever arrives first.
    private suspend fun reading() {
        shownOnce.first { it }
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

    // What a row shows of [id], with the person's own entry for it, for the title page it opens.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val read = model.value.read
        val entries =
            when (read) {
                is UserListState.Reading -> read.entries
                is UserListState.Whole -> read.entries
                is UserListState.None, null -> return null
            }
        val anime = entries.firstOrNull { it.anime.id == id }?.anime ?: return null
        return TitleSummary(anime, ownEntries[id], airing = null, session = model.value.session)
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
                if (error != null &&
                    held &&
                    asked != Asked.Retry &&
                    !error.ofUser
                ) {
                    post(ShellMessage(ShellMessage.Text.RefreshFailed(error)))
                }
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
                else -> tabs(model, entries, filter)
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
        )
    }

    // A tab whose page did not change keeps the same object, so that its page is not composed again.
    private fun tabs(
        model: Model,
        entries: List<ListEntry>,
        filter: String?,
    ): ListContent.Tabs {
        val byStatus = entries.groupBy { it.status }
        val statuses = tabOrder(byStatus.keys)
        val before = ordered
        ordered =
            statuses.associateWith { status ->
                val sort = model.sorts[status] ?: TabSort(defaultSort(status, withAiring = false))
                before[status]?.takeIf { it.entries === entries && it.sort == sort }
                    ?: Ordered(entries, sort, rows(byStatus[status].orEmpty(), sort, before[status]?.rows))
            }
        val shown = (built as? ListContent.Tabs)?.pages
        return ListContent.Tabs(
            ordered.mapValues { (status, tab) ->
                val rows = if (filter.isNullOrEmpty()) tab.rows else tab.rows.filter { it.title.contains(filter, ignoreCase = true) }
                val page =
                    ListPage(tab.sort.sort, sortsFor(status, withAiring = false), rows, model.orderings[status] ?: 0, tab.sort.direction)
                shown?.get(status)?.takeIf { it == page } ?: page
            },
        )
    }

    // A row that shows the same as before stays the same object.
    private fun rows(
        entries: List<ListEntry>,
        sort: TabSort,
        before: List<ListRow>?,
    ): List<ListRow> {
        val known = before?.associateBy { it.id }.orEmpty()
        return order(entries, sort.sort, { null }, sort.direction) { it.anime.titles.preferred(titleLanguage) }
            .map { entry ->
                val row = listRow(entry, null, now, titleLanguage, readOnly = true, sort = sort.sort)
                known[row.id]?.takeIf { it == row } ?: row
            }
    }

    // The shown tab's new sort, or null for none; a new sort starts in its own direction and from the page's top.
    private fun choose(change: (TabSort) -> TabSort?) {
        viewModelScope.launch(lane) {
            model.update { model ->
                val tab = model.tab
                val current = model.sorts[tab] ?: TabSort(defaultSort(tab, withAiring = false))
                val sort = change(current)?.takeIf { it != current } ?: return@update model
                model.copy(sorts = model.sorts + (tab to sort), orderings = model.orderings + (tab to (model.orderings[tab] ?: 0) + 1))
            }
        }
    }

    private fun filterBy(text: String?) {
        typed.value = text
        saved[FILTER] = text
    }

    private fun saveTab() {
        saved[TAB] = savedTab(model.value.tab)
    }

    private enum class Asked { Retry, Pull, Again }

    private companion object {
        val TAB = notShown("userListTab")
        val FILTER = notShown("userListFilter")
    }
}
