package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class DebugGraphTest {
    private val device = Clock.offset(FixtureTime.clock, Duration.ofDays(7 * 30 + 2))

    @Test
    fun `a debug build without a client id runs on the demo data of the device's week`() =
        runTest {
            val graph = createGraph("", device, backgroundScope)
            assertTrue(graph is DemoGraph)
            assertEquals(Fixtures.at(device).entries, graph.list.observeEntries().first())
            assertEquals(Fixtures.at(device).now, graph.clock.instant())
        }

    @Test
    fun `a debug build with a client id does not use demo data`() =
        runTest {
            val graph = createGraph("0123abcd", device, backgroundScope)
            assertFalse(graph is DemoGraph)
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
        }

    @Test
    fun `a +1 made through the demo list reaches an observer of that list`() =
        runTest {
            val graph = createGraph("", device, backgroundScope)
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
