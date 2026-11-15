package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.stale
import io.github.kauri_off.kiroku.title.TitleSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The user's own list, its orders fixed at moments and its state built on [background], one step at a time, in order.
class ListViewModel(
    private val list: ListSource,
    private val airing: AiringRepository,
    session: SessionRepository,
    connection: Connection,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
    background: CoroutineDispatcher,
) : ViewModel() {
    private data class Model(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val airing: Map<AnimeId, Airing> = emptyMap(),
        // Each tab's ids in the order last computed, with titles that joined since appended; null until the first order.
        val orders: Map<ListStatus, List<AnimeId>>? = null,
        val orderings: Map<ListStatus, Int> = ListStatus.entries.associateWith { 0 },
        val failure: ListFailure? = null,
        val tab: ListStatus,
        // Each tab's sort, as chosen; null until the choices are read.
        val sorts: Map<ListStatus, TabSort>? = null,
        val sortSheetOpen: Boolean = false,
        val refreshing: Boolean = false,
        val session: Session? = null,
        // Counts the user's refreshes, each of which builds the pages again.
        val refreshes: Int = 0,
    )

    private val model = MutableStateFlow(Model(tab = saved.get<String>(TAB)?.let(ListStatus::valueOf) ?: ListStatus.Watching))
    private val typed = mutableStateOf(saved.get<String>(FILTER))
    private val retries = MutableStateFlow(0)
    private var syncing: Job? = null
    private val lane = background.limitedParallelism(1)

    // The sorts chosen before, read once when the List is first shown; from then on this view model keeps them.
    private val chosen = viewModelScope.async(lane, CoroutineStart.LAZY) { preferences.observeListSorts().first() }

    // The first sync ended, or the stored list was fresh: a list read from now on is ordered as it comes.
    @Volatile private var settled = false

    @Volatile private var entriesLoaded = false

    // What the pages are built from; a tab switch, the sort sheet or the refresh indicator changes none of it.
    private data class Shown(
        val entries: Map<AnimeId, ListEntry>,
        val airing: Map<AnimeId, Airing>,
        val orders: Map<ListStatus, List<AnimeId>>?,
        val orderings: Map<ListStatus, Int>,
        val sorts: Map<ListStatus, TabSort>?,
        val failure: ListFailure?,
        val signedOut: Boolean,
        val filter: String?,
        val refreshes: Int,
    )

    // Pages written against [now] in [zone]; until a Refresh or [until], pages built again keep [now].
    private class Built(
        val shown: Shown,
        val content: ListContent,
        val now: Instant,
        val until: Instant,
        val zone: ZoneId,
    )

    private var built: Built? = null

    // A row as last built, with what it was built from; a row that shows the same stays the same object.
    private class BuiltRow(
        val entry: ListEntry,
        val airing: Airing?,
        val today: LocalDate,
        val sort: ListSort,
        val row: ListRow,
    )

    private var rows: Map<AnimeId, BuiltRow> = emptyMap()

    // The filter field's text, held as Compose state so the field reads each keystroke in the same frame.
    val filter: String? get() = typed.value

    val state: StateFlow<ListState> =
        flow {
            coroutineScope {
                launch { reading().collect() }
                launch { session.observeSession().collect { session -> model.update { it.copy(session = session) } } }
                emitAll(combine(model, snapshotFlow { typed.value }, ::build))
            }
        }.flowOn(lane)
            .stateIn(
                viewModelScope,
                WhileShown,
                ListState(ListOwner.Me, ListContent.Loading, model.value.tab, filter = typed.value, now = clock.instant()),
            )

    private val loads = ShownLoads(viewModelScope, connection, ::syncIfStale, ::syncUnlessSyncing)

    // Collected while the List is shown; showing it syncs a stale list.
    val shown: StateFlow<Unit> get() = loads.shown

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
            is ListEvent.SelectSort -> choose { if (it.sort == event.sort) null else TabSort(event.sort) }
            is ListEvent.SelectDirection -> choose { if (it.sort.defaultDirection == null) null else it.copy(direction = event.direction) }
            ListEvent.Refresh -> startSync(fromUser = true)
            ListEvent.Retry -> {
                retries.update { it + 1 }
                startSync(fromUser = false)
            }
            is ListEvent.Open, is ListEvent.Edit, ListEvent.Search, ListEvent.SignIn, ListEvent.Back -> Unit
        }
    }

    private suspend fun syncIfStale() {
        if (list.stale(clock.instant())) {
            syncUnlessSyncing()
        } else if (!settled) {
            settled = true
            withContext(lane) { model.update { if (it.orders == null && entriesLoaded) it.orderedAll() else it } }
        }
    }

    private fun startSync(fromUser: Boolean) {
        syncing = viewModelScope.launch { sync(fromUser) }
    }

    private fun syncUnlessSyncing() {
        if (syncing?.isActive != true) startSync(fromUser = false)
    }

    private fun reading(): Flow<Unit> =
        flow {
            awaitSorts()
            emitAll(combine(list.observeEntries(), airing.observeAiring(), ::received))
        }.onStart { emit(Unit) }
            .readAgainOnRetry(retries, ::unreadable)

    private suspend fun awaitSorts() {
        if (model.value.sorts != null) return
        val sorts = chosen.await()
        model.update { if (it.sorts == null) it.copy(sorts = sorts) else it }
    }

    private fun received(
        list: List<ListEntry>,
        schedule: Map<AnimeId, Airing>,
    ) {
        entriesLoaded = true
        model.update { model ->
            val read = model.copy(airing = schedule, entries = list.associateBy { it.anime.id })
            when {
                read.orders != null -> read.withJoinedAppended()
                list.isNotEmpty() || settled -> read.orderedAll()
                else -> read
            }
        }
    }

    // A list that cannot be read shows the error state, whatever it showed before.
    private fun unreadable(error: DataError) {
        entriesLoaded = false
        model.update { it.copy(orders = null, entries = emptyMap(), failure = error.toListFailure()) }
    }

    // What the List shows of [id], for its title page.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val model = model.value
        val entry = model.entries[id] ?: return null
        return TitleSummary(entry.anime, entry, model.airing[id], model.session)
    }

    private fun build(
        model: Model,
        filter: String?,
    ): ListState {
        val session = model.session
        val shown =
            Shown(
                model.entries,
                model.airing,
                model.orders,
                model.orderings,
                model.sorts,
                model.failure,
                session == Session.SignedOut,
                filter,
                model.refreshes,
            )
        val instant = clock.instant()
        val current =
            built?.takeIf { instant >= it.now && instant < it.until && it.zone == clock.zone && it.shown.refreshes == shown.refreshes }
        val pages =
            current?.takeIf { it.shown == shown }
                ?: current?.let { built(model, filter, shown, it.now) }?.takeIf { instant < it.until }
                ?: built(model, filter, shown, instant)
        return ListState(
            owner = ListOwner.Me,
            content = pages.content,
            tab = model.tab,
            sortSheetOpen = model.sortSheetOpen,
            filter = filter,
            refreshing = model.refreshing,
            sessionExpired = session is Session.Expired,
            now = pages.now,
        )
    }

    private fun built(
        model: Model,
        filter: String?,
        shown: Shown,
        now: Instant,
    ): Built {
        val today = now.atZone(clock.zone).toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(clock.zone).toInstant()
        val nextAirTime =
            model.airing.values
                .mapNotNull { it.nextEpisode?.airsAt?.takeIf(now::isBefore) }
                .minOrNull()
        val until = nextAirTime?.coerceAtMost(midnight) ?: midnight
        val rowsBefore = rows
        val rowsNow = HashMap<AnimeId, BuiltRow>(rowsBefore.size)
        val content =
            content(model, filter, today) { entry, sort -> row(entry, model.airing[entry.anime.id], today, sort, rowsBefore, rowsNow) }
        rows = rowsNow
        return Built(shown, content.sharing(built?.content), now, until, clock.zone).also { built = it }
    }

    private fun row(
        entry: ListEntry,
        airing: Airing?,
        today: LocalDate,
        sort: ListSort,
        before: Map<AnimeId, BuiltRow>,
        now: MutableMap<AnimeId, BuiltRow>,
    ): ListRow {
        val id = entry.anime.id
        val last = before[id]
        val built =
            last?.takeIf { it.entry == entry && it.airing == airing && it.today == today && it.sort == sort }
                ?: run {
                    val row = listRow(entry, airing, today, titleLanguage, sort = sort)
                    BuiltRow(entry, airing, today, sort, last?.row?.takeIf(row::equals) ?: row)
                }
        now[id] = built
        return built.row
    }

    // A tab whose page did not change keeps the same object, so that its page is not composed again.
    private fun ListContent.sharing(before: ListContent?): ListContent {
        if (this !is ListContent.Tabs || before !is ListContent.Tabs) return this
        return ListContent.Tabs(pages.mapValues { (status, page) -> before.pages[status]?.takeIf { it == page } ?: page })
    }

    private fun content(
        model: Model,
        filter: String?,
        today: LocalDate,
        row: (ListEntry, ListSort) -> ListRow,
    ): ListContent {
        val orders = model.orders
        val sorts = model.sorts
        return when {
            model.session == Session.SignedOut -> ListContent.SignedOut
            orders == null || sorts == null -> model.failure?.let(ListContent::Failed) ?: ListContent.Loading
            else ->
                ListContent.Tabs(
                    ListStatus.entries.associateWith { page(model, sorts.getValue(it), filter, it, orders.getValue(it), row) },
                )
        }
    }

    private fun page(
        model: Model,
        sort: TabSort,
        text: String?,
        status: ListStatus,
        ids: List<AnimeId>,
        row: (ListEntry, ListSort) -> ListRow,
    ): ListPage {
        val rows =
            ids
                .mapNotNull { id -> model.entries[id]?.takeIf { it.status == status } }
                .map { row(it, sort.sort) }
                .filter { text.isNullOrEmpty() || it.title.contains(text, ignoreCase = true) }
        return ListPage(sort.sort, sortsFor(status, withAiring = true), rows, model.orderings.getValue(status), sort.direction)
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun Model.ordered(
        status: ListStatus,
        sort: TabSort,
    ): List<AnimeId> =
        order(entries.values.filter { it.status == status }, sort.sort, airing::get, today(), sort.direction) {
            it.anime.titles.preferred(titleLanguage)
        }.map { it.anime.id }

    private fun Model.orderedAll(): Model {
        val sorts = sorts ?: return this
        return copy(
            orders = ListStatus.entries.associateWith { ordered(it, sorts.getValue(it)) },
            orderings = if (orders == null) orderings else orderings.mapValues { it.value + 1 },
        )
    }

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

    // The shown tab's new sort, or null for none; a new sort starts in its own direction. The sheet stays open.
    private fun choose(change: (TabSort) -> TabSort?) {
        val tab = model.value.tab
        viewModelScope.launch(lane) {
            val current = model.value.sorts?.get(tab) ?: return@launch
            val sort = change(current)?.takeIf { it != current } ?: return@launch
            model.update { model ->
                model.copy(
                    sorts = model.sorts?.plus(tab to sort),
                    orders = model.orders?.let { it + (tab to model.ordered(tab, sort)) },
                    orderings = model.orderings + (tab to model.orderings.getValue(tab) + 1),
                )
            }
            withContext(NonCancellable) { preferences.setListSort(tab, sort) }
        }
    }

    private fun filterBy(text: String?) {
        typed.value = text
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
        loads.started()
        val result = list.sync()
        settled = true
        loads.ended((result as? Outcome.Failure)?.error)
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
                    awaitSorts()
                    if (synced != null) {
                        entriesLoaded = true
                        // Asked on the lane: the list may have arrived there first and been ordered at start.
                        withContext(lane) {
                            model.update {
                                if (fromUser || it.orders == null) {
                                    it.copy(entries = synced.associateBy { entry -> entry.anime.id }, airing = schedule).orderedAll()
                                } else {
                                    it
                                }
                            }
                        }
                    }
                }
            is Outcome.Failure ->
                when {
                    model.value.orders != null ->
                        if (result.error != DataError.SessionExpired) {
                            messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(result.error)))
                        }
                    entriesLoaded && model.value.entries.isNotEmpty() ->
                        withContext(lane) {
                            model.update {
                                if (it.orders ==
                                    null
                                ) {
                                    it.orderedAll()
                                } else {
                                    it
                                }
                            }
                        }
                    else -> model.update { it.copy(failure = result.error.toListFailure()) }
                }
        }
        model.update { it.copy(refreshing = false, refreshes = if (fromUser) it.refreshes + 1 else it.refreshes) }
    }

    private companion object {
        val TAB = notShown("tab")
        val FILTER = notShown("filter")
    }
}

internal fun DataError.toListFailure(): ListFailure =
    when (this) {
        DataError.NoConnection -> ListFailure.NoConnection
        DataError.Refused -> ListFailure.Refused
        else -> ListFailure.Other
    }
