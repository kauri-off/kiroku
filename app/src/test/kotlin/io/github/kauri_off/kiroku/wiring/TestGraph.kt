package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.score.ScoreDistributionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

// Demo mode with the stores' answers made late, slow, held or failing, and their open reads counted.
class TestGraph(
    val demo: Demo,
    // An observer hears of a change after the write and a fresh read see it, as with a database.
    lateObservers: Boolean = false,
    // A write takes time before it is applied.
    slowWrites: Boolean = false,
    override val preferences: PreferencesRepository = demo.preferences,
) : AppGraph {
    private val counter = Readers()

    // The collectors of the list, airing, title and session flows at this moment.
    val readers: Int get() = counter.count

    // While true, no read of those flows answers, as a database that has not answered yet.
    var readsHeld: Boolean
        get() = counter.held.value
        set(value) {
            counter.held.value = value
        }

    override val clock = demo.clock
    private val scoreAskCount = AtomicInteger()

    // The score distributions the screens asked for so far.
    val scoreAsks: Int get() = scoreAskCount.get()

    override val scores =
        object : ScoreDistributionRepository {
            override suspend fun distribution(id: AnimeId) = demo.scores.distribution(id).also { scoreAskCount.incrementAndGet() }
        }
    override val session: SessionRepository = CountedSession(demo.session, counter)
    private val syncCount = AtomicInteger()

    // The list syncs asked for so far.
    val syncs: Int get() = syncCount.get()

    private val listReads = ListReads()

    // Collections of the whole list so far, the titles whose entries were read one by one, and each read of the
    // entries of several titles together.
    val wholeListReads: Int get() = listReads.whole.get()
    val entriesRead: Set<AnimeId> get() = listReads.titles.toSet()
    val entryQueries: List<Set<AnimeId>> get() = listReads.sets.toList()

    override val list: ListRepository =
        CountedList(
            demo.list.let { if (lateObservers) LateList(it) else it }.let { if (slowWrites) SlowWrites(it) else it },
            counter,
            syncCount,
            listReads,
        )

    // While true, the list's store cannot be read.
    var listUnreadable: Boolean
        get() = demo.store.unreadable.value
        set(value) {
            demo.store.unreadable.value = value
        }
    private val refreshCount = AtomicInteger()

    // The title refreshes asked for so far.
    val refreshes: Int get() = refreshCount.get()

    // While true, the store holds no title and no details, as before a title is first stored.
    val titlesUnstored = MutableStateFlow(false)

    // While true, the title store cannot be read: its flows end with a storage error.
    val titlesUnreadable = MutableStateFlow(false)

    // While it holds a title, a refresh of that title fails as without a connection.
    val refreshFails = MutableStateFlow(emptySet<AnimeId>())

    private val counted = CountedAnime(demo.anime, counter, refreshCount, titlesUnstored, titlesUnreadable, refreshFails)

    // The titles MAL answered so far, in order (a request cancelled before its answer reached nothing, as the demo's
    // server answers at the end of its latency), and each read of the details of several titles together.
    val refreshed: List<AnimeId> get() = counted.refreshed.toList()

    // The titles asked for so far, in order, answered or not.
    val requested: List<AnimeId> get() = counted.requested.toList()
    val detailQueries: List<Set<AnimeId>> get() = counted.detailQueries.toList()

    override val anime: AnimeRepository = counted
    override val airing: AiringRepository = CountedAiring(demo.airing, counter)
    override val search = demo.search
    override val user = demo.user
    override val notifications get() = demo.notifications
    override val withoutScreen = WorkAtOnce({ list }, { airing }, { demo.notifications })
    override val madeUp = true

    // The test's main dispatcher, so that the state a view model builds is built within the test's scheduler.
    override val background: CoroutineDispatcher = Dispatchers.Main

    // While set, the connection says this whatever the demo's server does, as when a request and the system disagree.
    val connectionSays = MutableStateFlow<Boolean?>(null)

    override val connection: Connection =
        object : Connection {
            override fun observeOnline(): Flow<Boolean> =
                combine(demo.connection.observeOnline(), connectionSays) { online, says -> says ?: online }.distinctUntilChanged()
        }

    companion object {
        fun over(
            scope: CoroutineScope,
            slowWrites: Boolean = false,
        ) = TestGraph(Demo(Fixtures.reference, scope), slowWrites = slowWrites)
    }
}

private class Readers {
    private val open = AtomicInteger()
    val count: Int get() = open.get()
    val held = MutableStateFlow(false)

