package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.demo.DemoServer
import io.github.kauri_off.kiroku.core.demo.ListRequest
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// What the list engine must do over any store and any client of MAL; a pair of ports passes it by subclassing.
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ListEngineContract {
    // A new, empty store.
    protected abstract fun local(scope: TestScope): LocalList

    // What the engine asks in place of MAL: the scripted server itself, or a client under test in front of it.
    protected open fun remote(server: DemoServer): RemoteList = server

    private val fixtures = Fixtures.reference
    private val today = PartialDate.Day(LocalDate.now(fixtures.clock))
    private val a = Session.SignedIn(SignInId("a"))
    private val b = Session.SignedIn(SignInId("b"))

    // When the store's list was downloaded, before the engine's clock.
    private val seededAt = fixtures.now.minusSeconds(3600)

    // [store] is the store under test, [local] the engine's way to it; the session is not known while it is null.
    private inner class Subject(
        val server: DemoServer,
        val store: LocalList,
        scope: CoroutineScope,
        session: Session?,
    ) {
        val session = MutableStateFlow(session)
        val mal = FaultyRemote(remote(server))
        val local = DatabaseLike(store)
        val clock = FaultyClock(fixtures.clock)
        val unexpected = mutableListOf<Throwable>()
        val list = engine(scope)

        fun engine(scope: CoroutineScope): ListRepository =
            ListEngine(mal, local, session.filterNotNull(), clock, scope) { unexpected += it }

        suspend fun syncedAt() = list.observeSyncedAt().first()

        fun serverWatched(anime: Anime) = server.entry(anime.id)?.values?.watchedEpisodes

        suspend fun shown(id: AnimeId) = list.observeEntry(id).first()

        suspend fun shownWatched(anime: Anime) = shown(anime.id)?.values?.watchedEpisodes

        fun editOnWebsite(
            anime: Anime,
            watched: Int?,
        ) = server.editOnWebsite(anime, watched?.let { checkNotNull(server.entry(anime.id)).values.copy(watchedEpisodes = it) })
    }

    // A client of MAL that counts the entries it is asked for, and throws, before or after its call, while told to.
    // Its answers about one title can carry other data of the title, or be about another title altogether.
    private class FaultyRemote(
        private val inner: RemoteList,
    ) : RemoteList {
        @Volatile
        var throwing: Exception? = null

        @Volatile
        var entryReads = 0

        @Volatile
        var answering: (Anime) -> Anime = { it }

        override suspend fun fetch(): Outcome<List<ListEntry>> = fault().let { inner.fetch() }.also { fault() }

        override suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?> {
            entryReads++
            fault()
            return inner.fetchEntry(id).answered().also { fault() }
        }

        override suspend fun put(
            anime: Anime,
            values: ListValues?,
        ): Outcome<ListEntry?> = fault().let { inner.put(anime, values) }.answered().also { fault() }

        private fun Outcome<ListEntry?>.answered() =
            if (this is Outcome.Success) Outcome.Success(value?.let { it.copy(anime = answering(it.anime)) }) else this

        private fun fault() {
            throwing?.let { throw it }
        }
    }

    // A store as a database is: a query emits again after every write, changed or not, and it can fail: on every
    // call, on its next queries, under its running queries, only on writes, as a full disk does, or with its writes
    // held until [writeGate] completes. It keeps what the engine called and what each download handed it.
    private class DatabaseLike(
        private val inner: LocalList,
    ) : LocalList {
        class Download(
            val titles: List<Anime>,
            val entries: Map<AnimeId, StoredEntry?>,
            val at: Instant,
        )

        @Volatile
        var throwing = false

        @Volatile
        var failingWrites = false

        @Volatile
        var writesToFail = 0

        @Volatile
        var writeGate: CompletableDeferred<Unit>? = null

        @Volatile
        var subscriptions = 0

        @Volatile
        var subscriptionsToFail = 0

        // Nothing a process does after its death reaches the store.
        @Volatile
        var dead = false

        val calls = CopyOnWriteArrayList<String>()
        val downloads = CopyOnWriteArrayList<Download>()

        val writes: List<String> get() = calls.filter { it in WRITING }

        private val written = MutableStateFlow(0)
        private val closed = MutableStateFlow(0)

        // Every running query ends with a failure.
        fun close() = closed.update { it + 1 }

        override fun observeEntries(): Flow<List<ListEntry>> = query("observeEntries") { inner.observeEntries() }

        override fun observeEntry(id: AnimeId): Flow<ListEntry?> = query("observeEntry") { inner.observeEntry(id) }

        override suspend fun state(): StoredList = reading("state") { inner.state() }

        override suspend fun read(id: AnimeId): StoredTitle? = reading("read") { inner.read(id) }

        override suspend fun readAll(): Map<AnimeId, StoredEntry> = reading("readAll") { inner.readAll() }

        override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = writing("write") { inner.write(entries) }

        override suspend fun add(
            anime: Anime,
            entry: StoredEntry,
        ) = writing("add") { inner.add(anime, entry) }

        override suspend fun download(
            titles: List<Anime>,
            entries: Map<AnimeId, StoredEntry?>,
            at: Instant,
        ) = writing("download") {
            downloads += Download(titles, entries, at)
            inner.download(titles, entries, at)
        }

        override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) = writing("restore") { inner.restore(entries) }

        override suspend fun clear(owner: SignInId?) = writing("clear") { inner.clear(owner) }

        private fun <T> query(
            name: String,
            source: () -> Flow<T>,
        ): Flow<T> =
            flow {
                calls += name
                subscriptions++
                fault()
                if (subscriptionsToFail > 0) {
                    subscriptionsToFail--
                    throw IOException("The store cannot be opened")
                }
                val opened = closed.value
                emitAll(
                    combine(source(), written, closed) { value, _, now ->
                        if (now != opened) throw IOException("The store was closed")
                        value
                    },
                )
            }

        private inline fun <T> reading(
            name: String,
            call: () -> T,
        ): T {
            calls += name
            fault()
            return call()
        }

        private suspend inline fun writing(
            name: String,
            call: () -> Unit,
        ) {
            if (dead) return
            calls += name
            writeGate?.await()
            fault()
            if (failingWrites) throw IOException("The disk is full")
            if (writesToFail > 0) {
                writesToFail--
                throw IOException("The write was aborted")
            }
            call()
            written.update { it + 1 }
        }

        private fun fault() {
            if (throwing) throw IOException("The store cannot be opened")
        }

        private companion object {
            val WRITING = setOf("write", "add", "download", "restore", "clear")
        }
    }

    // The engine's clock, which throws while told to, so the engine itself fails where no port is called.
    private class FaultyClock(
        private val inner: Clock,
    ) : Clock() {
        @Volatile
        var throwing = false

        @Volatile
        var ahead: java.time.Duration = java.time.Duration.ZERO

        override fun getZone(): ZoneId = inner.zone

        override fun withZone(zone: ZoneId): Clock = inner.withZone(zone)

        override fun instant(): Instant = check(!throwing) { "The engine failed" }.let { inner.instant() + ahead }
    }

    // A store that takes its time, as a database does.
    private class SlowLocalList(
        private val inner: LocalList,
    ) : LocalList by inner {
        override suspend fun read(id: AnimeId): StoredTitle? {
            delay(STORE_TIME)
            return inner.read(id)
        }

        override suspend fun write(entries: Map<AnimeId, StoredEntry?>) {
            delay(STORE_TIME)
            inner.write(entries)
        }
    }

    // MAL and the store start with the fixtures' list, all of it confirmed; the store's is the list of [a].
    private suspend fun TestScope.subject(
        latency: Duration = 1.seconds,
        scope: CoroutineScope = backgroundScope,
        slowStore: Boolean = false,
        serverClock: Clock = fixtures.clock,
        session: Session? = a,
    ): Subject {
        val local = local(this)
        local.clear(a.signIn)
        local.download(
            fixtures.entries.map { it.anime },
            fixtures.entries.associate { it.anime.id to StoredEntry.settled(it.record) },
            seededAt,
        )
        val store = if (slowStore) SlowLocalList(local) else local
        return Subject(DemoServer(fixtures.entries, serverClock, latency), store, scope, session).also { runCurrent() }
    }

    // The process dies: its scope ends, and nothing it does while ending reaches the store.
    private fun TestScope.kill(
        subject: Subject,
        process: CoroutineScope,
    ) {
        subject.local.dead = true
        process.cancel()
        runCurrent()
        subject.local.dead = false
    }

    private fun TestScope.observe(subject: Subject): List<List<ListEntry>> {
        val seen = mutableListOf<List<ListEntry>>()
        backgroundScope.launch { subject.list.observeEntries().collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun TestScope.failures(subject: Subject): List<WriteFailure> {
        val seen = mutableListOf<WriteFailure>()
        backgroundScope.launch { subject.list.observeWriteFailures().collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun List<List<ListEntry>>.watched(anime: Anime) = last().single { it.anime.id == anime.id }.values.watchedEpisodes

    private fun TestScope.childScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.settle() {
        advanceTimeBy(1.minutes)
        runCurrent()
    }

    // The title as MAL or a screen has it at another time than the store.
    private fun Anime.renewed() = copy(titles = titles.copy(synonyms = titles.synonyms + "Renewed"))

    @Test
    fun `a +1 reaches an observer of the list at once`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            assertEquals(12, seen.watched(FixtureAnime.exactToWatch))

            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()

            assertEquals(13, seen.watched(FixtureAnime.exactToWatch))
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            settle()
            assertEquals(
                13,
                s.server
                    .entry(FixtureAnime.exactToWatch.id)
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `W3 each of several quick writes reports its own change, so Undo of the last takes back only that one`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val changes = List(3) { async(start = CoroutineStart.UNDISPATCHED) { s.list.write(ListWrite.MarkNextWatched(anime)) } }
            val counts =
                changes.map {
                    checkNotNull(it.await()).let { c ->
                        c.before!!.values.watchedEpisodes to
                            c.after!!.values.watchedEpisodes
                    }
                }
            assertEquals(listOf(12 to 13, 13 to 14, 14 to 15), counts)
            s.list.write(checkNotNull(changes.last().await()).undo)
            assertEquals(14, s.shownWatched(anime))
            assertNull(s.list.write(ListWrite.Save(anime, checkNotNull(s.list.observeEntry(anime.id).first()).values)))
        }

    @Test
    fun `W3 Undo of a completing step keeps a score saved and a step made after it`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.oneShortOfTotal
            val change = checkNotNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            assertEquals(true, change.completed)
            val now = checkNotNull(s.list.observeEntry(anime.id).first()).values
            s.list.write(ListWrite.Save(anime, now.copy(score = 10)))
            s.list.write(ListWrite.StepWatched(anime, 1))
            s.list.write(change.undo)
            val after = checkNotNull(s.list.observeEntry(anime.id).first()).values
            assertEquals(ListStatus.Watching, after.status)
            assertEquals(10, after.score)
            assertEquals(12, after.watchedEpisodes)
            assertEquals(change.before!!.values.finishDate, after.finishDate)
        }

    @Test
    fun `W7 the last episode completes the entry and Undo restores it in one write`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            val before = checkNotNull(fixtures.entryFor(FixtureAnime.oneShortOfTotal.id))

            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.oneShortOfTotal))
            settle()
            val completed = seen.last().single { it.anime.id == FixtureAnime.oneShortOfTotal.id }.values
            assertEquals(ListStatus.Completed, completed.status)
            assertEquals(12, completed.watchedEpisodes)
            assertEquals(today, completed.finishDate)
            assertEquals(before.values.startDate, completed.startDate)

            s.list.write(ListWrite.Save(before.anime, before.values))
            settle()
            val restored = seen.last().single { it.anime.id == FixtureAnime.oneShortOfTotal.id }.values
            assertEquals(before.values, restored)
            assertEquals(
                listOf(ListRequest(before.anime.id, completed), ListRequest(before.anime.id, before.values)),
                s.server.requests,
            )
            assertEquals(before.values, s.server.entry(before.anime.id)?.values)
        }

    @Test
    fun `W7 the first episode of a planned title sets Watching and the start date`() =
        runTest {
            val s = subject()
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.plannedFinished))
            val values = checkNotNull(s.list.observeEntry(FixtureAnime.plannedFinished.id).first()).values
            assertEquals(ListStatus.Watching, values.status)
            assertEquals(1, values.watchedEpisodes)
            assertEquals(today, values.startDate)
        }

    @Test
    fun `W1 quick edits are sent in order and the server ends with the last value shown`() =
        runTest {
            val s = subject()
            s.server.writeLatency = { if (it.values?.watchedEpisodes == 13) 5.seconds else 1.seconds }
            repeat(3) { s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)) }
            settle()
            assertEquals(listOf(13, 14, 15), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(
                15,
                s.server
                    .entry(FixtureAnime.exactToWatch.id)
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `W1 an answered request does not take back the edits still waiting behind it`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            val anime = FixtureAnime.exactToWatch
            repeat(3) { s.list.write(ListWrite.MarkNextWatched(anime)) }
            runCurrent()

            advanceTimeBy(1500.milliseconds)
            assertEquals(13, s.serverWatched(anime))
            assertEquals(15, seen.watched(anime))
            settle()

            val counts = seen.map { entries -> entries.single { it.anime.id == anime.id }.values.watchedEpisodes }
            assertEquals(counts.sorted(), counts)
            assertEquals(15, counts.last())
        }

    @Test
    fun `an answer that confirms the values shown reaches no observer again, and one that differs does`() =
        runTest {
            val s = subject(serverClock = Clock.offset(fixtures.clock, java.time.Duration.ofSeconds(1)))
            val seen = observe(s)
            val anime = FixtureAnime.exactToWatch

            val change = checkNotNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            settle()
            assertEquals(2, seen.size)
            assertEquals(change.after, s.shown(anime.id))

            s.editOnWebsite(anime, 14)
            assertEquals(Outcome.Success(Unit), s.list.send(ListWrite.MarkWatched(anime, 14)))
            runCurrent()
            assertEquals(3, seen.size)
            assertEquals(s.server.entry(anime.id), s.shown(anime.id))
        }

    @Test
    fun `W2 a failed write returns to the server's state, drops waiting edits and offers Retry`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.server.failingWrites.value = true

            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))
            runCurrent()
            assertEquals(14, seen.watched(anime))
            val shown = seen.last().single { it.anime.id == anime.id }.values

            settle()
            assertEquals(12, seen.watched(anime))
            assertEquals(listOf(13), s.server.requests.map { it.values?.watchedEpisodes })
            val failure = failures.single()
            assertEquals(DataError.ServerError, failure.error)
            assertEquals(ListWrite.Save(anime, shown), failure.retry)

            s.server.failingWrites.value = false
            s.list.write(failure.retry)
            settle()
            assertEquals(14, seen.watched(anime))
            assertEquals(
                14,
                s.server
                    .entry(anime.id)
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `a failed removal brings the entry back and Retry removes it`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.droppedMovie
            s.server.offline.value = true

            s.list.write(ListWrite.Remove(anime))
            runCurrent()
            assertNull(s.list.observeEntry(anime.id).first())
            settle()
            assertEquals(s.server.entry(anime.id), s.list.observeEntry(anime.id).first())
            assertEquals(WriteFailure(DataError.NoConnection, ListWrite.Remove(anime)), failures.single())
        }

    @Test
    fun `a failure with nothing collecting it is dropped`() =
        runTest {
            val s = subject()
            s.server.failingWrites.value = true
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            val failures = failures(s)
            settle()
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `an added title shows at once and reaches the server`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.notOnListAiring
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            s.list.write(ListWrite.Save(anime, values))
            assertEquals(
                values,
                s.list
                    .observeEntry(anime.id)
                    .first()
                    ?.values,
            )
            settle()
            assertEquals(values, s.server.entry(anime.id)?.values)
        }

    @Test
    fun `N9 send waits for the server and applies the step to the values as they are`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))

            val sent = s.list.send(ListWrite.MarkWatched(anime, 14))

            assertEquals(Outcome.Success(Unit), sent)
            assertEquals(listOf(13, 14), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(
                14,
                s.list
                    .observeEntry(anime.id)
                    .first()
                    ?.values
                    ?.watchedEpisodes,
            )
        }

    @Test
    fun `N9 send reports a failure and changes nothing`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.server.offline.value = true
            assertEquals(Outcome.Failure(DataError.NoConnection), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(
                12,
                s.list
                    .observeEntry(anime.id)
                    .first()
                    ?.values
                    ?.watchedEpisodes,
            )
            assertTrue(s.server.requests.isEmpty())
        }

    @Test
    fun `N9 send never reports success for a step that a later failing edit only showed`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            val sent = async { s.list.send(ListWrite.MarkWatched(anime, 13)) }
            runCurrent()
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.server.failingWrites.value = true

            settle()

            assertEquals(Outcome.Failure(DataError.ServerError), sent.await())
            assertEquals(12, s.serverWatched(anime))
            assertEquals(12, s.shownWatched(anime))
        }

    @Test
    fun `N9 send compares with the server's confirmed state`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.editOnWebsite(anime, 13)

            assertEquals(Outcome.Success(Unit), s.list.send(ListWrite.MarkWatched(anime, 13)))

            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(13, s.serverWatched(anime))
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `N9 a sync that already brought the change does not raise the count again`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.editOnWebsite(anime, 13)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(13, s.shownWatched(anime))

            assertEquals(Outcome.Success(Unit), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(Outcome.Success(Unit), s.list.send(ListWrite.MarkWatched(anime, 13)))

            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `a sync brings changes made on the website, removals included`() =
        runTest {
            val s = subject()
            val raised = FixtureAnime.exactToWatch
            val removed = FixtureAnime.droppedMovie
            val added = FixtureAnime.notOnListAiring
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            s.editOnWebsite(raised, 15)
            s.editOnWebsite(removed, null)
            s.server.editOnWebsite(added, values)
            assertEquals(12, s.shownWatched(raised))
            assertEquals(fixtures.entryFor(removed.id), s.list.observeEntry(removed.id).first())

            assertEquals(Outcome.Success(Unit), s.list.sync())

            assertEquals(15, s.shownWatched(raised))
            assertNull(s.list.observeEntry(removed.id).first())
            assertEquals(
                values,
                s.list
                    .observeEntry(added.id)
                    .first()
                    ?.values,
            )
            assertEquals(
                s.server.entries().toSet(),
                s.list
                    .observeEntries()
                    .first()
                    .toSet(),
            )
        }

    @Test
    fun `W4 a sync never reverts an edit made while it was running`() =
        runTest {
            val s = subject()
            val edited = FixtureAnime.exactToWatch
            val added = FixtureAnime.notOnListAiring
            val removed = FixtureAnime.droppedMovie
            val other = FixtureAnime.exactCaughtUp
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            s.editOnWebsite(other, 9)

            val sync = async { s.list.sync() }
            advanceTimeBy(500.milliseconds)
            s.list.write(ListWrite.MarkNextWatched(edited))
            s.list.write(ListWrite.Save(added, values))
            s.list.write(ListWrite.Remove(removed))
            advanceTimeBy(1100.milliseconds)
            assertEquals(13, s.serverWatched(edited))

            assertEquals(Outcome.Success(Unit), sync.await())
            assertEquals(1, s.server.listDownloads)
            assertEquals(13, s.shownWatched(edited))
            assertEquals(
                values,
                s.list
                    .observeEntry(added.id)
                    .first()
                    ?.values,
            )
            assertNull(s.list.observeEntry(removed.id).first())
            assertEquals(9, s.shownWatched(other))
        }

    @Test
    fun `W4 a sync does not revert an earlier edit whose request completed while it ran`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.server.writeLatency = { 2.seconds }
            s.list.write(ListWrite.MarkNextWatched(anime))
            advanceTimeBy(100.milliseconds)

            val sync = async { s.list.sync() }
            settle()

            assertEquals(Outcome.Success(Unit), sync.await())
            assertEquals(13, s.serverWatched(anime))
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `W6 one list sync runs at a time, and calls made while one runs share one follow-up`() =
        runTest {
            val s = subject()
            val syncs = List(3) { async { s.list.sync() } }
            advanceTimeBy(2500.milliseconds)
            assertEquals(1, s.server.listDownloads)
            settle()
            syncs.forEach { assertEquals(Outcome.Success(Unit), it.await()) }
            assertEquals(2, s.server.listDownloads)

            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(3, s.server.listDownloads)
        }

    @Test
    fun `a sync requested while one runs shows a website change made after the running one downloaded`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val first = async { s.list.sync() }
            advanceTimeBy(1500.milliseconds)
            assertEquals(1, s.server.listDownloads)
            s.editOnWebsite(anime, 20)

            assertEquals(Outcome.Success(Unit), s.list.sync())

            assertEquals(Outcome.Success(Unit), first.await())
            assertEquals(20, s.shownWatched(anime))
        }

    @Test
    fun `once the scope has ended, waiting calls fail and shown edits return to the server's state`() =
        runTest {
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val s = subject(scope = scope)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            s.list.write(ListWrite.MarkNextWatched(anime))
            val sent = async { runCatching { s.list.send(ListWrite.MarkWatched(anime, 14)) } }
            runCurrent()
            assertEquals(13, s.shownWatched(anime))

            scope.cancel()
            runCurrent()

            assertTrue(sent.await().exceptionOrNull() is IllegalStateException)
            assertEquals(12, s.shownWatched(anime))
            assertNull(s.local.state().syncedAt)
            assertEquals(fixtures.entryFor(FixtureAnime.exactCaughtUp.id), s.list.observeEntry(FixtureAnime.exactCaughtUp.id).first())
            assertTrue(runCatching { s.list.write(ListWrite.MarkNextWatched(anime)) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { s.list.send(ListWrite.MarkWatched(anime, 13)) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { s.list.sync() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { s.engine(scope) }.exceptionOrNull() is IllegalStateException)
            assertEquals(emptyList<ListRequest>(), s.server.requests)
        }

    @Test
    fun `signed out, writes and sends reach no server`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.session.value = Session.SignedOut

            assertEquals(emptyList<ListEntry>(), s.list.observeEntries().first())
            assertNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            assertEquals(Outcome.Failure(DataError.SessionExpired), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(Outcome.Failure(DataError.SessionExpired), s.list.sync())
            settle()

            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(0, s.server.listDownloads)
            assertEquals(0, s.mal.entryReads)
            assertEquals(listOf(WriteFailure(DataError.SessionExpired, ListWrite.MarkNextWatched(anime))), failures)
        }

    @Test
    fun `an edit waiting when the user signs out is not sent and reports no failure`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            s.session.value = Session.SignedOut
            settle()
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `a failed removal keeps the entry's place in the list`() =
        runTest {
            val s = subject()
            val before = s.list.observeEntries().first()
            s.server.offline.value = true
            s.list.write(ListWrite.Remove(FixtureAnime.exactCaughtUp))
            settle()
            assertEquals(before, s.list.observeEntries().first())
        }

    @Test
    fun `Undo of an edit whose request fails still reports the failure, with a Retry equal to the server's state`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            val before = checkNotNull(fixtures.entryFor(anime.id))
            s.server.failingWrites.value = true
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.Save(anime, before.values))
            settle()

            assertEquals(listOf(WriteFailure(DataError.ServerError, ListWrite.Save(anime, before.values))), failures)
            assertEquals(12, s.shownWatched(anime))
            assertEquals(listOf(13), s.server.requests.map { it.values?.watchedEpisodes })
        }

    @Test
    fun `refused writes fail like any other`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            s.server.refusing.value = true
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(DataError.Refused, failures.single().error)
            assertEquals(Outcome.Failure(DataError.Refused), s.list.sync())
        }

    @Test
    fun `an edit handed to an idle worker returns to the server's state when the scope ends`() =
        runTest {
            val scope = childScope()
            val s = subject(scope = scope)
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            runCurrent()

            s.list.write(ListWrite.MarkNextWatched(anime))
            scope.cancel()
            runCurrent()

            assertEquals(12, s.shownWatched(anime))
            assertEquals(12, s.serverWatched(anime))
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(emptyList<WriteFailure>(), failures)
        }

    @Test
    fun `a send handed to an idle worker fails when the scope ends`() =
        runTest {
            val scope = childScope()
            val s = subject(scope = scope)
            runCurrent()

            val sent =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { s.list.send(ListWrite.MarkWatched(FixtureAnime.exactToWatch, 13)) }
                }
            scope.cancel()
            runCurrent()

            assertTrue(sent.isCompleted)
            assertTrue(sent.await().exceptionOrNull() is IllegalStateException)
            assertEquals(emptyList<ListRequest>(), s.server.requests)
        }

    @Test
    fun `W2 under an unconfined dispatcher a failed write returns to the server's state`() =
        runTest(UnconfinedTestDispatcher()) {
            val s = subject(latency = Duration.ZERO)
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.server.failingWrites.value = true

            s.list.write(ListWrite.MarkNextWatched(anime))

            assertEquals(12, s.shownWatched(anime))
            assertEquals(12, s.serverWatched(anime))
            assertEquals(DataError.ServerError, failures.single().error)
        }

    @Test
    fun `W5 under an unconfined dispatcher a successful write is not left out of later syncs`() =
        runTest(UnconfinedTestDispatcher()) {
            val s = subject(latency = Duration.ZERO)
            val anime = FixtureAnime.exactToWatch

            s.list.write(ListWrite.MarkNextWatched(anime))
            assertEquals(13, s.serverWatched(anime))
            s.editOnWebsite(anime, 20)

            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(20, s.shownWatched(anime))
        }

    @Test
    fun `N9 send fails for a title no longer on the list`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.editOnWebsite(anime, null)

            assertEquals(Outcome.Failure(DataError.NotFound), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(
                Outcome.Failure(DataError.NotFound),
                s.list.send(ListWrite.MarkWatched(FixtureAnime.notOnListAiring, 1)),
            )
            assertEquals(emptyList<ListRequest>(), s.server.requests)
        }

    @Test
    fun `N9 send fails rather than jump the count over unseen episodes`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch

            assertEquals(Outcome.Failure(DataError.InvalidRequest), s.list.send(ListWrite.MarkWatched(anime, 15)))

            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(12, s.serverWatched(anime))
            assertEquals(12, s.shownWatched(anime))
        }

    @Test
    fun `a sync running when the user signs out does not report success`() =
        runTest {
            val s = subject()
            val sync = async { s.list.sync() }
            advanceTimeBy(1500.milliseconds)
            s.session.value = Session.SignedOut
            assertEquals(Outcome.Failure(DataError.SessionExpired), sync.await())
        }

    @Test
    fun `write returns the entry before and after a step, a save and a removal, and null when it changed nothing`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val before = checkNotNull(fixtures.entryFor(anime.id))

            val step = checkNotNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            assertEquals(before, step.before)
            assertEquals(before.values.copy(watchedEpisodes = 13), step.after?.values)
            assertTrue(step.stepped)

            val onHold = before.values.copy(status = ListStatus.OnHold)
            val save = checkNotNull(s.list.write(ListWrite.Save(anime, onHold)))
            assertEquals(step.after, save.before)
            assertEquals(onHold, save.after?.values)
            assertEquals(s.shown(anime.id), save.after)
            assertFalse(save.stepped)
            assertNull(s.list.write(ListWrite.Save(anime, onHold)))

            val removal = checkNotNull(s.list.write(ListWrite.Remove(anime)))
            assertEquals(save.after, removal.before)
            assertNull(removal.after)
            assertNull(s.list.write(ListWrite.Remove(anime)))
            assertNull(s.list.write(ListWrite.MarkNextWatched(anime)))

            val added = checkNotNull(s.list.write(ListWrite.Save(anime, before.values)))
            assertNull(added.before)
            assertEquals(before.values, added.after?.values)
        }

    @Test
    fun `a write is applied whole and sent although its caller is cancelled meanwhile`() =
        runTest {
            val s = subject(slowStore = true)
            val anime = FixtureAnime.exactToWatch
            val caller = launch { s.list.write(ListWrite.MarkNextWatched(anime)) }
            advanceTimeBy(STORE_TIME * 1.5)

            caller.cancel()
            settle()

            assertEquals(13, s.shownWatched(anime))
            assertEquals(13, s.serverWatched(anime))
        }

    @Test
    fun `W5 an edit whose request died with its process is back at the confirmed values at the next start, and the list is synced`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            val confirmed = FixtureAnime.exactToWatch
            val added = FixtureAnime.notOnListAiring
            val removed = FixtureAnime.droppedMovie
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            s.list.write(ListWrite.MarkNextWatched(confirmed))
            s.list.write(ListWrite.Save(added, values))
            s.list.write(ListWrite.Remove(removed))
            advanceTimeBy(1500.milliseconds)
            assertEquals(listOf(ListRequest(confirmed.id, s.shown(confirmed.id)?.values)), s.server.requests)

            // The store as the process left it, and MAL having taken the request whose answer never came.
            kill(s, process)
            s.server.editOnWebsite(added, values)
            val restarted = s.engine(backgroundScope)

            assertNull(restarted.observeEntry(added.id).first())
            assertEquals(fixtures.entryFor(removed.id), restarted.observeEntry(removed.id).first())
            assertEquals(
                13,
                restarted
                    .observeEntry(confirmed.id)
                    .first()
                    ?.values
                    ?.watchedEpisodes,
            )
            assertEquals(0, s.server.listDownloads)

            settle()
            assertEquals(1, s.server.listDownloads)
            assertEquals(
                values,
                restarted
                    .observeEntry(added.id)
                    .first()
                    ?.values,
            )
            assertEquals(s.server.entries().toSet(), restarted.observeEntries().first().toSet())
        }

    @Test
    fun `W5 a start after every request was answered changes nothing and syncs nothing`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.Remove(FixtureAnime.droppedMovie))
            settle()
            s.server.failingWrites.value = true
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            settle()

            val left = s.local.readAll() to s.local.state()
            process.cancel()
            runCurrent()
            assertEquals(left, s.local.readAll() to s.local.state())
            val restarted = s.engine(backgroundScope)
            val shown = restarted.observeEntries().first()
            settle()

            assertEquals(s.server.entries().toSet(), shown.toSet())
            assertEquals(shown, restarted.observeEntries().first())
            assertEquals(0, s.server.listDownloads)
        }

    @Test
    fun `W8 a write that failed offline is not sent when the connection is back`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.server.offline.value = true
            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()

            s.server.offline.value = false
            assertEquals(Outcome.Success(Unit), s.list.sync())
            settle()

            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(12, s.serverWatched(anime))
            assertEquals(12, s.shownWatched(anime))
        }

    @Test
    fun `W2 a failure after an answered request returns to the value MAL confirmed meanwhile`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))
            val shown = checkNotNull(s.shown(anime.id)).values
            advanceTimeBy(1500.milliseconds)
            assertEquals(13, s.serverWatched(anime))

            s.server.failingWrites.value = true
            settle()

            assertEquals(13, s.shownWatched(anime))
            assertEquals(listOf(WriteFailure(DataError.ServerError, ListWrite.Save(anime, shown))), failures)
        }

    @Test
    fun `a failed addition does not stay on the list, and Retry adds the title`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.notOnListAiring
            val values = checkNotNull(fixtures.entryFor(FixtureAnime.premiereDate.id)).values
            s.server.offline.value = true

            s.list.write(ListWrite.Save(anime, values))
            assertEquals(values, s.shown(anime.id)?.values)
            settle()

            assertNull(s.shown(anime.id))
            assertEquals(listOf(WriteFailure(DataError.NoConnection, ListWrite.Save(anime, values))), failures)
            s.server.offline.value = false
            s.list.write(failures.single().retry)
            settle()
            assertEquals(values, s.server.entry(anime.id)?.values)
            assertEquals(values, s.shown(anime.id)?.values)
        }

    @Test
    fun `W5 a title whose write failed is in the next sync`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.server.failingWrites.value = true
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()

            s.server.failingWrites.value = false
            s.editOnWebsite(anime, 20)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(20, s.shownWatched(anime))
        }

    @Test
    fun `edits and sends waiting at a sign-out are never sent, also after the next sign-in`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            repeat(3) { s.list.write(ListWrite.MarkNextWatched(anime)) }
            val sent = async { s.list.send(ListWrite.MarkWatched(FixtureAnime.oneShortOfTotal, 12)) }
            advanceTimeBy(500.milliseconds)

            s.session.value = Session.SignedOut
            runCurrent()
            s.session.value = b
            settle()

            assertEquals(listOf(ListRequest(anime.id, s.server.entry(anime.id)?.values)), s.server.requests)
            assertEquals(Outcome.Failure(DataError.SessionExpired), sent.await())
            assertEquals(0, s.mal.entryReads)
            assertNull(s.shown(anime.id))
            assertEquals(emptyList<WriteFailure>(), failures)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `N9 a send whose read spans a sign-out and a sign-in writes nothing`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val sent = async { s.list.send(ListWrite.MarkWatched(anime, 13)) }
            advanceTimeBy(500.milliseconds)

            s.session.value = Session.SignedOut
            runCurrent()
            s.session.value = b
            settle()

            assertEquals(Outcome.Failure(DataError.SessionExpired), sent.await())
            assertEquals(emptyList<ListRequest>(), s.server.requests)
        }

    @Test
    fun `a sync across a sign-out and a sign-in stores nothing it downloaded`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.editOnWebsite(anime, 20)
            val sync = async { s.list.sync() }
            advanceTimeBy(1500.milliseconds)
            assertEquals(1, s.server.listDownloads)

            s.session.value = Session.SignedOut
            runCurrent()
            s.session.value = b

            assertEquals(Outcome.Failure(DataError.SessionExpired), sync.await())
            assertEquals(emptyList<ListEntry>(), s.list.observeEntries().first())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), s.local.readAll())
        }

    @Test
    fun `a client of MAL that throws fails the one call as a server error, reports it, and the list goes on`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            val bug = IllegalStateException("MAL's client failed")
            s.mal.throwing = bug

            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()
            assertEquals(12, s.shownWatched(anime))
            assertEquals(DataError.ServerError, failures.single().error)
            assertEquals(Outcome.Failure(DataError.ServerError), s.list.sync())
            assertEquals(Outcome.Failure(DataError.ServerError), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(listOf(bug, bug, bug), s.unexpected)

            s.mal.throwing = null
            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()
            assertEquals(13, s.serverWatched(anime))
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `a client of MAL that lets a lost connection escape fails the call as no connection, unreported`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            s.mal.throwing = UnknownHostException("myanimelist.net")

            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            settle()
            assertEquals(DataError.NoConnection, failures.single().error)
            assertEquals(Outcome.Failure(DataError.NoConnection), s.list.sync())
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `a client of MAL that throws once the user signed out fails the call as an expired session, unreported`() =
        runTest {
            val s = subject()
            val sync = async { s.list.sync() }
            advanceTimeBy(500.milliseconds)

            s.session.value = Session.SignedOut
            s.mal.throwing = IllegalStateException("No token")
            settle()

            assertEquals(Outcome.Failure(DataError.SessionExpired), sync.await())
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `a store that throws fails the one call as a storage error, and the list goes on`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.local.throwing = true
            val seen = observe(s)

            assertNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            runCurrent()
            assertEquals(listOf(WriteFailure(DataError.StorageError, ListWrite.MarkNextWatched(anime))), failures)
            assertEquals(emptyList<List<ListEntry>>(), seen)

            s.local.throwing = false
            settle()
            assertEquals(12, seen.watched(anime))
            s.local.throwing = true
            assertEquals(Outcome.Failure(DataError.StorageError), s.list.sync())

            s.local.throwing = false
            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()
            assertEquals(listOf(13), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(13, seen.watched(anime))
            assertTrue(s.unexpected.isNotEmpty())
            assertTrue(s.unexpected.all { it.message == "The store cannot be opened" })
        }

    @Test
    fun `a store that fails under an answer drops the waiting edits and offers Retry of the last one`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))
            val shown = checkNotNull(s.shown(anime.id)).values

            s.local.throwing = true
            advanceTimeBy(1500.milliseconds)
            s.local.throwing = false
            settle()

            assertEquals(listOf(13), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(listOf(WriteFailure(DataError.StorageError, ListWrite.Save(anime, shown))), failures)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `a store whose write fails under an answer returns the title to its confirmed values, and Retry sends the last edit`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))
            val shown = checkNotNull(s.shown(anime.id)).values

            s.local.writesToFail = 1
            settle()

            assertEquals(12, s.shownWatched(anime))
            assertFalse(checkNotNull(s.local.read(anime.id)?.entry).unconfirmed)
            assertEquals(listOf(WriteFailure(DataError.StorageError, ListWrite.Save(anime, shown))), failures)
            s.list.write(failures.single().retry)
            settle()
            assertEquals(listOf(13, 14), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(14, s.shownWatched(anime))
        }

    @Test
    fun `Retry after a rollback the store could not make sends the edit`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))

            s.local.throwing = true
            advanceTimeBy(1500.milliseconds)
            s.local.throwing = false
            settle()
            assertEquals(14, s.shownWatched(anime))

            s.list.write(failures.single().retry)
            settle()
            assertEquals(listOf(13, 14), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(14, s.shownWatched(anime))
            assertFalse(checkNotNull(s.local.read(anime.id)?.entry).unconfirmed)
        }

    @Test
    fun `on a full disk, where reads work and writes fail, a Retry that cannot be stored reports its failure again`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))

            s.local.failingWrites = true
            advanceTimeBy(1500.milliseconds)
            runCurrent()
            assertEquals(DataError.StorageError, failures.single().error)

            assertNull(s.list.write(failures.single().retry))
            runCurrent()
            assertEquals(listOf(DataError.StorageError, DataError.StorageError), failures.map { it.error })
            assertEquals(failures[0].retry, failures[1].retry)

            s.local.failingWrites = false
            s.list.write(failures.last().retry)
            settle()
            assertEquals(listOf(13, 14), s.server.requests.map { it.values?.watchedEpisodes })
        }

    @Test
    fun `W5 a restore the store failed at start is tried again at the next use, and the list is still synced`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            val anime = FixtureAnime.exactToWatch
            s.list.write(ListWrite.MarkNextWatched(anime))
            runCurrent()
            kill(s, process)

            s.local.throwing = true
            val restarted = s.engine(backgroundScope)
            val seen = mutableListOf<List<ListEntry>>()
            backgroundScope.launch { restarted.observeEntries().collect { seen += it } }
            runCurrent()
            s.local.throwing = false
            settle()

            assertEquals(12, seen.watched(anime))
            assertEquals(1, s.server.listDownloads)
        }

    @Test
    fun `N9 a send whose write spans a sign-out and a sign-in stores nothing`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val sent = async { s.list.send(ListWrite.MarkWatched(anime, 13)) }
            advanceTimeBy(1500.milliseconds)
            assertEquals(1, s.mal.entryReads)

            s.session.value = Session.SignedOut
            runCurrent()
            s.session.value = b
            settle()

            sent.await()
            assertEquals(13, s.serverWatched(anime))
            assertEquals(emptyMap<AnimeId, StoredEntry>(), s.local.readAll())
        }

    @Test
    fun `a store that cannot be read is tried three times, then the list ends with a storage error, and a new observer tries again`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.local.throwing = true
            var ended: DataException? = null
            backgroundScope.launch {
                try {
                    s.list.observeEntries().collect {}
                } catch (e: DataException) {
                    ended = e
                }
            }
            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals(3, s.local.subscriptions)
            assertEquals(DataError.StorageError, ended?.error)
            assertTrue(s.unexpected.isNotEmpty())

            s.local.throwing = false
            assertEquals(12, s.shownWatched(anime))
        }

    @Test
    fun `the engine's scope ending during a write is not a storage error, and nothing is reported`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            val failures = failures(s)
            val gate = CompletableDeferred<Unit>()
            s.local.writeGate = gate
            val written = async { runCatching { s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)) } }
            runCurrent()

            process.cancel()
            gate.completeExceptionally(CancellationException("The database is closed"))
            runCurrent()

            assertTrue(written.await().exceptionOrNull() is IllegalStateException)
            assertEquals(emptyList<WriteFailure>(), failures)
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `an exception of the engine's own in a write reaches its caller and is no storage error`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            s.clock.throwing = true

            val written = runCatching { s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)) }
            runCurrent()

            assertEquals("The engine failed", written.exceptionOrNull()?.message)
            assertEquals(emptyList<WriteFailure>(), failures)
            assertEquals(emptyList<Throwable>(), s.unexpected)
        }

    @Test
    fun `an exception of the engine's own in its background work is reported, fails that call, and the list goes on`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.clock.throwing = true
            val sent = async { runCatching { s.list.send(ListWrite.MarkWatched(anime, 13)) } }
            advanceTimeBy(1500.milliseconds)
            s.clock.throwing = false

            assertEquals("The engine failed", sent.await().exceptionOrNull()?.message)
            assertEquals(listOf("The engine failed"), s.unexpected.map { it.message })
            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()
            assertEquals(13, s.serverWatched(anime))
            assertEquals(13, s.shownWatched(anime))
        }

    @Test
    fun `before the session is known the engine reads nothing, sends nothing and answers no call`() =
        runTest {
            val s = subject(session = null)
            val anime = FixtureAnime.exactToWatch
            val seen = observe(s)
            val times = mutableListOf<Instant?>()
            backgroundScope.launch { s.list.observeSyncedAt().collect { times += it } }
            val written = async { s.list.write(ListWrite.MarkNextWatched(anime)) }
            val synced = async { s.list.sync() }
            val sent = async { s.list.send(ListWrite.MarkWatched(FixtureAnime.oneShortOfTotal, 12)) }
            settle()

            assertEquals(emptyList<String>(), s.local.calls)
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(0, s.server.listDownloads)
            assertEquals(0, s.mal.entryReads)
            assertEquals(emptyList<List<ListEntry>>(), seen)
            assertEquals(emptyList<Instant?>(), times)
            assertFalse(written.isCompleted || synced.isCompleted || sent.isCompleted)

            s.session.value = a
            settle()

            assertEquals(13, seen.watched(anime))
            assertEquals(13, written.await()?.after?.values?.watchedEpisodes)
            assertEquals(Outcome.Success(Unit), synced.await())
            assertEquals(Outcome.Success(Unit), sent.await())
            assertEquals(13, s.serverWatched(anime))
        }

    @Test
    fun `S6 expired, the list stays readable, nothing is sent, and a write fails without Retry`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.session.value = Session.Expired
            runCurrent()

            assertNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            assertEquals(Outcome.Failure(DataError.SessionExpired), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(Outcome.Failure(DataError.SessionExpired), s.list.sync())
            settle()

            assertEquals(listOf(fixtures.entries.toSet()), seen.map { it.toSet() })
            assertEquals(seededAt, s.syncedAt())
            assertEquals(emptyList<String>(), s.local.writes)
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(0, s.server.listDownloads)
            assertEquals(0, s.mal.entryReads)
            // The error the shell offers no Retry for.
            assertEquals(listOf(WriteFailure(DataError.SessionExpired, ListWrite.MarkNextWatched(anime))), failures)
        }

    @Test
    fun `S6 an edit waiting when the session expires is not sent, returns to MAL's state and fails without Retry`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            val change = checkNotNull(s.list.write(ListWrite.MarkNextWatched(anime)))
            s.session.value = Session.Expired
            settle()

            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(fixtures.entryFor(anime.id), s.shown(anime.id))
            assertEquals(
                listOf(WriteFailure(DataError.SessionExpired, ListWrite.Save(anime, checkNotNull(change.after).values))),
                failures,
            )
        }

    @Test
    fun `S9 a sign-out clears the stored list, and the next sign-in starts from nothing`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            s.session.value = Session.SignedOut
            runCurrent()

            assertEquals(emptyList<ListEntry>(), seen.last())
            assertNull(s.syncedAt())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), s.local.readAll())
            assertEquals(StoredList(owner = null, syncedAt = null), s.local.state())

            s.session.value = b
            runCurrent()
            assertEquals(emptyList<ListEntry>(), seen.last())
            assertNull(s.syncedAt())
            assertEquals(StoredList(b.signIn, syncedAt = null), s.local.state())
            assertEquals(0, s.server.listDownloads)

            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()
            assertEquals(s.server.entries().toSet(), seen.last().toSet())
        }

    @Test
    fun `a new sign-in is a new list, straight after another sign-in and after an expired session`() =
        runTest {
            val s = subject()
            val seen = observe(s)
            val failures = failures(s)
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            s.session.value = b
            settle()

            assertEquals(emptyList<ListEntry>(), seen.last())
            assertEquals(StoredList(b.signIn, syncedAt = null), s.local.state())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), s.local.readAll())
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(emptyList<WriteFailure>(), failures)

            assertEquals(Outcome.Success(Unit), s.list.sync())
            s.session.value = Session.Expired
            runCurrent()
            assertEquals(s.server.entries().toSet(), seen.last().toSet())
            val c = Session.SignedIn(SignInId("c"))
            s.session.value = c
            runCurrent()
            assertEquals(emptyList<ListEntry>(), seen.last())
            assertEquals(StoredList(c.signIn, syncedAt = null), s.local.state())
        }

    @Test
    fun `a start under another sign-in than the stored list's shows nothing of it, one under its own or expired keeps it`() =
        runTest {
            val other = subject(session = b)
            assertEquals(emptyList<ListEntry>(), other.list.observeEntries().first())
            assertEquals(StoredList(b.signIn, syncedAt = null), other.local.state())
            assertEquals(emptyMap<AnimeId, StoredEntry>(), other.local.readAll())

            for (session in listOf(a, Session.Expired)) {
                val same = subject(session = session)
                settle()
                assertEquals(fixtures.entries.toSet(), same.list.observeEntries().first().toSet())
                assertEquals(StoredList(a.signIn, seededAt), same.local.state())
                assertEquals(emptyList<String>(), same.local.writes)
                assertEquals(0, same.server.listDownloads)
            }
        }

    @Test
    fun `S9 a start signed out clears a list that was left stored`() =
        runTest {
            val s = subject(session = Session.SignedOut)
            assertEquals(emptyMap<AnimeId, StoredEntry>(), s.local.readAll())
            assertEquals(StoredList(owner = null, syncedAt = null), s.local.state())
            assertEquals(emptyList<ListEntry>(), s.list.observeEntries().first())
        }

    @Test
    fun `the time of the last successful sync is stored, a failed sync leaves it, and the next start says it`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            val times = mutableListOf<Instant?>()
            backgroundScope.launch { s.list.observeSyncedAt().collect { times += it } }
            s.clock.ahead = java.time.Duration.ofHours(2)
            s.server.offline.value = true
            assertEquals(Outcome.Failure(DataError.NoConnection), s.list.sync())
            assertEquals(seededAt, s.syncedAt())

            s.server.offline.value = false
            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()
            val synced = fixtures.now + s.clock.ahead
            assertEquals(listOf(seededAt, synced), times)
            assertEquals(synced, s.local.state().syncedAt)

            kill(s, process)
            val restarted = s.engine(backgroundScope)
            assertEquals(synced, restarted.observeSyncedAt().first())
            settle()
            assertEquals(1, s.server.listDownloads)
        }

    @Test
    fun `W5 the restore clears the time of the last sync, and the sync it owes stays owed until one succeeds`() =
        runTest {
            val process = childScope()
            val s = subject(scope = process)
            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()
            kill(s, process)
            assertEquals(seededAt, s.local.state().syncedAt)

            s.server.offline.value = true
            val restarted = s.engine(backgroundScope)
            assertNull(restarted.observeSyncedAt().first())
            settle()
            assertNull(restarted.observeSyncedAt().first())
            assertNull(s.local.state().syncedAt)

            s.server.offline.value = false
            assertEquals(Outcome.Success(Unit), restarted.sync())
            assertEquals(fixtures.now, restarted.observeSyncedAt().first())
            assertEquals(fixtures.now, s.local.state().syncedAt)
        }

    @Test
    fun `an edit writes values alone, whatever data of the title it was made with`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val values = checkNotNull(fixtures.entryFor(anime.id)).values.copy(status = ListStatus.OnHold)
            s.local.calls.clear()

            val change = checkNotNull(s.list.write(ListWrite.Save(anime.renewed(), values)))
            assertEquals(anime, change.after?.anime)
            settle()

            assertEquals(listOf("write", "write"), s.local.writes)
            assertEquals(anime, s.shown(anime.id)?.anime)
            assertEquals(anime, s.local.read(anime.id)?.anime)
            assertEquals(values, s.server.entry(anime.id)?.values)
        }

    @Test
    fun `a title new to the store brings its data, and one the store knows keeps the data it has`() =
        runTest {
            val s = subject()
            val new = FixtureAnime.notOnListAiring
            val known = FixtureAnime.droppedMovie
            val values = checkNotNull(fixtures.entryFor(known.id)).values
            s.editOnWebsite(known, null)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(StoredTitle(known, entry = null), s.local.read(known.id))
            assertNull(s.local.read(new.id))
            s.local.calls.clear()

            s.list.write(ListWrite.Save(new, values))
            s.list.write(ListWrite.Save(known.renewed(), values))
            assertEquals(listOf("add", "write"), s.local.writes)
            assertEquals(new, s.shown(new.id)?.anime)
            assertEquals(known, s.shown(known.id)?.anime)
            settle()
            assertEquals(known, s.local.read(known.id)?.anime)
            assertEquals(values, s.server.entry(new.id)?.values)

            // The store's own guard, for a title it came to know after the engine read it.
            s.store.add(new.renewed(), StoredEntry.settled(ListRecord(values, fixtures.now)))
            assertEquals(new, s.store.read(new.id)?.anime)
        }

    @Test
    fun `a download brings a title's data while an edit of the title is on its way, and no answer takes it back`() =
        runTest {
            val s = subject()
            val answered = FixtureAnime.exactToWatch
            val failed = FixtureAnime.exactCaughtUp
            s.server.writeLatency = { 5.seconds }
            s.list.write(ListWrite.MarkNextWatched(answered))
            s.list.write(ListWrite.MarkNextWatched(failed))
            s.server.retitle(answered.renewed())
            s.server.retitle(failed.renewed())

            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(emptyList<ListRequest>(), s.server.requests)
            assertEquals(answered.renewed(), s.shown(answered.id)?.anime)
            assertEquals(13, s.shownWatched(answered))
            assertEquals(failed.renewed(), s.shown(failed.id)?.anime)

            advanceTimeBy(4.seconds)
            assertEquals(13, s.serverWatched(answered))
            s.server.failingWrites.value = true
            settle()

            assertEquals(answered.renewed(), s.shown(answered.id)?.anime)
            assertEquals(13, s.shownWatched(answered))
            assertEquals(checkNotNull(fixtures.entryFor(failed.id)).copy(anime = failed.renewed()), s.shown(failed.id))
        }

    @Test
    fun `the answers to a send and to a write bring no title data`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            s.mal.answering = { it.renewed() }

            assertEquals(Outcome.Success(Unit), s.list.send(ListWrite.MarkWatched(anime, 13)))
            assertEquals(13, s.shownWatched(anime))
            assertEquals(anime, s.shown(anime.id)?.anime)

            s.list.write(ListWrite.MarkNextWatched(anime))
            settle()
            assertEquals(14, s.serverWatched(anime))
            assertEquals(14, s.shownWatched(anime))
            assertEquals(anime, s.shown(anime.id)?.anime)
            assertEquals(anime, s.local.read(anime.id)?.anime)
        }

    @Test
    fun `a sync hands the store the entries MAL changed and no other, also when a title's data changed`() =
        runTest {
            val s = subject()
            val renewed = FixtureAnime.exactToWatch.renewed()
            val edited = FixtureAnime.exactCaughtUp
            s.server.retitle(renewed)

            assertEquals(Outcome.Success(Unit), s.list.sync())
            val first = s.local.downloads.single()
            assertEquals(emptyMap<AnimeId, StoredEntry?>(), first.entries)
            assertEquals(s.server.entries().map { it.anime }.toSet(), first.titles.toSet())
            assertEquals(checkNotNull(fixtures.entryFor(renewed.id)).copy(anime = renewed), s.shown(renewed.id))

            s.editOnWebsite(edited, 9)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(setOf(edited.id), s.local.downloads.last().entries.keys)
            assertEquals(listOf("download", "download"), s.local.writes)
        }

    @Test
    fun `a sync that changes nothing leaves the store's observers alone`() =
        runTest {
            val s = subject()
            val lists = mutableListOf<List<ListEntry>>()
            val entries = mutableListOf<ListEntry?>()
            backgroundScope.launch { s.store.observeEntries().collect { lists += it } }
            backgroundScope.launch { s.store.observeEntry(FixtureAnime.exactToWatch.id).collect { entries += it } }
            runCurrent()

            assertEquals(Outcome.Success(Unit), s.list.sync())
            runCurrent()

            assertEquals(1, lists.size)
            assertEquals(1, entries.size)
            assertEquals(emptyMap<AnimeId, StoredEntry?>(), s.local.downloads.single().entries)
            assertEquals(fixtures.now, s.syncedAt())
        }

    @Test
    fun `one entry is observed by itself, and a change to another title does not reach its observer`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            val stored = mutableListOf<ListEntry?>()
            val seen = mutableListOf<ListEntry?>()
            backgroundScope.launch { s.store.observeEntry(anime.id).collect { stored += it } }
            backgroundScope.launch { s.list.observeEntry(anime.id).collect { seen += it } }
            runCurrent()

            s.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            settle()
            assertEquals(listOf(fixtures.entryFor(anime.id)), stored)
            assertEquals(listOf(fixtures.entryFor(anime.id)), seen)

            s.list.write(ListWrite.MarkNextWatched(anime))
            runCurrent()
            assertEquals(listOf(12, 13), seen.map { it?.values?.watchedEpisodes })
            assertEquals(listOf("observeEntry"), s.local.calls.filter { it.startsWith("observe") })
        }

    @Test
    fun `a store that cannot be read is tried three times in a row, not three times in all`() =
        runTest {
            val s = subject()
            val anime = FixtureAnime.exactToWatch
            var ended: DataException? = null
            val seen = mutableListOf<List<ListEntry>>()
            s.local.subscriptionsToFail = 2
            backgroundScope.launch {
                try {
                    s.list.observeEntries().collect { seen += it }
                } catch (e: DataException) {
                    ended = e
                }
            }
            settle()
            assertEquals(12, seen.watched(anime))

            s.local.subscriptionsToFail = 1
            s.local.close()
            settle()
            s.list.write(ListWrite.MarkNextWatched(anime))
            runCurrent()

            assertNull(ended)
            assertEquals(5, s.local.subscriptions)
            assertEquals(13, seen.watched(anime))
        }

    @Test
    fun `W5 an exception of the engine's own while a queued edit is sent fails the edit and keeps its title in later syncs`() =
        runTest {
            val s = subject()
            val failures = failures(s)
            val anime = FixtureAnime.exactToWatch
            s.mal.answering = { FixtureAnime.exactCaughtUp }
            s.list.write(ListWrite.MarkNextWatched(anime))
            s.list.write(ListWrite.MarkNextWatched(anime))
            val shown = checkNotNull(s.shown(anime.id)).values
            settle()

            assertEquals(listOf(13), s.server.requests.map { it.values?.watchedEpisodes })
            assertEquals(12, s.shownWatched(anime))
            assertEquals(listOf(WriteFailure(DataError.ServerError, ListWrite.Save(anime, shown))), failures)
            assertEquals(1, s.unexpected.size)

            s.mal.answering = { it }
            s.editOnWebsite(anime, 20)
            assertEquals(Outcome.Success(Unit), s.list.sync())
            assertEquals(20, s.shownWatched(anime))
        }

    private companion object {
        val STORE_TIME = 10.milliseconds
    }
}
