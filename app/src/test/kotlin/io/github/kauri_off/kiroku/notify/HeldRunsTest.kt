package io.github.kauri_off.kiroku.notify

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HeldRunsTest {
    private val runs = RecordedRuns()
    private var done = 0

    private fun TestScope.held(
        engine: () -> NotificationRuns? = {
            runs
        },
    ) = HeldRuns(CoroutineScope(StandardTestDispatcher(testScheduler)), runs = engine)

    @Test
    fun `each trigger starts one run and is held until that run has ended`() =
        runTest {
            val held = held()
            for (trigger in RunTrigger.entries) {
                val until = CompletableDeferred<Unit>().also { runs.until = it }
                done = 0
                held.start(trigger) { done++ }
                runCurrent()
                assertEquals("$trigger", listOf(trigger), runs.started.toList())
                advanceTimeBy(HELD_FOR - 1.milliseconds)
                assertEquals("$trigger held while its run goes on", 0, done)
                until.complete(Unit)
                runCurrent()
                assertEquals("$trigger let go once its run has ended", 1, done)
                assertEquals(listOf(trigger), runs.ended.toList())
                advanceTimeBy(HELD_FOR)
                assertEquals("$trigger let go once", 1, done)
                runs.started.clear()
                runs.ended.clear()
            }
        }

    @Test
    fun `a run longer than a receiver is given is let go at that time and goes on to its end`() =
        runTest {
            val until = CompletableDeferred<Unit>().also { runs.until = it }
            held().start(RunTrigger.Alarm) { done++ }
            advanceTimeBy(HELD_FOR - 1.milliseconds)
            assertEquals(0, done)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(1, done)
            assertTrue("well under the minute a receiver is given", HELD_FOR <= 30.seconds)
            assertEquals(emptyList<RunTrigger>(), runs.ended.toList())
            until.complete(Unit)
            runCurrent()
            assertEquals(listOf(RunTrigger.Alarm), runs.ended.toList())
            assertEquals(1, done)
        }

    @Test
    fun `a run that throws and a graph without notifications are let go as well`() =
        runTest {
            val thrown = mutableListOf<Throwable>()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> thrown += e })
            val failing =
                object : NotificationRuns {
                    override suspend fun run(trigger: RunTrigger): RunReport = throw IllegalStateException()
                }
            HeldRuns(scope) { failing }.start(RunTrigger.DeviceRestarted) { done++ }
            runCurrent()
            assertEquals(1, done)
            assertEquals(1, thrown.size)
            held { null }.start(RunTrigger.AppUpdated) { done++ }
            runCurrent()
            assertEquals(2, done)
        }

    @Test
    fun `the engine is asked for off the thread that starts a run`() =
        runTest {
            var asked = 0
            val held = held { runs.also { asked++ } }
            held.start(RunTrigger.AppOpened)
            assertEquals("built by the caller", 0, asked)
            runCurrent()
            assertEquals(1, asked)
        }

    @Test
    fun `each time the app comes to the foreground one run is started`() =
        runTest {
            val owner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry.createUnsafe(this)
                    override val lifecycle: Lifecycle get() = registry
                }
            held().atEachOpening(owner.lifecycle)
            runCurrent()
            assertEquals(emptyList<RunTrigger>(), runs.started.toList())
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(listOf(RunTrigger.AppOpened), runs.started.toList())
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(listOf(RunTrigger.AppOpened, RunTrigger.AppOpened), runs.started.toList())
        }
}
