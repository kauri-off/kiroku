package io.github.kauri_off.kiroku.recorder

import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.session.TokensNotRead
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

// A title that a recording left on the list is dealt with when the app's graph is made: nothing has to ask for the recorder.
@RunWith(RobolectricTestRunner::class)
class RecorderStartTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob())
    private val device =
        object : Device {
            override fun isOnline() = true

            override fun observeOnline() = flowOf(true)

            override fun isMainThread() = false
        }
    private val unexpected = CopyOnWriteArrayList<Throwable>()

    private fun parts() = LiveParts(context, LiveSource.Account, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device)

    // Tokens no key opens: the session reports them when it is first read, which only a request of the recorder's does here.
    private fun LiveParts.sealedTokens() = file("tokens").writeBytes(byteArrayOf(1) + SIGN_IN.encodeToByteArray() + ByteArray(40))

    private fun reportedWithin(millis: Long): Boolean =
        runBlocking { withTimeoutOrNull(millis) { while (unexpected.isEmpty()) delay(20) } != null }

    @After
    fun close() = scope.cancel()

    @Test
    fun `making the graph of a source with a title left on the list sets about removing it, with the recorder never asked for`() {
        val parts = parts()
        parts.sealedTokens()
        parts.file("recording").writeText("""{"id":5,"episodes":12,"name":"A finished title","user":"nobody"}""")

        LiveGraph(parts)

        assertTrue(reportedWithin(WAIT))
        assertTrue(unexpected.single() is TokensNotRead)
        assertTrue(parts.file("recording").exists())
    }

    @Test
    fun `without a title left, making the graph reads no session and asks nothing`() {
        val parts = parts()
        parts.sealedTokens()

        LiveGraph(parts)

        assertEquals(false, reportedWithin(QUIET))
    }

    private companion object {
        const val WAIT = 15_000L
        const val QUIET = 1_500L
        const val SIGN_IN = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
