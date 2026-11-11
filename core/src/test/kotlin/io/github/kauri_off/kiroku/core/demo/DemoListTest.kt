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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
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

    private fun Demo.serverWatched(anime: Anime) = server.entry(anime.id)?.values?.watchedEpisodes

    private suspend fun Demo.shownWatched(anime: Anime) =
        list
            .observeEntry(anime.id)
            .first()
            ?.values
            ?.watchedEpisodes

    private fun Demo.editOnWebsite(
        anime: Anime,
        watched: Int?,
    ) = server.editOnWebsite(anime, watched?.let { checkNotNull(server.entry(anime.id)).values.copy(watchedEpisodes = it) })

    private fun TestScope.childScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

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
    fun `W3 each of several quick writes reports its own change, so Undo of the last takes back only that one`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            val changes = List(3) { async(start = CoroutineStart.UNDISPATCHED) { demo.list.write(ListWrite.MarkNextWatched(anime)) } }
            val counts =
                changes.map {
                    checkNotNull(it.await()).let { c ->
                        c.before!!.values.watchedEpisodes to
                            c.after!!.values.watchedEpisodes
                    }
                }
            assertEquals(listOf(12 to 13, 13 to 14, 14 to 15), counts)
            demo.list.write(checkNotNull(changes.last().await()).undo)
            assertEquals(14, demo.shownWatched(anime))
            assertNull(demo.list.write(ListWrite.Save(anime, checkNotNull(demo.list.observeEntry(anime.id).first()).values)))
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
            demo.server.writeLatency = { if (it.values?.watchedEpisodes == 13) 5.seconds else 1.seconds }
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

    @Test
    fun `N9 send never reports success for a step that a later failing edit only showed`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            val sent = async { demo.list.send(ListWrite.MarkWatched(anime, 13)) }
            runCurrent()
            demo.list.write(ListWrite.MarkNextWatched(anime))
            demo.server.failingWrites.value = true

            settle()

            assertEquals(Outcome.Failure(DataError.ServerError), sent.await())
            assertEquals(12, demo.serverWatched(anime))
            assertEquals(12, demo.shownWatched(anime))
        }

    @Test
    fun `N9 send compares with the server's confirmed state`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.editOnWebsite(anime, 13)

            assertEquals(Outcome.Success(Unit), demo.list.send(ListWrite.MarkWatched(anime, 13)))

            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(13, demo.serverWatched(anime))
            assertEquals(13, demo.shownWatched(anime))
        }

    @Test
    fun `N9 a sync that already brought the change does not raise the count again`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.editOnWebsite(anime, 13)
            assertEquals(Outcome.Success(Unit), demo.list.sync())
            assertEquals(13, demo.shownWatched(anime))

            assertEquals(Outcome.Success(Unit), demo.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(Outcome.Success(Unit), demo.list.send(ListWrite.MarkWatched(anime, 13)))

            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(13, demo.shownWatched(anime))
        }

    @Test
    fun `a sync brings changes made on the website, removals included`() =
        runTest {
            val demo = demo()
            val raised = FixtureAnime.exactToWatch
            val removed = FixtureAnime.droppedMovie
            val added = FixtureAnime.notOnListAiring
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            demo.editOnWebsite(raised, 15)
            demo.editOnWebsite(removed, null)
            demo.server.editOnWebsite(added, values)
            assertEquals(12, demo.shownWatched(raised))
            assertEquals(fixtures.entryFor(removed.id), demo.list.observeEntry(removed.id).first())

            assertEquals(Outcome.Success(Unit), demo.list.sync())

            assertEquals(15, demo.shownWatched(raised))
            assertNull(demo.list.observeEntry(removed.id).first())
            assertEquals(
                values,
                demo.list
                    .observeEntry(added.id)
                    .first()
                    ?.values,
            )
            assertEquals(
                demo.server.entries().toSet(),
                demo.list
                    .observeEntries()
                    .first()
                    .toSet(),
            )
        }

    @Test
    fun `W4 a sync never reverts an edit made while it was running`() =
        runTest {
            val demo = demo()
            val edited = FixtureAnime.exactToWatch
            val added = FixtureAnime.notOnListAiring
            val removed = FixtureAnime.droppedMovie
            val other = FixtureAnime.exactCaughtUp
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            demo.editOnWebsite(other, 9)

            val sync = async { demo.list.sync() }
            advanceTimeBy(500.milliseconds)
            demo.list.write(ListWrite.MarkNextWatched(edited))
            demo.list.write(ListWrite.Save(added, values))
            demo.list.write(ListWrite.Remove(removed))
            advanceTimeBy(1100.milliseconds)
            assertEquals(13, demo.serverWatched(edited))

            assertEquals(Outcome.Success(Unit), sync.await())
            assertEquals(1, demo.server.listDownloads)
            assertEquals(13, demo.shownWatched(edited))
            assertEquals(
                values,
                demo.list
                    .observeEntry(added.id)
                    .first()
                    ?.values,
            )
            assertNull(demo.list.observeEntry(removed.id).first())
            assertEquals(9, demo.shownWatched(other))
        }

    @Test
    fun `W4 a sync does not revert an earlier edit whose request completed while it ran`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.server.writeLatency = { 2.seconds }
            demo.list.write(ListWrite.MarkNextWatched(anime))
            advanceTimeBy(100.milliseconds)

            val sync = async { demo.list.sync() }
            settle()

            assertEquals(Outcome.Success(Unit), sync.await())
            assertEquals(13, demo.serverWatched(anime))
            assertEquals(13, demo.shownWatched(anime))
        }

    @Test
    fun `W6 one list sync runs at a time, and calls made while one runs share one follow-up`() =
        runTest {
            val demo = demo()
            val syncs = List(3) { async { demo.list.sync() } }
            advanceTimeBy(2500.milliseconds)
            assertEquals(1, demo.server.listDownloads)
            settle()
            syncs.forEach { assertEquals(Outcome.Success(Unit), it.await()) }
            assertEquals(2, demo.server.listDownloads)

            assertEquals(Outcome.Success(Unit), demo.list.sync())
            assertEquals(3, demo.server.listDownloads)
        }

    @Test
    fun `a sync requested while one runs shows a website change made after the running one downloaded`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            val first = async { demo.list.sync() }
            advanceTimeBy(1500.milliseconds)
            assertEquals(1, demo.server.listDownloads)
            demo.editOnWebsite(anime, 20)

            assertEquals(Outcome.Success(Unit), demo.list.sync())

            assertEquals(Outcome.Success(Unit), first.await())
            assertEquals(20, demo.shownWatched(anime))
        }

    @Test
    fun `once the scope has ended, waiting calls fail and shown edits return to the server's state`() =
        runTest {
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val demo = Demo(fixtures, scope, latency = 1.seconds)
            val anime = FixtureAnime.exactToWatch
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            demo.list.write(ListWrite.MarkNextWatched(anime))
            val sent = async { runCatching { demo.list.send(ListWrite.MarkWatched(anime, 14)) } }
            runCurrent()
            assertEquals(13, demo.shownWatched(anime))

            scope.cancel()
            runCurrent()

            assertTrue(sent.await().exceptionOrNull() is IllegalStateException)
            assertEquals(12, demo.shownWatched(anime))
            assertEquals(fixtures.entryFor(FixtureAnime.exactCaughtUp.id), demo.list.observeEntry(FixtureAnime.exactCaughtUp.id).first())
            assertTrue(runCatching { demo.list.write(ListWrite.MarkNextWatched(anime)) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { demo.list.send(ListWrite.MarkWatched(anime, 13)) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { demo.list.sync() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { Demo(fixtures, scope) }.exceptionOrNull() is IllegalStateException)
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
        }

    @Test
    fun `signed out, writes and sends reach no server`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            val anime = FixtureAnime.exactToWatch
            demo.session.signOut()

            demo.list.write(ListWrite.MarkNextWatched(anime))
            assertEquals(Outcome.Failure(DataError.SessionExpired), demo.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(Outcome.Failure(DataError.SessionExpired), demo.list.sync())
            settle()

            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(listOf(WriteFailure(DataError.SessionExpired, ListWrite.MarkNextWatched(anime))), failures)
        }

    @Test
    fun `an edit waiting when the user signs out is not sent and reports no failure`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            demo.session.signOut()
            settle()
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `a failed removal keeps the entry's place in the list`() =
        runTest {
            val demo = demo()
            demo.server.offline.value = true
            demo.list.write(ListWrite.Remove(FixtureAnime.exactCaughtUp))
            settle()
            assertEquals(fixtures.entries, demo.list.observeEntries().first())
        }

    @Test
    fun `Undo of an edit whose request fails still reports the failure, with a Retry equal to the server's state`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            val anime = FixtureAnime.exactToWatch
            val before = checkNotNull(fixtures.entryFor(anime.id))
            demo.server.failingWrites.value = true
            demo.list.write(ListWrite.MarkNextWatched(anime))
            demo.list.write(ListWrite.Save(anime, before.values))
            settle()

            assertEquals(listOf(WriteFailure(DataError.ServerError, ListWrite.Save(anime, before.values))), failures)
            assertEquals(12, demo.shownWatched(anime))
            assertEquals(listOf(13), demo.server.requests.map { it.values?.watchedEpisodes })
        }

    @Test
    fun `refused writes fail like any other`() =
        runTest {
            val demo = demo()
            val failures = failures(demo)
            demo.server.refusing.value = true
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(DataError.Refused, failures.single().error)
            assertEquals(Outcome.Failure(DataError.Refused), demo.list.sync())
        }

    @Test
    fun `an edit handed to an idle worker returns to the server's state when the scope ends`() =
        runTest {
            val scope = childScope()
            val demo = Demo(fixtures, scope, latency = 1.seconds)
            val failures = failures(demo)
            val anime = FixtureAnime.exactToWatch
            runCurrent()

            demo.list.write(ListWrite.MarkNextWatched(anime))
            scope.cancel()
            runCurrent()

            assertEquals(12, demo.shownWatched(anime))
            assertEquals(12, demo.serverWatched(anime))
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `a send handed to an idle worker fails when the scope ends`() =
        runTest {
            val scope = childScope()
            val demo = Demo(fixtures, scope, latency = 1.seconds)
            runCurrent()

            val sent =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { demo.list.send(ListWrite.MarkWatched(FixtureAnime.exactToWatch, 13)) }
                }
            scope.cancel()
            runCurrent()

            assertTrue(sent.isCompleted)
            assertTrue(sent.await().exceptionOrNull() is IllegalStateException)
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
        }

    @Test
    fun `W2 under an unconfined dispatcher a failed write returns to the server's state`() =
        runTest(UnconfinedTestDispatcher()) {
            val demo = Demo(fixtures, backgroundScope)
            val failures = failures(demo)
            val anime = FixtureAnime.exactToWatch
            demo.server.failingWrites.value = true

            demo.list.write(ListWrite.MarkNextWatched(anime))

            assertEquals(12, demo.shownWatched(anime))
            assertEquals(12, demo.serverWatched(anime))
            assertEquals(DataError.ServerError, failures.single().error)
        }

    @Test
    fun `W5 under an unconfined dispatcher a successful write is not left out of later syncs`() =
        runTest(UnconfinedTestDispatcher()) {
            val demo = Demo(fixtures, backgroundScope)
            val anime = FixtureAnime.exactToWatch

            demo.list.write(ListWrite.MarkNextWatched(anime))
            assertEquals(13, demo.serverWatched(anime))
            demo.editOnWebsite(anime, 20)

            assertEquals(Outcome.Success(Unit), demo.list.sync())
            assertEquals(20, demo.shownWatched(anime))
        }

    @Test
    fun `N9 send fails for a title no longer on the list`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch
            demo.editOnWebsite(anime, null)

            assertEquals(Outcome.Failure(DataError.NotFound), demo.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(
                Outcome.Failure(DataError.NotFound),
                demo.list.send(ListWrite.MarkWatched(FixtureAnime.notOnListAiring, 1)),
            )
            assertEquals(emptyList<ListRequest>(), demo.server.requests)
        }

    @Test
    fun `N9 send fails rather than jump the count over unseen episodes`() =
        runTest {
            val demo = demo()
            val anime = FixtureAnime.exactToWatch

            assertEquals(Outcome.Failure(DataError.InvalidRequest), demo.list.send(ListWrite.MarkWatched(anime, 15)))

            assertEquals(emptyList<ListRequest>(), demo.server.requests)
            assertEquals(12, demo.serverWatched(anime))
            assertEquals(12, demo.shownWatched(anime))
        }

    @Test
    fun `a sync running when the user signs out does not report success`() =
        runTest {
            val demo = demo()
            val sync = async { demo.list.sync() }
            advanceTimeBy(1500.milliseconds)
            demo.session.signOut()
            assertEquals(Outcome.Failure(DataError.SessionExpired), sync.await())
        }
}
