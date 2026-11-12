package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration.Companion.milliseconds

// With [lateObservers], as with a database, an observer hears of a change after the write and a fresh read see it;
// with [slowWrites], a write takes time before it is applied; while [listUnreadable], the list cannot be read.
class TestGraph(
    val demo: Demo,
    lateObservers: Boolean = false,
    slowWrites: Boolean = false,
) : AppGraph {
    override val clock = demo.clock
    override val session = demo.session
    private val reads = UnreadableList(demo.list)
    override val list: ListRepository =
        reads.let { if (lateObservers) LateList(it) else it }.let { if (slowWrites) SlowWrites(it) else it }

    var listUnreadable: Boolean
        get() = reads.unreadable
        set(value) {
            reads.unreadable = value
        }
    override val anime = demo.anime
    override val airing = demo.airing
    override val search = demo.search
    override val user = demo.user

    companion object {
        fun over(
            scope: CoroutineScope,
            slowWrites: Boolean = false,
        ) = TestGraph(Demo(Fixtures.reference, scope), slowWrites = slowWrites)
    }
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

private class UnreadableList(
    private val inner: ListRepository,
) : ListRepository by inner {
    @Volatile
    var unreadable = false

    override fun observeEntries(): Flow<List<ListEntry>> = flow { emitAll(readable { inner.observeEntries() }) }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = flow { emitAll(readable { inner.observeEntry(id) }) }

    override suspend fun sync(): Outcome<Unit> = if (unreadable) Outcome.Failure(DataError.StorageError) else inner.sync()

    private fun <T> readable(flow: () -> Flow<T>): Flow<T> = if (unreadable) throw DataException(DataError.StorageError) else flow()
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

// View models run their work on Dispatchers.Main, which a plain JVM test has to provide.
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher,
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
