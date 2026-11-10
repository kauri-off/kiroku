package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoListTest {
    private val fixtures = Fixtures.reference
    private val today = PartialDate.Day(LocalDate.now(fixtures.clock))

    private fun TestScope.demo() = Demo(fixtures, backgroundScope, latency = 1.seconds)

    private fun TestScope.observe(demo: Demo): List<List<ListEntry>> {
        val seen = mutableListOf<List<ListEntry>>()
        backgroundScope.launch { demo.list.observeEntries().collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun TestScope.failures(demo: Demo): List<WriteFailure> {
        val seen = mutableListOf<WriteFailure>()
        backgroundScope.launch { demo.list.observeWriteFailures().collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun List<List<ListEntry>>.watched(anime: Anime) = last().single { it.anime.id == anime.id }.values.watchedEpisodes

    private fun TestScope.settle() {
        advanceTimeBy(1.minutes)
        runCurrent()
    }

    @Test
    fun `a +1 made through the demo list reaches an observer of that list at once`() =
        runTest {
            val demo = demo()
            val seen = observe(demo)
            assertEquals(12, seen.watched(FixtureAnime.exactToWatch))

            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()

            assertEquals(13, seen.watched(FixtureAnime.exactToWatch))
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            settle()
            assertEquals(
                13,
                demo.server
                    .entry(FixtureAnime.exactToWatch.id)
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `W7 the last episode completes the entry and Undo restores it in one write`() =
        runTest {
            val demo = demo()
            val seen = observe(demo)
            val before = checkNotNull(fixtures.entryFor(FixtureAnime.oneShortOfTotal.id))

            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.oneShortOfTotal))
            settle()
            val completed = seen.last().single { it.anime.id == FixtureAnime.oneShortOfTotal.id }.values
            assertEquals(ListStatus.Completed, completed.status)
            assertEquals(12, completed.watchedEpisodes)
            assertEquals(today, completed.finishDate)
            assertEquals(before.values.startDate, completed.startDate)

            demo.list.write(ListWrite.Save(before.anime, before.values))
            settle()
            val restored = seen.last().single { it.anime.id == FixtureAnime.oneShortOfTotal.id }.values
            assertEquals(before.values, restored)
            assertEquals(
                listOf(ListRequest(before.anime.id, completed), ListRequest(before.anime.id, before.values)),
                demo.server.requests,
            )
            assertEquals(before.values, demo.server.entry(before.anime.id)?.values)
        }

    @Test
    fun `W7 the first episode of a planned title sets Watching and the start date`() =
        runTest {
            val demo = demo()
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.plannedFinished))
            val values = checkNotNull(demo.list.observeEntry(FixtureAnime.plannedFinished.id).first()).values
            assertEquals(ListStatus.Watching, values.status)
            assertEquals(1, values.watchedEpisodes)
            assertEquals(today, values.startDate)
        }

    @Test
    fun `W1 quick edits are sent in order and the server ends with the last value shown`() =
        runTest {
            val demo = demo()
            repeat(3) { demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)) }
            settle()
            assertEquals(listOf(13, 14, 15), demo.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(
                15,
                demo.server
                    .entry(FixtureAnime.exactToWatch.id)
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `W2 a failed write returns to the server's state, drops waiting edits and offers Retry`() =
        runTest {
            val demo = demo()
            val seen = observe(demo)
            val failures = failures(demo)
            val anime = FixtureAnime.exactToWatch
            demo.server.failingWrites.value = true

            demo.list.write(ListWrite.MarkNextWatched(anime))
            demo.list.write(ListWrite.MarkNextWatched(anime))
            runCurrent()
            assertEquals(14, seen.watched(anime))
            val shown = seen.last().single { it.anime.id == anime.id }.values

            settle()
            assertEquals(12, seen.watched(anime))
            assertEquals(listOf(13), demo.server.requests.map { it.values?.watchedEpisodes })
            val failure = failures.single()
            assertEquals(DataError.ServerError, failure.error)
            assertEquals(ListWrite.Save(anime, shown), failure.retry)

            demo.server.failingWrites.value = false
            demo.list.write(failure.retry)
            settle()
            assertEquals(14, seen.watched(anime))
            assertEquals(
                14,
                demo.server
                    .entry(anime.id)
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `a failed removal brings the entry back and Retry removes it`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            val anime = FixtureAnime.droppedMovie
            demo.server.offline.value = true

            demo.list.write(ListWrite.Remove(anime))
            runCurrent()
            assertNull(demo.list.observeEntry(anime.id).first())
            settle()
            assertEquals(demo.server.entry(anime.id), demo.list.observeEntry(anime.id).first())
            assertEquals(WriteFailure(DataError.NoConnection, ListWrite.Remove(anime)), failures.single())
        }

    @Test
    fun `a failure with nothing collecting it is dropped`() =
        runTest {
            val demo = demo()
            demo.server.failingWrites.value = true
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            val failures = failures(demo)
            settle()
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `an added title shows at once and reaches the server`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.notOnListAiring
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            demo.list.write(ListWrite.Save(anime, values))
            assertEquals(
                values,
                demo.list
                    .observeEntry(anime.id)
                    .first()
                    ?.values,
            )
            settle()
            assertEquals(values, demo.server.entry(anime.id)?.values)
        }

    @Test
    fun `N9 send waits for the server and applies the step to the values as they are`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.list.write(ListWrite.MarkNextWatched(anime))

            val sent = demo.list.send(ListWrite.MarkWatched(anime, 14))

            assertEquals(Outcome.Success(Unit), sent)
            assertEquals(listOf(13, 14), demo.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(
                14,
                demo.list
                    .observeEntry(anime.id)
                    .first()
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `N9 send reports a failure and changes nothing`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.server.offline.value = true
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(
                12,
                demo.list
                    .observeEntry(anime.id)
                    .first()
                    ?.values
                    ?.watchedEpisodes,
            )
            assertTrue(demo.server.requests.isEmpty())
        }
}
