package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.rules.appSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

// Nothing of the network or of the list's store is built before its first use, and each source has its own files.
@RunWith(RobolectricTestRunner::class)
class LiveGraphTest {
    private val context get() = RuntimeEnvironment.getApplication()

    // The HTTP client asks whether it is on the main thread exactly when it is built.
    private class CountingDevice : Device {
        val clientBuilds = AtomicInteger()

        override fun isOnline() = true

        override fun isMainThread(): Boolean {
            clientBuilds.incrementAndGet()
            return false
        }
    }

    private val device = CountingDevice()
    private val stores = AtomicInteger()
    private val unexpected = mutableListOf<Throwable>()

    private fun parts(
        scope: CoroutineScope,
        source: LiveSource = LiveSource.Account,
    ) = LiveParts(context, source, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device)

    private fun store(): LocalList {
        stores.incrementAndGet()
        return InMemoryLocalList()
    }

    @Test
    fun `reading the session builds neither the HTTP client nor the list's store, and each is built when it is first used`() =
        runTest {
            val graph = LiveGraph(parts(backgroundScope), listStore = { store() })

            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0, device.clientBuilds.get())
            assertEquals(0, stores.get())

            // Naming the list builds nothing either: its store is built by the first read.
            val list = graph.list
            assertEquals(0, stores.get())
            assertEquals(emptyList<ListEntry>(), list.observeEntries().first())
            assertEquals(emptyList<ListEntry>(), list.observeEntries().first())
            assertEquals(1, stores.get())
            assertEquals(0, device.clientBuilds.get())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `an area is built once, when it is first asked for`() =
        runTest {
            val graph = LiveGraph(parts(backgroundScope), listStore = { store() })
            assertSame(graph.session, graph.session)
            assertSame(graph.list, graph.list)
            assertSame(graph.anime, graph.anime)
        }

    @Test
    fun `the files of two sources differ in name, not only in place`() =
        runTest {
            val account = parts(backgroundScope).file("tokens")
            val other = parts(backgroundScope, LiveSource("sandbox", MalHosts.Real)).file("tokens")
            assertEquals("account.tokens", account.name)
            assertNotEquals(account.name, other.name)
            assertEquals(context.noBackupFilesDir, account.parentFile)
            assertTrue(other.name.startsWith("sandbox."))
        }

    @Test
    fun `the session of a source is read from that source's own file`() =
        runTest {
            val parts = parts(backgroundScope)
            // An expired sign-in, as the session writes it: a zero byte and the sign-in's id.
            val signIn = "0f8fad5b-d9cb-469f-a165-70867728950e"
            parts.file("tokens").writeBytes(byteArrayOf(0) + signIn.encodeToByteArray())
            assertEquals(Session.Expired(SignInId(signIn)), LiveGraph(parts).session.observeSession().first())
        }

    // So a graph that is not a live graph, the demo's, holds no HTTP client.
    @Test
    fun `the HTTP client is made in the live graph's file and nowhere else`() {
        val making = Regex("""\bMalHttp\s*\(""")
        assertEquals(listOf("LiveGraph.kt"), appSources().filter { making.containsMatchIn(it.readText()) }.map { it.name })
    }
}
