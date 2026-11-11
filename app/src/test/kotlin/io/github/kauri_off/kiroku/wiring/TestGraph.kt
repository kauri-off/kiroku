package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.repository.ListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration.Companion.milliseconds

// Demo mode over the reference fixtures, for tests in either build type. With [lateObservers], observers of the list
// hear of a change a moment after the write returns, as with a database.
class TestGraph(
    val demo: Demo,
    lateObservers: Boolean = false,
) : AppGraph {
    override val clock = demo.clock
    override val session = demo.session
    override val list: ListRepository = if (lateObservers) LateList(demo.list) else demo.list
    override val anime = demo.anime
    override val airing = demo.airing
    override val search = demo.search
    override val user = demo.user

    companion object {
        fun over(scope: CoroutineScope) = TestGraph(Demo(Fixtures.reference, scope))
    }
}

private class LateList(
    private val inner: ListRepository,
) : ListRepository by inner {
    override fun observeEntries(): Flow<List<ListEntry>> = inner.observeEntries().onEach { delay(LATE) }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = inner.observeEntry(id).onEach { delay(LATE) }

    private companion object {
        val LATE = 5.milliseconds
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
