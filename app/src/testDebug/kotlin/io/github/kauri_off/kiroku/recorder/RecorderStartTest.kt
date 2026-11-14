package io.github.kauri_off.kiroku.recorder

import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.recorder.Halt
import io.github.kauri_off.kiroku.data.recorder.ListAfter
import io.github.kauri_off.kiroku.data.recorder.RecordedTitle
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.rules.appSources
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

// A title that a recording left on the list is dealt with when the app's graph is made, not when the menu is opened.
@RunWith(RobolectricTestRunner::class)
class RecorderStartTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob())
    private val clientBuilds = AtomicInteger()
    private val device =
        object : Device {
            override fun isOnline() = true

            override fun observeOnline() = flowOf(true)

            override fun isMainThread(): Boolean {
                clientBuilds.incrementAndGet()
                return false
            }
        }
    private val unexpected = mutableListOf<Throwable>()

    private fun parts() = LiveParts(context, LiveSource.Account, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device)

    @After
    fun close() = scope.cancel()

    // Nobody is signed in here, so the session answers before any request is made and no host is reached.
    @Test
    fun `the graph of a source whose file names a title left on the list tries at once to take it off`() {
        val parts = parts()
        parts.file("recording").writeText("""{"id":5,"episodes":12,"name":"A finished title","user":"nobody"}""")

        val state = runBlocking { withTimeout(WAIT) { LiveGraph(parts).recorder.state.first { it is Recording.Leftover } } }

        assertEquals(Recording.Leftover(RecordedTitle(5, "A finished title", 12), ListAfter.Left, Halt.NotSignedIn), state)
        assertTrue(parts.file("recording").exists())
        assertEquals(0, clientBuilds.get())
        assertEquals(emptyList<Throwable>(), unexpected)
    }

    @Test
    fun `the graph makes its recorder with itself, so nothing has to ask for it first`() {
        val graph = appSources().single { it.name == "LiveGraph.kt" }.readText()

        assertTrue(graph, Regex("""\n    val recorder = liveRecorder\(parts, built\)\n""").containsMatchIn(graph))
    }

    private companion object {
        const val WAIT = 15_000L
    }
}
