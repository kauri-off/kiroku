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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// What a release build runs on: it has no other source, so [createGraph] is given none.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GraphSwitchTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC)
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a blank client id counts as absent`() {
        assertNull(malClientId(""))
        assertNull(malClientId("  \t"))
        assertEquals("0123abcd", malClientId(" 0123abcd "))
    }

    private fun onTheRealAccountSignedOut(id: String) =
        runTest {
            val graph = createGraph(context, id, clock, backgroundScope, other = null)
            assertTrue(graph is LiveGraph)
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            assertEquals(clock, graph.clock)
        }

    @Test
    fun `a release build without a client id is on the real account, signed out and with no invented data`() = onTheRealAccountSignedOut("")

    @Test
    fun `a release build with a blank client id is too`() = onTheRealAccountSignedOut(" ")

    @Test
    fun `a release build with a client id is too`() = onTheRealAccountSignedOut("0123abcd")

    @Test
    fun `signed out, the live graph's list refuses a write and a sync, and says that the write failed`() =
        runTest {
            val graph = createGraph(context, "0123abcd", clock, backgroundScope, other = null)
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
}
