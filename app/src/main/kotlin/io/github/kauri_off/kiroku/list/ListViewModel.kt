package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.unchangedWithoutResultUntil
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// The user's own list, its orders fixed at moments and its state built on [background], one step at a time, in order.
class ListViewModel(
    private val list: ListSource,
    private val anime: AnimeRepository,
    private val airing: AiringRepository,
    private val session: SessionRepository,
    connection: Connection,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
    titleLanguage: Flow<TitleLanguage>,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
    background: CoroutineDispatcher,
) : ViewModel() {
    private data class Model(
        val language: TitleLanguage = TitleLanguage.Romaji,
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val airing: Map<AnimeId, Airing> = emptyMap(),
        // Each tab's ids in the order last computed, with titles that joined since appended; null until the first order.
        // A tab of a status the app does not know is there from the moment one of its titles is until the next order.
        val orders: Map<EntryStatus, List<AnimeId>>? = null,
        val orderings: Map<EntryStatus, Int> = emptyMap(),
        val failure: ListFailure? = null,
        val tab: EntryStatus,
        // Each tab's sort, as chosen; null until the choices are read. One of a status the app does not know is not kept.
        val sorts: Map<EntryStatus, TabSort>? = null,
        val sortSheetOpen: Boolean = false,
        val refreshing: Boolean = false,
        val session: Session? = null,
        // Counts the user's refreshes, each of which builds the pages again.
        val refreshes: Int = 0,
        // Counts the owners of the list this view model has shown; nothing read for an earlier one is kept.
        val owners: Int = 0,
    )

    private val model = MutableStateFlow(Model(tab = saved.get<String>(TAB)?.let(::tabOf) ?: ListStatus.Watching))
    private val typed = mutableStateOf(saved.get<String>(FILTER))
    private val retries = MutableStateFlow(0)

    // Section 6.2: airing is a part of the rows, read again at the next pull or Retry when it could not be read.
    private val airingReads = MutableStateFlow(0)
    private var syncing: Job? = null
    private val lane = background.limitedParallelism(1)

    // The owner whose list is read, until a first one is known.
    @Volatile private var readFor: Any? = NOBODY_YET

    // The first sync ended, or the stored list was fresh: a list read from now on is ordered as it comes.
    @Volatile private var settled = false

    @Volatile private var entriesLoaded = false

    // The store was read and held no list: what a sync then brings is ordered once the airing data it asked for is there.
    @Volatile private var readEmpty = false

    // The syncs under way, of every owner.
    private val downloads = AtomicInteger()

    // The titles whose page is stored, for the summary a title page opens with; no part of what the List shows.
    @Volatile private var pagesRead: Set<AnimeId> = emptySet()

    // What the pages are built from; a tab switch, the sort sheet or the refresh indicator changes none of it.
    private data class Shown(
        val language: TitleLanguage,
        val entries: Map<AnimeId, ListEntry>,
        val airing: Map<AnimeId, Airing>,
        val orders: Map<EntryStatus, List<AnimeId>>?,
        val orderings: Map<EntryStatus, Int>,
        val sorts: Map<EntryStatus, TabSort>?,
        val failure: ListFailure?,
        val signedOut: Boolean,
        val filter: String?,
        val refreshes: Int,
    )

    // Pages written against [now] in [zone]; until a Refresh or [until], the day's end here or in Japan, pages built again keep [now].
    private class Built(
        val shown: Shown,
        val content: ListContent,
        val now: Instant,
        val until: Instant,
        val zone: ZoneId,
    )

    private var built: Built? = null

    // A row as last built, with what it was built from: [now] counts only for a title without a result. A row that shows the same stays.
    private class BuiltRow(
        val entry: ListEntry,
        val airing: Airing?,
        val now: Instant,
        val sort: ListSort,
        val language: TitleLanguage,
        val row: ListRow,
    )

    private var rows: Map<AnimeId, BuiltRow> = emptyMap()

    // The filter field's text, held as Compose state so the field reads each keystroke in the same frame.
    val filter: String? get() = typed.value

    val state: StateFlow<ListState> =
        flow {
            coroutineScope {
                launch {
                    titleLanguage.collect { language ->
                        model.update { current ->
                            if (current.language == language) current else current.copy(language = language).reorderForLanguage()
                        }
                    }
                }
                launch { reading().collect() }
                launch { session.observeSession().collect { session -> model.update { it.copy(session = session) } } }
                launch { anime.observePagesRead().catch { if (it !is DataException) throw it }.collect { pagesRead = it } }
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
                saveTab()
            }
            is ListEvent.PlusOne -> plusOne(event.id)
            ListEvent.StartFilter -> filterBy("")
            is ListEvent.FilterChanged -> filterBy(event.text)
            ListEvent.EndFilter -> filterBy(null)
            ListEvent.OpenSort -> model.update { it.copy(sortSheetOpen = true) }
            ListEvent.DismissSort -> model.update { it.copy(sortSheetOpen = false) }
            is ListEvent.SelectSort -> choose { if (it.sort == event.sort) null else TabSort(event.sort) }
            is ListEvent.SelectDirection -> choose { if (it.sort.defaultDirection == null) null else it.copy(direction = event.direction) }
            ListEvent.Refresh -> {
                airingReads.update { it + 1 }
                startSync(fromUser = true)
            }
            ListEvent.Retry -> {
                retries.update { it + 1 }
                airingReads.update { it + 1 }
                startSync(fromUser = false)
                // A list that could not be read at the start is ordered as the start would have, also with no airing stored.
                if (!settled) {
                    val owners = model.value.owners
                    viewModelScope.launch { if (noAiringStored()) orderedAtStart(owners, stale = true) }
                }
            }
            is ListEvent.Open, is ListEvent.Edit, ListEvent.Search, ListEvent.SignIn, ListEvent.Back -> Unit
        }
    }

    // A sync's end refreshes the stale airing data by itself; a list that needs no sync leaves that to its screen.
    private suspend fun syncIfStale() {
        val stale = list.stale(clock.instant())
        if (stale) syncUnlessSyncing()
        if (!settled && noAiringStored()) {
            val owners = model.value.owners
            viewModelScope.launch { orderedAtStart(owners, stale) }
            return
        }
        if (stale) return
        viewModelScope.launch { airing.refreshStale() }
        if (!settled) {
            withContext(lane) {
                settled = true
                model.update { if (it.orders == null && entriesLoaded) it.orderedAll() else it }
            }
        }
    }

    // A start with no airing data stored orders once the airing refresh ends; a list still to be downloaded is its sync's.
    private suspend fun orderedAtStart(
        owners: Int,
        stale: Boolean,
    ) {
        val stored =
            try {
                list.observeEntries().first()
            } catch (_: DataException) {
                return
            }
        if (stale && stored.isEmpty()) return
        ordered(owners, again = false) { airing.refreshStale() }
    }

    // Rows without airing data when it cannot be read: a part of them, not the list.
    private suspend fun readAiring(): Map<AnimeId, Airing> =
        try {
            airing.observeAiring().first()
        } catch (_: DataException) {
            emptyMap()
        }

    // As after an install or a first sign-in, when what "Up next" needs is still to come.
    private suspend fun noAiringStored(): Boolean =
        try {
            airing.observeSchedules().first().isEmpty()
        } catch (_: DataException) {
            false
        }

    private fun startSync(fromUser: Boolean) {
        syncing = viewModelScope.launch { sync(fromUser) }
    }

    private fun syncUnlessSyncing() {
        if (syncing?.isActive != true) startSync(fromUser = false)
    }

    // S9: each owner's list is read anew; its choices are read after the list's first answer, which follows its clear.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun reading(): Flow<Unit> =
        session
            .observeSession()
            .map { it.owner }
            .distinctUntilChanged()
            .flatMapLatest { owner ->
                if (readFor !== NOBODY_YET && readFor != owner) forgetOwner()
                readFor = owner
                val schedules = airing.observeAiring().readAgainOnRetry(airingReads) { emptyMap() }
                val read =
                    combine(list.observeEntries(), schedules, ::Pair).map { (entries, schedule) ->
                        awaitSorts()
                        val waits = !settled && model.value.orders == null && entries.isNotEmpty() && noAiringStored()
                        received(entries, schedule, waits)
                    }
                if (owner == null) read else merge(read, owedDownloads())
            }.onStart { emit(Unit) }
            .readAgainOnRetry(retries, ::unreadable)

    // The sorts chosen before, read once for each owner; from then on this view model keeps them.
    private suspend fun awaitSorts() {
        val asked = model.value
        if (asked.sorts != null) return
        val sorts = preferences.observeListSorts().first()
        model.update { if (it.sorts == null && it.owners == asked.owners) it.copy(sorts = sorts.toMap<EntryStatus, TabSort>()) else it }
    }

    // A download the engine owes (a write MAL may have made) that failed offline is made when the connection returns.
    private fun owedDownloads(): Flow<Unit> =
        flow {
            var known = false
            var owed = false
            list
                .observeSyncedAt()
                .catch { if (it !is DataException) throw it }
                .collect { at ->
                    if (at == null && known) {
                        owed = true
                        viewModelScope.launch { loads.owedOffline() }
                    } else if (at != null && owed) {
                        // Only what this marked: a load of the List's own that is owed stays owed.
                        owed = false
                        viewModelScope.launch { loads.loadedElsewhere() }
                    }
                    known = at != null
                }
        }

    // Another owner's list starts as a list first shown: no entry, order, sort, tab or filter of the one before; a new
    // signed-in owner's list is downloaded when it is stale, whatever the last owner's sync still does.
    private fun forgetOwner() {
        settled = false
        entriesLoaded = false
        readEmpty = false
        viewModelScope.launch {
            filterBy(null)
            saveTab()
            if (session.observeSession().first() is Session.SignedIn && list.stale(clock.instant())) startSync(fromUser = false)
        }
        model.update {
            it.copy(
                entries = emptyMap(),
                airing = emptyMap(),
                orders = null,
                orderings = it.orderings.mapValues { (_, ordering) -> ordering + 1 },
                failure = null,
                tab = ListStatus.Watching,
                sorts = null,
                sortSheetOpen = false,
                refreshing = false,
                owners = it.owners + 1,
            )
        }
    }

    // [waitsForAiring]: no airing data was stored when the list was read, so the order is the airing refresh's to compute.
    private fun received(
        list: List<ListEntry>,
        schedule: Map<AnimeId, Airing>,
        waitsForAiring: Boolean,
    ) {
        entriesLoaded = true
        if (list.isEmpty()) readEmpty = true
        model.update { model ->
            val read = model.copy(airing = schedule, entries = list.associateBy { it.anime.id })
            when {
                read.orders != null -> read.withJoinedAppended()
                readEmpty && downloads.get() > 0 -> read
                waitsForAiring && !settled -> read
                list.isNotEmpty() || settled -> read.orderedAll()
                else -> read
            }
        }
    }

    // A list that cannot be read shows the error state, whatever it showed before.
    private fun unreadable(error: DataError) {
        entriesLoaded = false
        readEmpty = false
        model.update { it.copy(orders = null, entries = emptyMap(), failure = error.toListFailure()) }
    }

    // What the List shows of [id], for its title page.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val model = model.value
        val entry = model.entries[id] ?: return null
        return TitleSummary(entry.anime, entry, model.airing[id], model.session, pageRead = id in pagesRead)
    }

    private fun build(
        model: Model,
        filter: String?,
    ): ListState {
        val session = model.session
        val shown =
            Shown(
                model.language,
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
        val until = minOf(today.plusDays(1).atStartOfDay(clock.zone).toInstant(), unchangedWithoutResultUntil(now))
        val rowsBefore = rows
        val rowsNow = HashMap<AnimeId, BuiltRow>(rowsBefore.size)
        val content =
            content(
                model,
                filter,
            ) { entry, sort -> row(entry, model.airing[entry.anime.id], now, sort, model.language, rowsBefore, rowsNow) }
        rows = rowsNow
        return Built(shown, content.sharing(built?.content), now, until, clock.zone).also { built = it }
    }

    private fun row(
        entry: ListEntry,
        airing: Airing?,
        now: Instant,
        sort: ListSort,
        language: TitleLanguage,
        before: Map<AnimeId, BuiltRow>,
        after: MutableMap<AnimeId, BuiltRow>,
    ): ListRow {
        val id = entry.anime.id
        val last = before[id]
        val built =
            last?.takeIf {
                it.entry == entry &&
                    it.airing == airing &&
                    (airing != null || it.now == now) &&
                    it.sort == sort &&
                    it.language == language
            }
                ?: run {
                    val row = listRow(entry, airing, now, language, sort = sort)
                    BuiltRow(entry, airing, now, sort, language, last?.row?.takeIf(row::equals) ?: row)
                }
        after[id] = built
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
        row: (ListEntry, ListSort) -> ListRow,
    ): ListContent {
        val orders = model.orders
        val sorts = model.sorts
        return when {
            model.session == Session.SignedOut -> ListContent.SignedOut
            orders == null || sorts == null -> model.failure?.let(ListContent::Failed) ?: ListContent.Loading
            else ->
                ListContent.Tabs(orders.mapValues { (status, ids) -> page(model, sorts.of(status), filter, status, ids, row) })
        }
    }

    private fun page(
        model: Model,
        sort: TabSort,
        text: String?,
        status: EntryStatus,
        ids: List<AnimeId>,
        row: (ListEntry, ListSort) -> ListRow,
    ): ListPage {
        val rows =
            ids
                .mapNotNull { id -> model.entries[id]?.takeIf { it.status == status } }
                .map { row(it, sort.sort) }
                .filter { text.isNullOrEmpty() || it.title.contains(text, ignoreCase = true) }
        return ListPage(sort.sort, sortsFor(status, withAiring = true), rows, model.orderings[status] ?: 0, sort.direction)
    }

    private fun Model.ordered(
        status: EntryStatus,
        sort: TabSort,
    ): List<AnimeId> =
        order(entries.values.filter { it.status == status }, sort.sort, airing::get, sort.direction) {
            it.anime.titles.preferred(language)
        }.map { it.anime.id }

    private fun Model.reorderForLanguage(): Model {
        val sorts = sorts ?: return this
        return copy(orders = orders?.mapValues { (status, _) -> ordered(status, sorts.of(status)) })
    }

    // A tab that is no longer there gives way to the one now in its place, or to Watching.
    private fun Model.orderedAll(): Model {
        val sorts = sorts ?: return this
        val statuses = tabOrder(entries.values.map { it.status })
        val before = orders?.keys?.let(::tabOrder)
        val shown =
            when {
                tab in statuses -> tab
                before != null && tab in before -> statuses[before.indexOf(tab).coerceAtMost(statuses.lastIndex)]
                else -> ListStatus.Watching
            }
        return copy(
            orders = statuses.associateWith { ordered(it, sorts.of(it)) },
            orderings = if (orders == null) orderings else (orderings.keys + statuses).associateWith { (orderings[it] ?: 0) + 1 },
            tab = shown,
        )
    }

    // A title that joined a tab since its order was computed goes at its end; one that left keeps its place for Undo.
    private fun Model.withJoinedAppended(): Model {
        val current = orders ?: return this
        val next =
            (current.keys + entries.values.map { it.status }).associateWith { status ->
                val ids = current[status].orEmpty()
                val known = ids.toSet()
                ids + entries.values.filter { it.status == status && it.anime.id !in known }.map { it.anime.id }
            }
        return if (next != current) copy(orders = next) else this
    }

    // The shown tab's new sort, or null for none; a new sort starts in its own direction. The sheet stays open.
    private fun choose(change: (TabSort) -> TabSort?) {
        val tab = model.value.tab
        viewModelScope.launch(lane) {
            val current = model.value.sorts?.of(tab) ?: return@launch
            val sort = change(current)?.takeIf { it != current } ?: return@launch
            model.update { model ->
                model.copy(
                    sorts = model.sorts?.plus(tab to sort),
                    orders = model.orders?.let { it + (tab to model.ordered(tab, sort)) },
                    orderings = model.orderings + (tab to (model.orderings[tab] ?: 0) + 1),
                )
            }
            if (tab is ListStatus) withContext(NonCancellable) { preferences.setListSort(tab, sort) }
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
            ShellMessage(text, change.undo?.let(ShellMessage.Action::Undo))
        }
    }

    // Pull-to-refresh, and a first sync with nothing ordered yet, order the list from what the sync brought.
    private suspend fun sync(fromUser: Boolean) {
        model.update { it.copy(failure = null, refreshing = it.refreshing || fromUser) }
        loads.started()
        val owners = model.value.owners
        // A pull asks for every tracked title's airing beside the sync; what is still asked after the wait goes on.
        val pulled = if (fromUser) viewModelScope.launch { airing.refresh() } else null
        downloads.incrementAndGet()
        try {
            val result = list.sync()
            loads.ended((result as? Outcome.Failure)?.error)
            if (model.value.owners != owners) {
                model.update { it.copy(refreshing = false) }
                return
            }
            // AniList is asked whatever MAL answered, an expired session included; offline the sync's repeat asks once it is back.
            if (result is Outcome.Failure && result.error != DataError.NoConnection) viewModelScope.launch { airing.refreshStale() }
            when (result) {
                is Outcome.Success ->
                    if (fromUser || model.value.orders == null) {
                        ordered(owners, again = fromUser) {
                            pulled?.join()
                            airing.refreshStale()
                        }
                    } else {
                        settle(owners)
                    }
                is Outcome.Failure -> failed(result.error, owners)
            }
        } finally {
            downloads.decrementAndGet()
        }
        model.update { it.copy(refreshing = false, refreshes = if (fromUser) it.refreshes + 1 else it.refreshes) }
        // A tab that went with the order gave way to another, which is the one to restore.
        saveTab()
    }

    // A list shown stays with a message; one stored and never ordered is ordered, after the airing refresh when none is stored.
    private suspend fun failed(
        error: DataError,
        owners: Int,
    ) {
        val stored =
            withContext(lane) {
                val current = model.value
                if (current.owners != owners) return@withContext false
                val stored = current.orders == null && entriesLoaded && current.entries.isNotEmpty()
                if (!stored) {
                    settled = true
                    if (current.orders == null) model.update { it.copy(failure = error.toListFailure()) }
                }
                stored
            }
        if (stored) {
            ordered(owners, again = false) { if (noAiringStored()) airing.refreshStale() }
        } else if (model.value.orders != null && error != DataError.SessionExpired) {
            messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(error)))
        }
    }

    // "Up next" by what [refresh] brought, however it ended, waited for up to AIRING_WAIT; read from the stores, ahead of the lane.
    private suspend fun ordered(
        owners: Int,
        again: Boolean,
        refresh: suspend () -> Unit,
    ) {
        withTimeoutOrNull(AIRING_WAIT) { refresh() }
        val read =
            try {
                list.observeEntries().first() to readAiring()
            } catch (e: DataException) {
                unreadable(e.error)
                return
            }
        awaitSorts()
        val (synced, schedule) = read
        withContext(lane) {
            if (model.value.owners != owners) return@withContext
            entriesLoaded = true
            settled = true
            model.update {
                if (again || it.orders == null) {
                    it.copy(entries = synced.associateBy { entry -> entry.anime.id }, airing = schedule).orderedAll()
                } else {
                    it
                }
            }
        }
    }

    private suspend fun settle(owners: Int) {
        withContext(lane) { if (model.value.owners == owners) settled = true }
    }

    private fun saveTab() {
        saved[TAB] = savedTab(model.value.tab)
    }

    private companion object {
        // How long an order waits for the airing data its sync asked for.
        val AIRING_WAIT = 5.seconds

        val TAB = notShown("tab")
        val FILTER = notShown("filter")
        val NOBODY_YET = Any()

        val Session.owner: SignInId?
            get() =
                when (this) {
                    Session.SignedOut -> null
                    is Session.SignedIn -> signIn
                    is Session.Expired -> signIn
                }

        fun Map<EntryStatus, TabSort>.of(status: EntryStatus): TabSort = get(status) ?: TabSort.defaultFor(status)
    }
}

internal fun DataError.toListFailure(): ListFailure =
    when (this) {
        DataError.NoConnection -> ListFailure.NoConnection
        DataError.Refused -> ListFailure.Refused
        DataError.NoClientId -> ListFailure.NoClientId
        DataError.PrivateList -> ListFailure.PrivateList
        DataError.UnknownUser -> ListFailure.UnknownUser
        else -> ListFailure.Other
    }
