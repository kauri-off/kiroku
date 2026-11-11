package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

// Demo mode over the reference fixtures, for tests in either build type.
class TestGraph(
    val demo: Demo,
) : AppGraph {
    override val clock = demo.clock
    override val session = demo.session
    override val list = demo.list
    override val anime = demo.anime
    override val airing = demo.airing
    override val search = demo.search
    override val user = demo.user

    companion object {
        fun over(scope: CoroutineScope) = TestGraph(Demo(Fixtures.reference, scope))
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
