package io.github.kauri_off.kiroku.wiring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

// A part built at its first use whose build meets the end of the graph's scope, as a screen's first read can.
class OnFirstUseTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val screens = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e })
    private val building = CountDownLatch(1)
    private val release = CountDownLatch(1)

    // An engine built on the scope checks that it is still active, as each engine does.
    private val part =
        lazy {
            building.countDown()
            release.await()
            check(scope.isActive) { "The scope has ended" }
        }

    // A screen left while its first read was building, and then the graph's work ended: nothing reaches the next test.
    @Test
    fun `a caller cancelled while the part was building ends cancelled when the build meets the scope's end`() {
        val caller = screens.launch { part.onFirstUse(scope) }
        building.await()
        caller.cancel()
        scope.cancel()
        release.countDown()
        runBlocking { caller.join() }

        assertTrue(caller.isCancelled)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    // A screen still shown over a graph whose work has ended is told so.
    @Test
    fun `a live caller outside the scope is told that the scope has ended`() {
        val caller = screens.async { part.onFirstUse(scope) }
        building.await()
        scope.cancel()
        release.countDown()

        val failure = runBlocking { runCatching { caller.await() }.exceptionOrNull() }
        assertTrue("$failure", failure is IllegalStateException && failure !is CancellationException)
    }
}
