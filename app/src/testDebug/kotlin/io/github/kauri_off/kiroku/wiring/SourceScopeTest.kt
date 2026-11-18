package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.sandbox.SandboxGraph
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

// Each source a debug build can start on runs on the application's scope, where nothing that escapes ends the process,
// on a device whose system refuses the connection's callback.
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RefusingConnectivity::class])
class SourceScopeTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val context get() = RuntimeEnvironment.getApplication()
    private val clock = FixtureTime.clock
    private val reported = CopyOnWriteArrayList<Throwable>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val scope = applicationScope(reported::add)

    @Before
    fun watchTheProcess() = Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }

    @After
    fun restore() {
        scope.cancel()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    // The graph's connection ends online, and work of the scope that fails after it is reported while the scope goes on.
    private fun holds(
        graph: AppGraph,
        refused: Boolean,
    ) = runBlocking {
        withTimeout(5_000) { graph.connection.observeOnline().first { it } }
        assertEquals(reported.toString(), if (refused) 1 else 0, reported.count { it is SecurityException })
        val failure = IllegalStateException()
        scope.launch { throw failure }.join()
        assertTrue(failure in reported)
        assertTrue(scope.isActive)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun `the real account`() =
        holds(LiveGraph(LiveParts(context, LiveSource.Account, "0123abcd", clock, scope, reported::add)), refused = true)

    @Test
    fun `the sandbox`() =
        holds(
            SandboxGraph.create(
                context,
                clock,
            ) { source, device, clock -> LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, reported::add, device = device) },
            refused = true,
        )

    // The demo asks the system nothing: its connection is its own.
    @Test
    fun `the demo`() = holds(DemoGraph(Demo.startingAt(clock, scope, reported::add), scope), refused = false)
}
