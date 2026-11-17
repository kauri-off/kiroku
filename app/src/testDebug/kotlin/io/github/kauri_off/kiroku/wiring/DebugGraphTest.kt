package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.profile.profileStandIn
import io.github.kauri_off.kiroku.sandbox.SandboxGraph
import io.github.kauri_off.kiroku.search.searchStandIn
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.TimeZone

// The graph a debug or preview build starts with, by the client id it was built with and the source chosen.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DebugGraphTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val device = Clock.offset(FixtureTime.clock, Duration.ofDays(7 * 30 + 2))
    private val context get() = RuntimeEnvironment.getApplication()

    // The graph of a process that starts now.
    private fun TestScope.started(
        clientId: String,
        clock: Clock = device,
    ) = createGraph(context, clientId, clock, backgroundScope)

    @Test
    fun `a debug build without a client id runs on the demo data of the device's week`() =
        runTest {
            for (id in listOf("", "  ")) {
                val graph = started(id)
                assertTrue(graph is DemoGraph)
                assertEquals(Fixtures.at(device).entries, graph.list.observeEntries().first())
                assertEquals(Fixtures.at(device).now, graph.clock.instant())
            }
        }

    @Test
    fun `the demo clock follows the device's time zone`() =
        runTest {
            val original = TimeZone.getDefault()
            try {
                val graph = started("", DeviceClock)
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
                assertEquals(ZoneId.of("Asia/Tokyo"), graph.clock.zone)
                TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
                assertEquals(ZoneId.of("Europe/Lisbon"), graph.clock.zone)
            } finally {
                TimeZone.setDefault(original)
            }
        }

    @Test
    fun `with a client id a debug build starts on the real account, and after the choice on the demo`() =
        runTest {
            val first = started("0123abcd")
            assertTrue(first is LiveGraph)
            assertEquals(Session.SignedOut, first.session.observeSession().first())
            assertEquals(emptyList<ListEntry>(), first.list.observeEntries().first())

            assertTrue(SourceChoice(context).choose(DataSource.Demo))
            val next = started("0123abcd")
            assertTrue(next is DemoGraph)
            assertEquals(Fixtures.at(device).entries, next.list.observeEntries().first())
        }

    // The sandbox reads its files at its first request: a start reads none, and holds no sign-in of the real account.
    @Test
    fun `after the choice a debug build starts on the sandbox, with a client id and without, and nothing is asked before a request`() =
        runTest {
            assertTrue(SourceChoice(context).choose(DataSource.Sandbox))
            for (id in listOf("0123abcd", "")) {
                // One process holds one graph: this start's work ends before the next one opens the same files.
                val process = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
                val graph = createGraph(context, id, device, process, live = { error("The sandbox started the real account") })
                assertTrue(graph is SandboxGraph)
                assertEquals(emptyList<String>(), (graph as SandboxGraph).sandbox.mal.asked)
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                assertTrue(graph.signIn.canStart)
                process.coroutineContext.job.cancelAndJoin()
            }
            assertFalse(
                context.noBackupFilesDir
                    .list()
                    .orEmpty()
                    .any { it.startsWith("sandbox.mal") },
            )
        }

    // Search and Profile over the real account show what a release shows, so no fixture title is edited there.
    @Test
    fun `fixture stand-ins are on the demo and the sandbox, and not on the real account`() =
        runTest {
            val graphs =
                DataSource.entries.associateWith { source ->
                    assertTrue(SourceChoice(context).choose(source))
                    started("0123abcd")
                }
            val standingIn = graphs.filterValues { searchStandIn(it) != null }.keys
            assertEquals(setOf(DataSource.Demo, DataSource.Sandbox), standingIn)
            assertEquals(standingIn, graphs.filterValues { profileStandIn(it) != null }.keys)
            assertTrue(graphs.getValue(DataSource.Account) is LiveGraph)
        }

    @Test
    fun `without a client id a debug build can be told to start on the real account`() =
        runTest {
            assertTrue(SourceChoice(context).choose(DataSource.Account))
            val graph = started("")
            assertFalse(graph is DemoGraph)
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
        }

    // The live graph alone makes the HTTP client, so a start that never asks for it has none.
    @Test
    fun `a start on the demo builds no live graph, with a client id and without`() =
        runTest {
            assertTrue(SourceChoice(context).choose(DataSource.Demo))
            for (id in listOf("0123abcd", "")) {
                val graph = createGraph(context, id, device, backgroundScope, live = { error("The demo built a live graph") })
                assertTrue(graph is DemoGraph)
                assertEquals(Outcome.Success(Unit), graph.list.sync())
            }
        }

    @Test
    fun `a +1 made through the demo list reaches an observer of that list`() =
        runTest {
            val graph = started("")
            val seen = mutableListOf<List<ListEntry>>()
            backgroundScope.launch { graph.list.observeEntries().collect { seen += it } }
            runCurrent()
            val anime = FixtureAnime.exactToWatch
            val before =
                seen
                    .last()
                    .single { it.anime.id == anime.id }
                    .values.watchedEpisodes

            graph.list.write(ListWrite.MarkNextWatched(anime))
            runCurrent()

            assertEquals(
                before + 1,
                seen
                    .last()
                    .single { it.anime.id == anime.id }
                    .values.watchedEpisodes,
            )
        }
}