    fun <T> of(flow: Flow<T>): Flow<T> =
        flow {
            held.first { !it }
            emitAll(flow)
        }.onStart { open.incrementAndGet() }.onCompletion { open.decrementAndGet() }
}

private class ListReads {
    val whole = AtomicInteger()
    val titles: MutableSet<AnimeId> = ConcurrentHashMap.newKeySet()
    val sets = CopyOnWriteArrayList<Set<AnimeId>>()
}

private class CountedList(
    private val inner: ListRepository,
    private val readers: Readers,
    private val syncs: AtomicInteger,
    private val reads: ListReads,
) : ListRepository by inner {
    override suspend fun sync(): Outcome<Unit> {
        syncs.incrementAndGet()
        return inner.sync()
    }

    override fun observeEntries(): Flow<List<ListEntry>> = readers.of(inner.observeEntries()).onStart { reads.whole.incrementAndGet() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = readers.of(inner.observeEntry(id)).onStart { reads.titles += id }

    override fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> =
        readers.of(inner.observeEntriesOf(ids)).onStart { reads.sets += ids }
}

private class CountedAnime(
    private val inner: AnimeRepository,
    private val readers: Readers,
    private val refreshes: AtomicInteger,
    private val unstored: StateFlow<Boolean>,
    private val unreadable: StateFlow<Boolean>,
    private val failing: StateFlow<Set<AnimeId>>,
) : AnimeRepository by inner {
    val refreshed = CopyOnWriteArrayList<AnimeId>()
    val requested = CopyOnWriteArrayList<AnimeId>()
    val detailQueries = CopyOnWriteArrayList<Set<AnimeId>>()

    override fun observeAnime(id: AnimeId): Flow<Anime?> = readers.of(stored(inner.observeAnime(id)))

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = readers.of(stored(inner.observeDetails(id)))

    override fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> =
        readers.of(stored(inner.observeDetailsOf(ids)).map { it.orEmpty() }).onStart { detailQueries += ids }

    override fun observeRefreshedAt(id: AnimeId): Flow<Instant?> = readers.of(stored(inner.observeRefreshedAt(id)))

    override fun observePagesRead(): Flow<Set<AnimeId>> = readers.of(stored(inner.observePagesRead()).map { it.orEmpty() })

    private fun <T> stored(flow: Flow<T?>): Flow<T?> =
        combine(flow, unstored, unreadable) { value, unstored, unreadable ->
            if (unreadable) throw DataException(DataError.StorageError)
            value.takeUnless { unstored }
        }

    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
        refreshes.incrementAndGet()
        requested += id
        val result = if (id in failing.value) Outcome.Failure(DataError.NoConnection) else inner.refresh(id)
        refreshed += id
        return result
    }
}

private class CountedAiring(
    private val inner: AiringRepository,
    private val readers: Readers,
) : AiringRepository by inner {
    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = readers.of(inner.observeAiring())

    override fun observePageAiring(page: Anime): Flow<Map<AnimeId, Airing>> = readers.of(inner.observePageAiring(page))
}

private class CountedSession(
    private val inner: SessionRepository,
    private val readers: Readers,
) : SessionRepository by inner {
    override fun observeSession(): Flow<Session> = readers.of(inner.observeSession())
}

private class LateList(
    private val inner: ListRepository,
) : ListRepository by inner {
    override fun observeEntries(): Flow<List<ListEntry>> = inner.observeEntries().late()

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = inner.observeEntry(id).late()

    override fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> = inner.observeEntriesOf(ids).late()

    // The first value is a read of what is there now; every later one is a change, heard late.
    private fun <T> Flow<T>.late(): Flow<T> = withIndex().onEach { if (it.index > 0) delay(LATE) }.map { it.value }

    private companion object {
        val LATE = 5.milliseconds
    }
}

private class SlowWrites(
    private val inner: ListRepository,
) : ListRepository by inner {
    override suspend fun write(write: ListWrite): ListChange? {
        delay(SLOW)
        return inner.write(write)
    }

    private companion object {
        val SLOW = 5.milliseconds
    }
}

// The demo's work in the test's scheduler as foreground work, so advanceUntilIdle waits for a sync or a write too;
// the caller cancels it.
fun TestScope.foregroundScope(): CoroutineScope = CoroutineScope(coroutineContext.minusKey(Job) + Job())

// A screen collecting [state] until the returned job is cancelled.
fun TestScope.showing(vararg flows: Flow<*>): Job = backgroundScope.launch { flows.forEach { launch { it.collect() } } }

// View models run their work on Dispatchers.Main, which a plain JVM test has to provide.
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher,
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
