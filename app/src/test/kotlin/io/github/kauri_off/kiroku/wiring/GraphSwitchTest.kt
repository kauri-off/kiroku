package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GraphSwitchTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `a blank client id counts as absent`() {
        assertNull(malClientId(""))
        assertNull(malClientId("  \t"))
        assertEquals("0123abcd", malClientId(" 0123abcd "))
    }

    @Test
    fun `a release build without a client id is signed out and shows no invented data`() =
        runTest {
            for (id in listOf("", " ")) {
                val graph = createGraph(id, clock, backgroundScope, demo = null)
                assertTrue(graph is LiveGraph)
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
                assertEquals(clock, graph.clock)
            }
        }

    @Test
    fun `signed out, the live graph's list refuses a write and a sync, and says that the write failed`() =
        runTest {
            val graph = createGraph("0123abcd", clock, backgroundScope, demo = null)
            val failures = mutableListOf<WriteFailure>()
            backgroundScope.launch { graph.list.observeWriteFailures().collect { failures += it } }
            runCurrent()
            val write = ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)

            assertNull(graph.list.write(write))
            runCurrent()

            assertEquals(listOf(WriteFailure(DataError.SessionExpired, write, list = null)), failures)
            assertEquals(Outcome.Failure(DataError.SessionExpired), graph.list.sync())
            assertEquals(Outcome.Failure(DataError.SessionExpired), graph.list.send(write))
        }

    @Test
    fun `a release build with a client id is not in demo mode`() =
        runTest {
            assertTrue(createGraph("0123abcd", clock, backgroundScope, demo = null) is LiveGraph)
        }
}
