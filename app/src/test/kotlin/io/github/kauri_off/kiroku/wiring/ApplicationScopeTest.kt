package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.rules.KotlinSource
import io.github.kauri_off.kiroku.rules.appSources
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// Section 2.3: a report never stops the app, also when the work that failed did not catch for itself.
@RunWith(RobolectricTestRunner::class)
class ApplicationScopeTest {
    private val reported = CopyOnWriteArrayList<Throwable>()

    // What would end the process: on a device, the uncaught exception handler of the thread that failed.
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun watchTheProcess() = Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }

    @After
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(original)

    @Test
    fun `an exception escaping a coroutine is reported, ends nothing and leaves the scope working`() =
        runBlocking {
            val scope = applicationScope(reported::add)
            try {
                val failure = SecurityException()
                scope.launch { throw failure }.join()
                assertEquals(listOf<Throwable>(failure), reported)
                assertEquals(emptyList<Throwable>(), uncaught)
                assertTrue(scope.isActive)
                assertEquals(1, scope.async { 1 }.await())
            } finally {
                scope.cancel()
            }
        }

    // As the connection is shared: a flow whose sharing fails is reported, and its subscribers keep the last value.
    @Test
    fun `a shared flow whose source fails is reported and keeps what it last had`() =
        runBlocking {
            val scope = applicationScope(reported::add)
            try {
                val failure = IllegalStateException()
                val shared =
                    flow {
                        emit(false)
                        throw failure
                    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)
                assertEquals(false, shared.first())
                withTimeout(5_000) { while (reported.isEmpty()) delay(1) }
                assertEquals(listOf<Throwable>(failure), reported)
                assertEquals(false, shared.replayCache.single())
                assertEquals(emptyList<Throwable>(), uncaught)
            } finally {
                scope.cancel()
            }
        }

    // Thrown back by the handler, an Error ends the process as it would anywhere else.
    @Test
    fun `an Error is not taken for a report`() {
        val scope = applicationScope(reported::add)
        val error = AssertionError()
        val thrown = runCatching { scope.coroutineContext[CoroutineExceptionHandler]!!.handleException(scope.coroutineContext, error) }
        assertSame(error, thrown.exceptionOrNull())
        assertEquals(emptyList<Throwable>(), reported)
        scope.cancel()
    }

    // Every source's graph is built on this scope, so its handler is the build's reporter whatever the source.
    @Test
    fun `the application makes its scope once, with the build's reporter`() {
        val making = Regex("""\bapplicationScope\s*\(""")
        val made =
            appSources()
                .flatMap { KotlinSource(it.readText()).code.lines() }
                .filter { making.containsMatchIn(it) && "fun applicationScope" !in it }
                .map { it.trim() }
        assertEquals(listOf("private val scope = applicationScope(reportUnexpected)"), made)
    }
}
