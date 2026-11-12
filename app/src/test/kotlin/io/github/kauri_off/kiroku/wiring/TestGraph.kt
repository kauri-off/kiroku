package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

// Demo mode with the stores' answers made late, slow, held or failing, and their open reads counted.
class TestGraph(
    val demo: Demo,
    // An observer hears of a change after the write and a fresh read see it, as with a database.
    lateObservers: Boolean = false,
    // A write takes time before it is applied.
    slowWrites: Boolean = false,
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
    override val session: SessionRepository = CountedSession(demo.session, counter)
    override val list: ListRepository =
        CountedList(
            demo.list.let { if (lateObservers) LateList(it) else it }.let { if (slowWrites) SlowWrites(it) else it },
            counter,
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

    override val anime: AnimeRepository = CountedAnime(demo.anime, counter, refreshCount, titlesUnstored)
    override val airing: AiringRepository = CountedAiring(demo.airing, counter)
    override val search = demo.search
    override val user = demo.user

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

private class CountedList(
    private val inner: ListRepository,
    private val readers: Readers,
) : ListRepository by inner {
    override fun observeEntries(): Flow<List<ListEntry>> = readers.of(inner.observeEntries())

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = readers.of(inner.observeEntry(id))
}

private class CountedAnime(
    private val inner: AnimeRepository,
    private val readers: Readers,
    private val refreshes: AtomicInteger,
    private val unstored: StateFlow<Boolean>,
) : AnimeRepository by inner {
    override fun observeAnime(id: AnimeId): Flow<Anime?> = readers.of(stored(inner.observeAnime(id)))

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = readers.of(stored(inner.observeDetails(id)))

    private fun <T> stored(flow: Flow<T?>): Flow<T?> = combine(flow, unstored) { value, unstored -> value.takeUnless { unstored } }

    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
        refreshes.incrementAndGet()
        return inner.refresh(id)
    }
}

private class CountedAiring(
    private val inner: AiringRepository,
    private val readers: Readers,
) : AiringRepository by inner {
    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = readers.of(inner.observeAiring())
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
fun TestScope.showing(state: Flow<*>): Job = backgroundScope.launch { state.collect() }

// View models run their work on Dispatchers.Main, which a plain JVM test has to provide.
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher,
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
