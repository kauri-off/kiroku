package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.AiringTime
import io.github.kauri_off.kiroku.core.airing.InMemoryAiringStore
import io.github.kauri_off.kiroku.core.airing.ListAiring
import io.github.kauri_off.kiroku.core.airing.ScriptedAiringSource
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.demo.DemoServer
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.hour
import io.github.kauri_off.kiroku.core.notify.NotifyCases.other
import io.github.kauri_off.kiroku.core.notify.NotifyCases.otherAfter
import io.github.kauri_off.kiroku.core.notify.NotifyCases.title
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// What the notification engine must do over any store; a store passes it by subclassing.
@OptIn(ExperimentalCoroutinesApi::class)
abstract class NotificationEngineContract {
    // A new, empty store, on a device that knows the titles of [NotifyCases].
    protected abstract fun store(scope: TestScope): NotificationStore

    // Damages the record the last store keeps so that it reads as nobody's; false for a store that cannot be damaged.
    protected open suspend fun damageRecord(): Boolean = false

    private val a = Session.SignedIn(SignInId("a"))
    private val b = Session.SignedIn(SignInId("b"))

    // A day before the first episode of [title].
    private val start: Instant = week(0).minus(day)
    private val halfHour: Duration = Duration.ofMinutes(30)
    private val watching = ListValues(ListStatus.Watching, 0, null, null, null)

    // What a notification says beside its title.
    private data class Says(
        val episode: Int,
        val toWatch: Int?,
        val marks: Int,
    )

    private val nearAlarm: Duration = Duration.ofMinutes(15)

    private class MovingClock(
        @Volatile var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    // The process an engine runs in: it dies once it has made the step told, and nothing of it runs after that.
    private class Process(
        val scope: CoroutineScope?,
    ) {
        var steps = 0
        var diesAfter: Int? = null

        fun step() {
            steps++
            if (steps == diesAfter) {
                scope?.cancel()
                throw CancellationException("The process died")
            }
        }
    }

    // A store as a database is: every call suspends, and it fails as told to.
    private class DeviceStore(
        private val inner: NotificationStore,
        private val step: () -> Unit,
    ) : NotificationStore {
        @Volatile
        var unreadable = false

        @Volatile
        var failingWrites = false

        @Volatile
        var writesToFail = 0

        override fun observeSettings(): Flow<NotificationSettings> =
            flow {
                fault()
                emitAll(inner.observeSettings())
            }

        override suspend fun writeSettings(settings: NotificationSettings) {
            written()
            inner.writeSettings(settings)
        }

        override suspend fun read(): StoredNotifications {
            fault()
            return inner.read()
        }

        override fun observeSent(): Flow<List<SentNotification>> =
            flow {
                fault()
                emitAll(inner.observeSent())
            }

        override suspend fun write(
            record: NotificationRecord,
            sent: List<SentNotification>,
            keptFrom: Instant,
        ) {
            written()
            inner.write(record, sent, keptFrom)
            step()
        }

        override suspend fun clear(owner: SignInId?) {
            written()
            inner.clear(owner)
            step()
        }

        private suspend fun fault() {
            yield()
            if (unreadable) throw IOException("The store cannot be opened")
        }

        private suspend fun written() {
            fault()
            if (failingWrites) throw IOException("The disk is full")
            if (writesToFail > 0) {
                writesToFail--
                throw IOException("The write was aborted")
            }
        }
    }

    private class DeviceAlarm(
        private val inner: NotificationAlarm,
        private val step: () -> Unit,
    ) : NotificationAlarm {
        @Volatile
        var throwing: Exception? = null

        // Runs once, when the alarm is next set and before it is.
        @Volatile
        var beforeSet: (suspend () -> Unit)? = null

        override suspend fun set(at: Instant?) {
            yield()
            beforeSet?.let {
                beforeSet = null
                it()
            }
            throwing?.let { throw it }
            inner.set(at)
            step()
        }
    }

    private class DeviceShown(
        private val inner: ShownNotifications,
        private val step: () -> Unit,
    ) : ShownNotifications {
        @Volatile
        var throwing: Exception? = null

        // Runs once, when a notification is next posted and before it is.
        @Volatile
        var beforePost: (suspend () -> Unit)? = null

        // The device does not list what was posted yet.
        @Volatile
        var lagging = false

        override suspend fun shown(): Map<AnimeId, Shown> {
            yield()
            if (lagging) return emptyMap()
            return inner.shown()
        }

        override suspend fun post(
            anime: Anime,
            notification: Shown,
            alert: Boolean,
        ): Boolean {
            yield()
            throwing?.let { throw it }
            beforePost?.let {
                beforePost = null
                it()
            }
            return inner.post(anime, notification, alert).also { step() }
        }

        override suspend fun withdraw(titles: Set<AnimeId>) {
            yield()
            inner.withdraw(titles)
            step()
        }
    }

    // The list as the engine reads it: still being read, or unreadable, while told so.
    private class ReadList(
        private val inner: ListSource,
    ) : ListSource by inner {
        val unreadable = MutableStateFlow(false)
        val beingRead = MutableStateFlow(false)

        // Runs once, when the list was next read and before its reader has it.
        @Volatile
        var afterRead: (suspend () -> Unit)? = null

        override fun observeEntries(): Flow<List<ListEntry>> =
            combine(inner.observeEntries(), unreadable, beingRead) { entries, unreadable, beingRead ->
                if (unreadable) throw DataException(DataError.StorageError)
                if (beingRead) return@combine null
                afterRead?.let {
                    afterRead = null
                    it()
                }
                entries
            }.filterNotNull()
    }

    // A device with its list, its airing data and what it keeps across processes: the store, the alarm and what is shown.
    private inner class World(
        private val test: TestScope,
        val clock: MovingClock,
        entries: List<ListEntry>,
        schedules: Map<AnimeId, AiringSchedule>,
        stored: Map<AnimeId, AiringSchedule>,
        val inner: NotificationStore,
    ) {
        val unexpected = CopyOnWriteArrayList<Throwable>()
        val reports = CopyOnWriteArrayList<RunReport>()
        val session = MutableStateFlow<Session?>(a)
        val server = DemoServer(entries, clock, kotlin.time.Duration.ZERO)
        val listStore = InMemoryLocalList()
        val stores: ListRepository =
            ListEngine(server, listStore, session.filterNotNull(), clock, test.backgroundScope) { unexpected += it }
        val list = ReadList(stores)
        val source = ScriptedAiringSource(schedules, clock)
        val airingStore = InMemoryAiringStore(stored.mapValues { StoredAiring(it.value, clock.now) })
        val airing: AiringRepository =
            ListAiring(source, airingStore, stores, AiringTime(clock) { awaitCancellation() }, test.backgroundScope) { unexpected += it }
        val online = MutableStateFlow(true)
        var process = Process(null)
        val store = DeviceStore(inner) { process.step() }
        val alarm = InMemoryNotificationAlarm()
        val alarms = DeviceAlarm(alarm) { process.step() }
        val shown = InMemoryShownNotifications()
        val shows = DeviceShown(shown) { process.step() }
        lateinit var engine: NotificationEngine

        // A new process, the one before it dead: a new engine over what the device keeps.
        fun start(): NotificationEngine {
            process.scope?.cancel()
            val background = test.backgroundScope
            process = Process(CoroutineScope(background.coroutineContext + Job(background.coroutineContext[Job])))
            engine =
                NotificationEngine(
                    store,
                    alarms,
                    shows,
                    list,
                    airing,
                    session.filterNotNull(),
                    object : Connection {
                        override fun observeOnline(): Flow<Boolean> = online
                    },
                    clock,
                    checkNotNull(process.scope),
                    reports = { reports += it },
                    unexpected = { unexpected += it },
                )
            return engine
        }

        // Every alert, in order: the title and the episode.
        val alerts: List<Pair<AnimeId, Int>>
            get() = shown.posts.filter { it.alert }.map { it.announcement.anime.id to it.announcement.episode }

        val showing: Map<AnimeId, Says>
            get() = shown.notifications.value.mapValues { (_, post) -> post.notification.let { Says(it.episode, it.toWatch, it.marks) } }

        suspend fun record(): NotificationRecord = inner.read().record

        suspend fun sent(): List<SentNotification> = inner.observeSent().first()

        suspend fun write(write: ListWrite) {
            stores.write(write)
            test.runCurrent()
        }

        // What the airing source has changes, and the app refreshes.
        suspend fun refreshed(schedules: Map<AnimeId, AiringSchedule>) {
            source.schedules = schedules
            assertEquals(Outcome.Success(Unit), airing.refresh())
            test.runCurrent()
        }

        // The device's time passes to [until]; when the alarm's time comes it fires and the app starts a run.
        suspend fun pass(until: Instant) {
            repeat(MOST_ALARMS) {
                val at = alarm.at.value
                if (at == null || at.isAfter(until)) {
                    clock.now = until
                    test.runCurrent()
                    return
                }
                clock.now = maxOf(clock.now, at)
                alarm.gone()
                // The alarm starts the app's process when it is dead.
                if (process.scope?.isActive != true) start()
                runCatching { engine.run(RunTrigger.Alarm) }
                test.runCurrent()
            }
            throw AssertionError("The alarm keeps firing")
        }
    }

    // A device signed in as [a], its list downloaded and its airing data stored; [started], the app was opened and its run has ended.
    private suspend fun TestScope.world(
        entries: List<ListEntry> = listOf(entry()),
        schedules: Map<AnimeId, AiringSchedule> = NotifyCases.schedules,
        now: Instant = start,
        settings: NotificationSettings = NotificationSettings(),
        started: Boolean = true,
    ): World {
        val inner = store(this)
        if (settings != NotificationSettings()) inner.writeSettings(settings)
        val stored =
            entries.filter { it.status == ListStatus.Watching || it.tracked }.mapNotNull {
                schedules[it.anime.id]?.let { s ->
                    it.anime.id to
                        s
                }
            }
        val world = World(this, MovingClock(now), entries, schedules, stored.toMap(), inner)
        world.listStore.clear(a.signIn)
        world.listStore.download(entries.map { it.anime }, entries.associate { it.anime.id to StoredEntry.settled(it.record) }, now)
        if (started) {
            // The app is opened.
            world.start()
            world.engine.run(RunTrigger.AppOpened)
            runCurrent()
        }
        return world
    }

    private fun AiringSchedule.moved(
        number: Int,
        by: Duration,
    ) = copy(episodes = episodes.map { if (it.number == number) it.copy(airsAt = it.airsAt.plus(by)) else it })

    private fun Map<AnimeId, AiringSchedule>.moved(
        anime: Anime,
        number: Int,
        by: Duration,
    ) = this + (anime.id to getValue(anime.id).moved(number, by))

    private fun sentOf(sent: List<SentNotification>) = sent.map { listOf(it.anime.id, it.episode, it.airedAt, it.notifyAt) }

    @Test
    fun `the store gives back the record, what was posted and the settings, forgets what is older than asked, and is cleared`() =
        runTest {
            val store = store(this)
            assertEquals(StoredNotifications(null, NotificationRecord()), store.read())
            assertEquals(NotificationSettings(), store.observeSettings().first())
            assertEquals(emptyList<SentNotification>(), store.observeSent().first())
            val settings = mutableListOf<NotificationSettings>()
            val posted = mutableListOf<List<SentNotification>>()
            backgroundScope.launch { store.observeSettings().collect { settings += it } }
            backgroundScope.launch { store.observeSent().collect { posted += it } }

            store.clear(a.signIn)
            assertEquals(StoredNotifications(a.signIn, NotificationRecord()), store.read())
            val record =
                NotificationRecord(
                    since = start,
                    plannedSince = start.plus(hour),
                    titles =
                        mapOf(
                            title.id to
                                TitleRecord(Followed(ListStatus.Watching, start), Announced(3, week(2)), takenBackTo = 2, standing = true),
                            other.id to TitleRecord(Followed(ListStatus.Planned, start.plus(hour)), announced = null),
                        ),
                )
            val sent =
                Recheck.entries.mapIndexed { index, recheck ->
                    val anime = if (index % 2 == 0) title else other
                    val notifyAt = week(index).plus(halfHour)
                    SentNotification(anime, index + 1, week(index), notifyAt, week(index).plus(hour), recheck, shown = index % 2 == 0)
                }
            store.write(record, sent.take(2), Instant.EPOCH)
            runCurrent()
            assertEquals(StoredNotifications(a.signIn, record), store.read())
            assertEquals(sent.take(2), store.observeSent().first())
            assertEquals(sent.take(2), posted.last())

            // An episode announced again for a later air time is there twice.
            val again = sent[1].copy(airedAt = week(5), notifyAt = week(5), postedAt = week(5))
            val later = record.copy(titles = record.titles + (title.id to TitleRecord(followed = null, Announced(4, week(3)))))
            store.write(later, sent.drop(2) + again, sent[1].postedAt)
            runCurrent()
            assertEquals(StoredNotifications(a.signIn, later), store.read())
            assertEquals(sent.drop(1) + again, store.observeSent().first())
            assertEquals(sent.drop(1) + again, posted.last())

            for (delay in NotificationDelay.entries) {
                val chosen = NotificationSettings(on = false, alsoPlanned = true, delay = delay)
                store.writeSettings(chosen)
                runCurrent()
                assertEquals(chosen, store.observeSettings().first())
                assertEquals(chosen, settings.last())
            }

            store.clear(b.signIn)
            runCurrent()
            assertEquals(StoredNotifications(b.signIn, NotificationRecord()), store.read())
            assertEquals(emptyList<SentNotification>(), store.observeSent().first())
            assertEquals(emptyList<SentNotification>(), posted.last())
            assertEquals(
                "the settings stay",
                NotificationSettings(on = false, alsoPlanned = true, delay = NotificationDelay.entries.last()),
                settings.last(),
            )
            store.clear(null)
            assertEquals(StoredNotifications(null, NotificationRecord()), store.read())
        }

    @Test
    fun `N5 the first run over a stored list announces nothing that aired before it, and leaves the alarm at what comes next`() =
        runTest {
            val w = world(now = week(2).plus(hour))

            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(3), w.alarm.at.value)
            assertEquals(week(2).plus(hour), w.record().since)
            assertEquals(a.signIn, w.inner.read().owner)
            assertEquals(listOf(Coming(title, 4, week(3), week(3))), w.engine.observeComing().first())
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `N1 runs started at once by every trigger announce an episode once`() =
        runTest {
            val w = world()
            w.clock.now = week(0)

            val reports = RunTrigger.entries.map { async { w.engine.run(it) } }.awaitAll()

            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.shown.posts.size)
            assertEquals(listOf(1), reports.flatMap { it.posted }.map { it.episode })
            assertEquals(listOf(1), w.sent().map { it.episode })
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `N1 a refresh that lands between an air time and its notification leaves the episode announced once, at its time`() =
        runTest {
            val w = world(settings = NotificationSettings(delay = NotificationDelay.HalfHour))
            w.clock.now = week(0).plus(Duration.ofMinutes(10))
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
            assertEquals(week(0).plus(halfHour), w.alarm.at.value)

            w.refreshed(NotifyCases.schedules)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(0).plus(halfHour), w.alarm.at.value)

            w.pass(week(0).plus(halfHour))
            assertEquals(listOf(title.id to 1), w.alerts)
            w.refreshed(NotifyCases.schedules)
            w.pass(week(0).plus(hour))
            w.engine.run(RunTrigger.Periodic)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.sent().size)
        }

    @Test
    fun `N1 N8 a delay changed after an episode was announced announces it no second time, and moves the alarm`() =
        runTest {
            val w = world()
            w.pass(week(0))
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(week(1), w.alarm.at.value)

            assertEquals(Outcome.Success(Unit), w.engine.changeSettings { it.copy(delay = NotificationDelay.ThreeHours) })
            assertEquals(week(1).plus(hour.multipliedBy(3)), w.alarm.at.value)
            w.pass(week(0).plus(hour.multipliedBy(4)))
            assertEquals(listOf(title.id to 1), w.alerts)

            assertEquals(Outcome.Success(Unit), w.engine.changeSettings { it.copy(delay = NotificationDelay.None) })
            assertEquals(week(1), w.alarm.at.value)
            assertEquals(1, w.sent().size)
        }

    @Test
    fun `N1 N2 an alarm that never fired loses no episode, and firing late it announces none again`() =
        runTest {
            val w = world()
            w.alarm.gone()
            w.clock.now = week(0).plus(hour.multipliedBy(2))

            val report = w.engine.run(RunTrigger.AppOpened)

            assertEquals(listOf(title.id to 1), w.alerts)
            val sent = SentNotification(title, 1, week(0), week(0), week(0).plus(hour.multipliedBy(2)), Recheck.NotAsked)
            assertEquals(listOf(sent), report.posted)
            assertEquals(listOf(sent), w.sent())
            assertEquals(week(1), w.alarm.at.value)

            w.engine.run(RunTrigger.Alarm)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.shown.posts.size)
            assertEquals(listOf(sent), w.sent())
        }

    @Test
    fun `N1 N8 a new engine over the same store, as after a restart or an update, announces nothing again and sets the alarm again`() =
        runTest {
            val w = world()
            w.pass(week(0))
            assertEquals(listOf(title.id to 1), w.alerts)
            val record = w.record()

            // A restart loses the alarm; what is shown stays.
            w.alarm.gone()
            w.clock.now = week(0).plus(hour)
            w.start()
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.DeviceRestarted).end)
            runCurrent()
            assertEquals(week(1), w.alarm.at.value)
            w.start()
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppUpdated).end)
            runCurrent()

            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.shown.posts.size)
            assertEquals(1, w.sent().size)
            assertEquals(record, w.record())
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(week(1), w.alarm.at.value)
        }

    // Two titles announced; then, in one run, the second episode of one is due and the other's is watched.
    private suspend fun TestScope.cutOff(diesAfter: Int?): World {
        val w = world(entries = listOf(entry(), entry(other)))
        w.pass(week(0).plus(otherAfter))
        assertEquals(listOf(title.id to 1, other.id to 1), w.alerts)
        w.clock.now = week(1)
        w.alarm.gone()
        w.process.steps = 0
        w.process.diesAfter = diesAfter
        w.stores.write(ListWrite.MarkNextWatched(other))
        runCatching { w.engine.run(RunTrigger.Alarm) }
        runCurrent()
        assertEquals(diesAfter == null, checkNotNull(w.process.scope).isActive)
        return w
    }

    @Test
    fun `N1 N2 a run cut off after each of its steps neither loses its episode nor alerts for it twice`() =
        runTest {
            val whole = cutOff(diesAfter = null)
            val steps = whole.process.steps
            // The alarm, the post, the withdrawal and the record.
            assertTrue("$steps steps", steps >= 4)
            assertEquals(listOf(title.id to 1, other.id to 1, title.id to 2), whole.alerts)
            assertEquals(mapOf(title.id to Says(2, toWatch = 2, marks = 1)), whole.showing)
            assertEquals(week(1).plus(otherAfter), whole.alarm.at.value)

            for (step in 1..steps) {
                val w = cutOff(diesAfter = step)
                // The app is opened later: a new process over what the dead one left.
                w.clock.now = week(1).plus(hour)
                w.start()
                assertEquals("step $step", RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
                runCurrent()

                // N2: or nothing but the alarm comes, which a run cut off before its record has left near.
                val alone = cutOff(diesAfter = step)
                alone.pass(week(1).plus(hour))
                assertEquals("step $step, the alarm alone", whole.alerts, alone.alerts)
                assertEquals("step $step, the alarm alone", whole.showing, alone.showing)
                assertEquals("step $step, the alarm alone", sentOf(whole.sent()), sentOf(alone.sent()))
                assertEquals("step $step, the alarm alone", whole.record(), alone.record())
                assertEquals("step $step, the alarm alone", whole.alarm.at.value, alone.alarm.at.value)

                assertEquals("step $step", whole.alerts, w.alerts)
                assertEquals("step $step", whole.showing, w.showing)
                assertEquals("step $step", sentOf(whole.sent()), sentOf(w.sent()))
                assertEquals("step $step", whole.record(), w.record())
                assertEquals("step $step", whole.alarm.at.value, w.alarm.at.value)
            }
        }

    @Test
    fun `N1 a notification dismissed between a cut-off run's post and the next run alerts once more, and is recorded once`() =
        runTest {
            // The alarm, then the post, after which the process dies before it recorded.
            val w = world()
            w.clock.now = week(0)
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals("a near alarm stands while the episode is not recorded", week(0).plus(nearAlarm), w.alarm.at.value)

            w.shown.dismiss(title.id)
            w.start()
            w.engine.run(RunTrigger.AppOpened)
            runCurrent()

            assertEquals(listOf(title.id to 1, title.id to 1), w.alerts)
            assertEquals(listOf(1), w.sent().map { it.episode })
        }

    @Test
    fun `N1 a notification of a cut-off run that reads otherwise by the next run is replaced without a second alert`() =
        runTest {
            val w = world()
            w.pass(week(0))
            w.clock.now = week(1)
            w.alarm.gone()
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            assertEquals(mapOf(title.id to Says(2, toWatch = 2, marks = 1)), w.showing)

            w.write(ListWrite.MarkNextWatched(title))
            w.start()
            w.engine.run(RunTrigger.AppOpened)
            runCurrent()

            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
            assertEquals(mapOf(title.id to Says(2, toWatch = 1, marks = 2)), w.showing)
            assertEquals(listOf(1, 2), w.sent().map { it.episode })
        }

    @Test
    fun `N2 the alarm's run cut off at its first step, or refused its post, is made again by the near alarm it left`() =
        runTest {
            val cut = world()
            cut.clock.now = week(0)
            cut.alarm.gone()
            cut.process.steps = 0
            cut.process.diesAfter = 1
            assertTrue(runCatching { cut.engine.run(RunTrigger.Alarm) }.isFailure)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), cut.alerts)
            assertEquals("its own alarm is spent, so another stands at once", week(0).plus(nearAlarm), cut.alarm.at.value)
            cut.pass(week(0).plus(hour))
            assertEquals(listOf(title.id to 1), cut.alerts)
            assertEquals(listOf(week(0).plus(nearAlarm)), cut.sent().map { it.postedAt })
            assertEquals(week(1), cut.alarm.at.value)

            val refused = world()
            refused.clock.now = week(0)
            refused.alarm.gone()
            refused.shows.throwing = IllegalStateException("The device refused")
            assertEquals(RunEnd.Failed, refused.engine.run(RunTrigger.Alarm).end)
            assertEquals(week(0).plus(nearAlarm), refused.alarm.at.value)
            refused.shows.throwing = null
            refused.pass(week(0).plus(hour))
            assertEquals(listOf(title.id to 1), refused.alerts)
            assertEquals(week(1), refused.alarm.at.value)
        }

    @Test
    fun `N2 with the alarm refusing too, a later trigger announces the episode within its three days and not after them`() =
        runTest {
            val broken = IllegalStateException("The device refused")
            for (late in listOf(false, true)) {
                val w = world()
                w.clock.now = week(0)
                w.alarm.gone()
                w.alarms.throwing = broken
                w.shows.throwing = broken
                val failed = w.engine.run(RunTrigger.Alarm)
                assertEquals(RunEnd.Failed, failed.end)
                assertTrue(failed.alarmFailed)
                assertNull("no alarm is left", w.alarm.at.value)

                // Nothing brings the app back but the periodic work.
                w.alarms.throwing = null
                w.shows.throwing = null
                w.clock.now = week(0).plus(day.multipliedBy(3)).plusSeconds(if (late) 1 else 0)
                assertEquals(RunEnd.Done, w.engine.run(RunTrigger.Periodic).end)
                assertEquals("late $late", if (late) emptyList() else listOf(title.id to 1), w.alerts)
                assertEquals(week(1), w.alarm.at.value)
            }
        }

    @Test
    fun `N7 a newer notification of a cut-off run is not replaced by the older one that stands, and is told as sent`() =
        runTest {
            val w = world()
            w.pass(week(0))
            w.shown.dismiss(title.id)
            w.clock.now = week(1)
            w.alarm.gone()
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            assertEquals(mapOf(title.id to Says(2, toWatch = 2, marks = 1)), w.showing)
            assertEquals(listOf(1), w.sent().map { it.episode })
            val posts = w.shown.posts.size

            // No run ends for more than three days: episode 2 is due no longer, and the record says episode 1 stands.
            w.clock.now = week(1).plus(day.multipliedBy(3)).plus(hour)
            w.start()
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
            runCurrent()

            assertEquals("nothing is posted in its place", posts, w.shown.posts.size)
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(listOf(1 to week(0), 2 to week(1)), w.sent().map { it.episode to it.airedAt })
            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
            w.engine.run(RunTrigger.Periodic)
            assertEquals(2, w.sent().size)
        }

    @Test
    fun `N1 a notification of a cut-off run stays while the clock is set back behind it, and alerts no second time`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            w.alarm.gone()
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            assertEquals(listOf(title.id to 1), w.alerts)

            w.clock.now = week(0).minus(hour.multipliedBy(2))
            w.start()
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals(week(0), w.alarm.at.value)

            w.pass(week(0).plus(hour))
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.shown.posts.size)
            assertEquals(listOf(1), w.sent().map { it.episode })
        }

    @Test
    fun `N7 a notification of a cut-off run goes while the clock is behind it, once its episode is watched or its title is gone`() =
        runTest {
            for (change in listOf(ListWrite.MarkNextWatched(title), ListWrite.Remove(title))) {
                val w = world()
                w.clock.now = week(0)
                w.alarm.gone()
                w.process.steps = 0
                w.process.diesAfter = 2
                assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
                w.clock.now = week(0).minus(hour.multipliedBy(2))
                w.start()
                w.engine.run(RunTrigger.AppOpened)
                assertEquals("$change", setOf(title.id), w.showing.keys)

                w.write(change)

                assertEquals("$change", emptyMap<AnimeId, Says>(), w.showing)
                // It alerted, so it is told as sent where its title is still there to tell it with.
                assertEquals("$change", if (change is ListWrite.Remove) emptyList() else listOf(1), w.sent().map { it.episode })
                w.pass(week(0).plus(hour))
                assertEquals("$change", listOf(title.id to 1), w.alerts)
                assertEquals("$change", emptyMap<AnimeId, Says>(), w.showing)
            }
        }

    @Test
    fun `N1 a delay lengthened after a cut-off post keeps its notification, which alerts no second time at its new time`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            w.alarm.gone()
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            val later = week(0).plus(hour.multipliedBy(3))

            w.start()
            assertEquals(Outcome.Success(Unit), w.engine.changeSettings { it.copy(delay = NotificationDelay.ThreeHours) })
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals(later, w.alarm.at.value)

            w.pass(later.plus(hour))
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(later), w.sent().map { it.notifyAt })
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals("replaced in silence with its new time", listOf(true, false), w.shown.posts.map { it.alert })
            assertEquals(
                later,
                w.shown.posts
                    .last()
                    .notification.notifyAt,
            )
        }

    @Test
    fun `an unrecorded post is matched to what was sent by its air time too, so an episode announced again is told as sent again`() =
        runTest {
            val w = world()
            w.pass(week(0))
            val postponed = week(0).plus(day.multipliedBy(2))
            w.refreshed(NotifyCases.schedules.moved(title, 1, day.multipliedBy(2)))
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)

            // The run at its new time posts it again and dies before its record; then the person watches it.
            w.clock.now = postponed
            w.alarm.gone()
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            assertEquals(listOf(title.id to 1, title.id to 1), w.alerts)
            w.write(ListWrite.MarkNextWatched(title))
            w.start()
            w.engine.run(RunTrigger.AppOpened)

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(listOf(week(0), postponed), w.sent().map { it.airedAt })
        }

    @Test
    fun `S9 a post that slipped through as the owner changed is withdrawn by that run, also while the device does not list it yet`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            w.shows.beforePost = {
                w.session.value = Session.SignedOut
                w.shows.lagging = true
            }

            val report = w.engine.run(RunTrigger.AppOpened)

            assertEquals(RunEnd.SignedOut, report.end)
            assertEquals(1, w.shown.posts.size)
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(setOf(title.id), report.withdrawn)
        }

    @Test
    fun `N2 the alarm's run that fails with nothing known to be due waits longer after each failure in a row, up to a bound`() =
        runTest {
            val w = world()
            w.store.unreadable = true
            w.pass(week(0))
            var at = week(0)
            for (minutes in listOf(15L, 30L, 60L, 120L, 240L, 360L, 360L)) {
                assertEquals("after $at", at.plus(Duration.ofMinutes(minutes)), w.alarm.at.value)
                assertEquals(RunEnd.Failed to w.alarm.at.value, w.reports.last().let { it.end to it.alarm })
                at = at.plus(Duration.ofMinutes(minutes))
                w.pass(at)
            }
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)

            // A run that ends as it should ends the waiting, and the next failure starts from the shortest wait.
            w.store.unreadable = false
            w.pass(at.plus(Duration.ofMinutes(360)))
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(week(1), w.alarm.at.value)
            w.store.unreadable = true
            w.pass(week(1))
            assertEquals(week(1).plus(nearAlarm), w.alarm.at.value)
        }

    @Test
    fun `S9 a run posts nothing more once the session's owner has changed under it`() =
        runTest {
            for (next in listOf<Session>(Session.SignedOut, b)) {
                // Before its first step: nothing of the run reaches the device.
                val early = world()
                early.clock.now = week(0)
                early.alarms.beforeSet = { early.session.value = next }
                early.engine.run(RunTrigger.AppOpened)
                runCurrent()
                assertEquals("$next", emptyList<InMemoryShownNotifications.Post>(), early.shown.posts.toList())
                assertEquals("$next", emptyList<SentNotification>(), early.sent())

                // Between two posts of one run: the second is not made.
                val w = world(entries = listOf(entry(), entry(other)))
                w.pass(week(0).plus(day))
                w.alarm.gone()
                w.clock.now = week(1)
                val posts = w.shown.posts.size
                w.shows.beforePost = { w.session.value = next }
                w.engine.run(RunTrigger.AppOpened)
                runCurrent()
                assertEquals("$next", posts + 1, w.shown.posts.size)
                assertEquals("$next", emptyMap<AnimeId, Says>(), w.showing)
                assertEquals("$next", emptyList<SentNotification>(), w.sent())
                assertEquals("$next", (next as? Session.SignedIn)?.signIn, w.inner.read().owner)
                assertTrue(
                    "$next",
                    w.inner
                        .read()
                        .record.titles.values
                        .none { it.announced != null },
                )
            }
        }

    @Test
    fun `N8 a list that could not be read once is read again, and a later change is followed by a run`() =
        runTest {
            val w = world()
            w.pass(week(0))
            w.list.unreadable.value = true
            runCurrent()
            w.list.unreadable.value = false
            runCurrent()
            val reports = w.reports.size

            w.write(ListWrite.MarkNextWatched(title))
            advanceTimeBy(2.minutes)
            runCurrent()

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(reports + 1, w.reports.size)
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `S9 another sign-in is not given what was coming for the one before, also while its own list cannot be read`() =
        runTest {
            val w = world()
            assertEquals(listOf(Coming(title, 1, week(0), week(0))), w.engine.observeComing().first())
            w.list.unreadable.value = true
            w.session.value = b
            runCurrent()

            assertEquals(RunEnd.Failed, w.engine.run(RunTrigger.AppOpened).end)

            assertNull(w.engine.observeComing().first())
            assertEquals(emptyList<SentNotification>(), w.engine.observeSent().first())
            assertEquals(b.signIn, w.inner.read().owner)
        }

    @Test
    fun `N2 each trigger announces what is overdue`() =
        runTest {
            for (trigger in RunTrigger.entries) {
                val w = world()
                w.alarm.gone()
                w.clock.now = week(0).plus(day)

                val report = w.engine.run(trigger)

                assertEquals("$trigger", listOf(title.id to 1), w.alerts)
                assertEquals("$trigger", trigger, report.trigger)
                assertEquals("$trigger", listOf(week(0).plus(day)), report.posted.map { it.postedAt })
                assertEquals("$trigger", week(1), w.alarm.at.value)
            }
        }

    @Test
    fun `N2 nothing is announced three days late, also counted from a delayed notification time`() =
        runTest {
            val threeDays = day.multipliedBy(3)
            val w = world(settings = NotificationSettings(delay = NotificationDelay.Hour))
            w.alarm.gone()
            w.clock.now = week(0).plus(hour).plus(threeDays).plusSeconds(1)
            w.engine.run(RunTrigger.AppOpened)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(1).plus(hour), w.alarm.at.value)

            val onTime = world(settings = NotificationSettings(delay = NotificationDelay.Hour))
            onTime.alarm.gone()
            onTime.clock.now = week(0).plus(hour).plus(threeDays)
            onTime.engine.run(RunTrigger.AppOpened)
            assertEquals(listOf(title.id to 1), onTime.alerts)
        }

    @Test
    fun `N3 a postponed episode is planned again instead of announced, and announced when it airs`() =
        runTest {
            val w = world(entries = listOf(entry(), entry(other)))
            val postponed = NotifyCases.schedules.moved(title, 1, day.multipliedBy(2))
            w.source.schedules = postponed
            w.clock.now = week(0)
            w.alarm.gone()

            val report = w.engine.run(RunTrigger.Alarm)

            assertEquals("the due title and no other", listOf(setOf(title.id)), w.source.calls.toList())
            assertEquals(Recheck.Answered, report.recheck)
            assertEquals(emptyList<SentNotification>(), report.posted)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(0).plus(day.multipliedBy(2)), report.alarm)
            assertEquals(week(0).plus(day.multipliedBy(2)), w.alarm.at.value)
            runCurrent()
            assertEquals("the run followed what it stored", 1, w.reports.count { it.began == week(0) })

            w.pass(week(0).plus(day.multipliedBy(2)))
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(week(0).plus(day.multipliedBy(2))), w.sent().map { it.airedAt })
            assertEquals(listOf(Recheck.Answered), w.sent().map { it.recheck })
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `N3 a re-check answered with what is still to air plans a postponed episode again, and announces one that aired`() =
        runTest {
            val w = world()
            w.source.onlyComing = true
            w.source.schedules = NotifyCases.schedules.moved(title, 1, day.multipliedBy(2))
            w.clock.now = week(0)
            w.alarm.gone()

            val report = w.engine.run(RunTrigger.Alarm)

            assertEquals(listOf(setOf(title.id)), w.source.rechecks.toList())
            assertEquals(Recheck.Answered, report.recheck)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(0).plus(day.multipliedBy(2)), w.alarm.at.value)

            // Episode 2 aired an hour before the alarm's run, so the answer leaves it out and the stored time stands.
            w.pass(week(0).plus(day.multipliedBy(2)))
            w.clock.now = week(1).plus(hour)
            w.alarm.gone()
            w.engine.run(RunTrigger.Alarm)

            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
            assertEquals(listOf(week(0).plus(day.multipliedBy(2)), week(1)), w.sent().map { it.airedAt })
            assertEquals(listOf(Recheck.Answered, Recheck.Answered), w.sent().map { it.recheck })
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `N3 a process the alarm starts runs nothing before the alarm's run, so a postponed episode is planned again there too`() =
        runTest {
            val w = world()
            val reports = w.reports.size
            w.source.schedules = NotifyCases.schedules.moved(title, 1, day)
            w.clock.now = week(0)
            w.alarm.gone()

            // The engine is built and given time, and the list changes, before the receiver asks for its run.
            w.start()
            runCurrent()
            w.write(ListWrite.Save(title, watching.copy(score = 5)))
            advanceTimeBy(10.minutes)
            runCurrent()
            assertEquals("no run of its own", reports, w.reports.size)
            assertNull("not known yet", w.engine.observeComing().first())
            assertEquals(NotificationSettings(), w.engine.observeSettings().first())
            assertEquals(emptyList<SentNotification>(), w.engine.observeSent().first())

            val report = w.engine.run(RunTrigger.Alarm)
            runCurrent()

            assertEquals(Recheck.Answered, report.recheck)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(0).plus(day), w.alarm.at.value)
            assertEquals(listOf(Coming(title, 1, week(0).plus(day), week(0).plus(day))), w.engine.observeComing().first())
            assertEquals("the run has followed what it read", reports + 1, w.reports.size)

            // From its first run on, the engine follows the list by itself.
            w.write(ListWrite.Save(title, watching.copy(status = ListStatus.Dropped)))
            assertNull(w.alarm.at.value)
            assertEquals(RunTrigger.Changed, w.reports.last().trigger)
        }

    @Test
    fun `a post the device does not show is recorded as sent and not shown, and its episode is not announced again later`() =
        runTest {
            val w = world()
            w.shown.blocked = true
            w.pass(week(0))

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(listOf(SentNotification(title, 1, week(0), week(0), week(0), Recheck.Answered, shown = false)), w.sent())
            assertEquals(
                listOf(false),
                w.reports
                    .last()
                    .posted
                    .map { it.shown },
            )
            assertEquals(1, w.shown.posts.size)

            // The permission is granted days later: nothing old is announced, and the next episode is shown.
            w.shown.blocked = false
            w.clock.now = week(0).plus(day.multipliedBy(2))
            w.engine.run(RunTrigger.AppOpened)
            assertEquals(1, w.shown.posts.size)
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            w.pass(week(1))
            assertEquals(mapOf(title.id to Says(2, toWatch = 2, marks = 1)), w.showing)
            assertEquals(listOf(false, true), w.sent().map { it.shown })
        }

    // The scope's end reaches its children one at a time: the run's work in the scope may end before its caller hears of it.
    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `a run asked in the engine's own scope ends cancelled with it, also when its work has ended first`() =
        runTest {
            val w = world()
            w.source.latency = 30.seconds
            w.clock.now = week(0)
            w.alarm.gone()
            val process = checkNotNull(w.process.scope)
            val before =
                process.coroutineContext.job.children
                    .toSet()
            var work = emptyList<Job>()
            lateinit var run: Deferred<RunReport>
            var raced = false
            process.coroutineContext.job.invokeOnCompletion(onCancelling = true) {
                work.forEach { it.cancel() }
                testScheduler.runCurrent()
                raced = run.isCompleted
            }
            run = process.async { w.engine.run(RunTrigger.Alarm) }
            runCurrent()
            work =
                process.coroutineContext.job.children
                    .filter { it !in before && it !== run }
                    .toList()
            assertTrue(work.isNotEmpty())

            process.cancel()
            runCurrent()

            assertTrue("the run ended before the scope's end reached it", raced)
            assertTrue("${run.getCompletionExceptionOrNull()}", run.getCompletionExceptionOrNull() is CancellationException)
        }

    @Test
    fun `a changed setting is stored and given at once while a run is under way, and is followed when that run has ended`() =
        runTest {
            val w = world()
            val reports = w.reports.size
            w.source.latency = 30.seconds
            w.clock.now = week(0)
            w.alarm.gone()
            val run = async { w.engine.run(RunTrigger.Alarm) }
            runCurrent()

            val change = async { w.engine.changeSettings { it.copy(delay = NotificationDelay.Hour) } }
            runCurrent()
            assertFalse(run.isCompleted)
            assertFalse(change.isCompleted)
            assertEquals(
                NotificationDelay.Hour,
                w.engine
                    .observeSettings()
                    .first()
                    .delay,
            )

            // The run under way reads the settings again after its re-check, so the episode waits for its new time.
            assertEquals(Recheck.Late, run.await().recheck)
            assertEquals(Outcome.Success(Unit), change.await())
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(week(0).plus(hour), w.alarm.at.value)
            runCurrent()
            assertEquals("the run under way has followed the change, so no other starts", reports + 1, w.reports.size)
            w.source.latency = kotlin.time.Duration.ZERO
            w.pass(week(0).plus(hour))
            assertEquals(listOf(title.id to 1), w.alerts)
        }

    @Test
    fun `N3 a re-check that fails leaves the stored data in use`() =
        runTest {
            val w = world()
            w.source.schedules = NotifyCases.schedules.moved(title, 1, day)
            w.source.failure = DataError.ServerError
            w.clock.now = week(0)

            val report = w.engine.run(RunTrigger.Alarm)

            assertEquals(listOf(setOf(title.id)), w.source.calls.toList())
            assertEquals(Recheck.Failed, report.recheck)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(SentNotification(title, 1, week(0), week(0), week(0), Recheck.Failed)), w.sent())
        }

    @Test
    fun `N3 a re-check slower than its bound leaves the stored data in use, and its late answer takes the announcement back`() =
        runTest {
            val w = world()
            w.source.schedules = NotifyCases.schedules.moved(title, 1, day)
            w.source.latency = 30.seconds
            w.clock.now = week(0)
            val began = currentTime

            val report = w.engine.run(RunTrigger.Alarm)

            assertEquals(NotificationEngine.RECHECK_WITHIN.inWholeMilliseconds, currentTime - began)
            assertEquals(Recheck.Late, report.recheck)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(Recheck.Late), w.sent().map { it.recheck })

            // The answer arrives: the episode has not aired, so its notification goes and it is announced when it has.
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(week(0).plus(day), w.alarm.at.value)
            w.source.latency = kotlin.time.Duration.ZERO
            w.pass(week(0).plus(day))
            assertEquals(listOf(title.id to 1, title.id to 1), w.alerts)
            assertEquals(listOf(week(0), week(0).plus(day)), w.sent().map { it.airedAt })
        }

    @Test
    fun `N3 offline the alarm's run asks nothing and announces from the stored data`() =
        runTest {
            val w = world()
            w.online.value = false
            w.clock.now = week(0)

            val report = w.engine.run(RunTrigger.Alarm)

            assertEquals(emptyList<Set<AnimeId>>(), w.source.calls.toList())
            assertEquals(Recheck.Offline, report.recheck)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(Recheck.Offline), w.sent().map { it.recheck })
        }

    @Test
    fun `N3 only the alarm's run asks the airing source, only with an episode due, and never about a title marked finished`() =
        runTest {
            val w = world()
            w.clock.now = week(0).minus(hour)
            assertEquals(Recheck.NotAsked, w.engine.run(RunTrigger.Alarm).recheck)
            w.clock.now = week(0)
            for (trigger in RunTrigger.entries - RunTrigger.Alarm) assertEquals(Recheck.NotAsked, w.engine.run(trigger).recheck)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(Recheck.NotAsked), w.sent().map { it.recheck })
            assertEquals(emptyList<Set<AnimeId>>(), w.source.calls.toList())

            // A13: the finale of a show marked finished, whose schedule is kept for it.
            val finished = title.copy(airingStatus = AiringStatus.Finished)
            val finale = world(entries = listOf(entry(finished)), now = week(11).minus(day))
            assertEquals(week(11), finale.alarm.at.value)
            finale.clock.now = week(11)
            val report = finale.engine.run(RunTrigger.Alarm)
            assertEquals(listOf(title.id to 12), finale.alerts)
            assertEquals(Recheck.NotAsked, report.recheck)
            assertEquals(emptyList<Set<AnimeId>>(), finale.source.calls.toList())
        }

    @Test
    fun `N7 a posted notification goes when its episode is watched, its title is dropped or its title leaves the list`() =
        runTest {
            val changes =
                listOf(
                    ListWrite.MarkNextWatched(title),
                    ListWrite.Save(title, watching.copy(status = ListStatus.Dropped)),
                    ListWrite.Save(title, watching.copy(status = ListStatus.OnHold)),
                    ListWrite.Remove(title),
                )
            for (change in changes) {
                val w = world()
                w.pass(week(0))
                assertEquals(setOf(title.id), w.showing.keys)

                w.write(change)

                assertEquals("$change", emptyMap<AnimeId, Says>(), w.showing)
                assertEquals("$change", listOf(setOf(title.id)), w.shown.withdrawals.toList())
                assertEquals("$change", setOf(title.id), w.reports.last().withdrawn)
                assertEquals("$change", RunTrigger.Changed, w.reports.last().trigger)
                assertEquals("$change", listOf(title.id to 1), w.alerts)
            }
        }

    @Test
    fun `N7 a notification that reads otherwise now is replaced without an alert, and one dismissed is not shown again`() =
        runTest {
            val w = world()
            w.pass(week(1))
            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
            assertEquals(mapOf(title.id to Says(2, toWatch = 2, marks = 1)), w.showing)

            w.write(ListWrite.MarkNextWatched(title))
            assertEquals(mapOf(title.id to Says(2, toWatch = 1, marks = 2)), w.showing)
            assertEquals(
                Announcement(title, 2, toWatch = 1, marks = 2) to false,
                w.shown.posts
                    .last()
                    .let { it.announcement to it.alert },
            )
            assertEquals(
                "the airing it tells of stays",
                week(1),
                w.shown.posts
                    .last()
                    .notification.airedAt,
            )
            assertEquals(3, w.shown.posts.size)

            // Nothing changed: no run posts the same again.
            w.engine.run(RunTrigger.Periodic)
            assertEquals(3, w.shown.posts.size)

            w.shown.dismiss(title.id)
            w.engine.run(RunTrigger.AppOpened)
            w.write(ListWrite.Save(title, watching.copy(score = 7, watchedEpisodes = 1)))
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(3, w.shown.posts.size)

            w.pass(week(2))
            assertEquals(listOf(title.id to 1, title.id to 2, title.id to 3), w.alerts)
            assertEquals(mapOf(title.id to Says(3, toWatch = 2, marks = 2)), w.showing)
        }

    @Test
    fun `N7 a notification that a cut-off run posted and never recorded goes when its episode is watched, and is told as sent`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            w.process.steps = 0
            w.process.diesAfter = 2
            assertTrue(runCatching { w.engine.run(RunTrigger.AppOpened) }.isFailure)
            assertEquals(setOf(title.id), w.showing.keys)
            assertFalse(
                w
                    .record()
                    .titles
                    .getValue(title.id)
                    .standing,
            )

            w.write(ListWrite.MarkNextWatched(title))
            w.start()
            w.engine.run(RunTrigger.AppOpened)
            runCurrent()

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            // It alerted, so it is told as sent.
            assertEquals(listOf(SentNotification(title, 1, week(0), week(0), week(0), Recheck.NotAsked)), w.sent())
        }

    @Test
    fun `N8 after each change of the list, the airing data or a setting the alarm is the earliest coming notification time, or none`() =
        runTest {
            val w = world()
            assertEquals(week(0), w.alarm.at.value)

            w.write(ListWrite.Save(other, watching))
            assertEquals("a title added that airs later", week(0), w.alarm.at.value)
            assertEquals(
                listOf(Coming(title, 1, week(0), week(0)), Coming(other, 1, week(0).plus(otherAfter), week(0).plus(otherAfter))),
                w.engine.observeComing().first(),
            )

            w.write(ListWrite.Save(title, watching.copy(status = ListStatus.Dropped)))
            assertEquals("the earlier title dropped", week(0).plus(otherAfter), w.alarm.at.value)

            w.refreshed(NotifyCases.schedules.moved(other, 1, day.negated()))
            assertEquals("an episode moved earlier", week(0).plus(otherAfter).minus(day), w.alarm.at.value)

            w.engine.changeSettings { it.copy(delay = NotificationDelay.Hour) }
            assertEquals("the delay alone", week(0).plus(otherAfter).minus(day).plus(hour), w.alarm.at.value)

            w.engine.changeSettings { it.copy(on = false) }
            assertNull("notifications off", w.alarm.at.value)
            assertEquals(emptyList<Coming>(), w.engine.observeComing().first())

            w.engine.changeSettings { it.copy(on = true) }
            assertEquals("on again", week(0).plus(otherAfter).minus(day).plus(hour), w.alarm.at.value)

            w.write(ListWrite.Remove(other))
            assertNull("nothing left to announce", w.alarm.at.value)
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `S9 signed out nothing stays shown, no alarm is left, the record and what was sent are forgotten, and nothing is announced`() =
        runTest {
            val w = world(settings = NotificationSettings(delay = NotificationDelay.HalfHour))
            w.pass(week(0).plus(halfHour))
            assertEquals(setOf(title.id), w.showing.keys)
            assertEquals(week(1).plus(halfHour), w.alarm.at.value)
            assertEquals(1, w.sent().size)

            w.session.value = Session.SignedOut
            runCurrent()

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertNull(w.alarm.at.value)
            assertEquals(StoredNotifications(null, NotificationRecord()), w.inner.read())
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals(emptyList<SentNotification>(), w.engine.observeSent().first())
            assertEquals(emptyList<Coming>(), w.engine.observeComing().first())
            assertEquals(
                "the settings stay",
                NotificationDelay.HalfHour,
                w.engine
                    .observeSettings()
                    .first()
                    .delay,
            )
            assertEquals(RunEnd.SignedOut, w.reports.last().end)
            assertEquals(setOf(title.id), w.reports.last().withdrawn)

            w.clock.now = week(1).plus(halfHour)
            for (trigger in RunTrigger.entries) assertEquals(RunEnd.SignedOut, w.engine.run(trigger).end)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertNull(w.alarm.at.value)
            assertEquals(StoredNotifications(null, NotificationRecord()), w.inner.read())

            // N5: a new sign-in announces nothing that aired before it.
            w.clock.now = week(1).plus(hour)
            w.session.value = b
            assertEquals(Outcome.Success(Unit), w.stores.sync())
            runCurrent()
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(week(2).plus(halfHour), w.alarm.at.value)
            assertEquals(StoredNotifications(b.signIn, w.record()), w.inner.read())
            assertEquals(week(1).plus(hour), w.record().since)
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `S9 a sign-out while a run reads the list stores nothing that run would give over the emptied list`() =
        runTest {
            val w = world()
            w.pass(week(0))
            w.clock.now = week(1)
            w.list.afterRead = { w.session.value = Session.SignedOut }

            val report = w.engine.run(RunTrigger.AppOpened)
            runCurrent()

            assertEquals(RunEnd.SignedOut, report.end)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(StoredNotifications(null, NotificationRecord()), w.inner.read())
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertNull(w.alarm.at.value)
        }

    @Test
    fun `S9 a sign-out while a run posts leaves no notification, no alarm and no record of that run`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            w.shows.beforePost = { w.session.value = Session.SignedOut }

            w.engine.run(RunTrigger.AppOpened)
            runCurrent()

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertNull(w.alarm.at.value)
            assertEquals(StoredNotifications(null, NotificationRecord()), w.inner.read())
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals(RunEnd.SignedOut, w.reports.last().end)
        }

    // A store, an alarm and a notification that an earlier process left for the list of [owner].
    private suspend fun TestScope.left(owner: SignInId): World {
        val w = world(now = week(2).plus(hour), started = false)
        val before = week(0).minus(day.multipliedBy(30))
        w.inner.clear(owner)
        w.inner.write(
            NotificationRecord(
                since = before,
                titles =
                    mapOf(
                        title.id to TitleRecord(Followed(ListStatus.Watching, before), Announced(2, week(1)), standing = true),
                    ),
            ),
            listOf(SentNotification(title, 2, week(1), week(1), week(1), Recheck.NotAsked)),
            Instant.EPOCH,
        )
        w.shown.post(title, Shown(2, toWatch = 2, marks = 1, week(1), week(1)), alert = true)
        w.alarm.set(week(2))
        return w
    }

    @Test
    fun `S9 a record of another sign-in's list is forgotten with what is shown of it, and nothing is announced from it`() =
        runTest {
            val w = left(b.signIn)
            w.start()
            w.engine.run(RunTrigger.AppOpened)
            runCurrent()

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertEquals(a.signIn, w.inner.read().owner)
            assertEquals(week(2).plus(hour), w.record().since)
            assertNull(
                w
                    .record()
                    .titles
                    .getValue(title.id)
                    .announced,
            )
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals(1, w.shown.posts.size)
            assertEquals(week(3), w.alarm.at.value)
        }

    @Test
    fun `S9 a sign-out that its process did not finish is finished by the first run of the next`() =
        runTest {
            val w = left(a.signIn)
            w.session.value = Session.SignedOut
            w.start()

            assertEquals(RunEnd.SignedOut, w.engine.run(RunTrigger.AppOpened).end)

            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertNull(w.alarm.at.value)
            assertEquals(StoredNotifications(null, NotificationRecord()), w.inner.read())
            assertEquals(emptyList<SentNotification>(), w.sent())
        }

    @Test
    fun `a record the store reads as nobody's is forgotten by the next run, which announces nothing twice and what comes later`() =
        runTest {
            val w = world()
            w.pass(week(0).plus(hour))
            assertEquals(listOf(title.id to 1), w.alerts)
            if (!damageRecord()) return@runTest

            w.engine.run(RunTrigger.AppUpdated)
            runCurrent()
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(a.signIn, w.inner.read().owner)
            assertEquals(week(1), w.alarm.at.value)

            w.pass(week(1).plus(hour))
            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
            assertEquals(
                Announced(2, week(1)),
                w
                    .record()
                    .titles
                    .getValue(title.id)
                    .announced,
            )
        }

    @Test
    fun `S6 an expired session changes nothing of it`() =
        runTest {
            val w = world()
            w.pass(week(0))
            val record = w.record()

            w.session.value = Session.Expired(a.signIn)
            runCurrent()
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(record, w.record())
            assertEquals(week(1), w.alarm.at.value)
            assertEquals(1, w.sent().size)

            w.pass(week(1))
            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
            assertEquals(a.signIn, w.inner.read().owner)
        }

    @Test
    fun `a run waits for the stored list to be read, and takes nothing for removed meanwhile`() =
        runTest {
            val w = world()
            w.pass(week(0))
            val record = w.record()
            val reports = w.reports.size

            w.list.beingRead.value = true
            w.clock.now = week(0).plus(hour)
            w.start()
            val run = async { w.engine.run(RunTrigger.AppOpened) }
            advanceTimeBy(10.minutes)
            runCurrent()

            assertFalse(run.isCompleted)
            assertEquals(reports, w.reports.size)
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(record, w.record())

            w.list.beingRead.value = false
            assertEquals(RunEnd.Done, run.await().end)
            runCurrent()
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(record, w.record())
            assertEquals(emptyList<Set<AnimeId>>(), w.shown.withdrawals.toList())
            assertEquals(week(1), w.alarm.at.value)
        }

    @Test
    fun `a run over a list that cannot be read fails and touches nothing, and the next one does its work`() =
        runTest {
            val w = world()
            w.pass(week(0))
            val record = w.record()
            w.list.unreadable.value = true
            w.clock.now = week(1)

            val failed = w.engine.run(RunTrigger.AppOpened)

            assertEquals(RunEnd.Failed, failed.end)
            assertEquals(mapOf(title.id to Says(1, toWatch = 1, marks = 1)), w.showing)
            assertEquals(record, w.record())
            assertEquals(week(1), w.alarm.at.value)
            assertEquals(emptyList<Throwable>(), w.unexpected)

            w.list.unreadable.value = false
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
            assertEquals(listOf(title.id to 1, title.id to 2), w.alerts)
        }

    @Test
    fun `A12 a schedule fetched for a page reaches no run, also once its title is on the list`() =
        runTest {
            val w = world()
            val airs = week(0).plus(otherAfter)
            w.pass(airs.minus(hour))
            assertEquals(Outcome.Success(Unit), w.airing.refresh(other))
            w.source.failure = DataError.ServerError
            w.write(ListWrite.Save(other, watching))
            w.pass(airs.plus(hour))
            w.engine.run(RunTrigger.AppOpened)

            assertEquals(
                "the page knows it",
                AiredCount.Exact(1),
                w.airing
                    .observePageAiring(other)
                    .first()[other.id]
                    ?.aired,
            )
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(
                listOf(title.id),
                w.engine
                    .observeComing()
                    .first()
                    ?.map { it.anime.id },
            )

            // Once it is stored for the list, the episode is announced.
            w.source.failure = null
            w.refreshed(NotifyCases.schedules)
            assertEquals(listOf(title.id to 1, other.id to 1), w.alerts)
        }

    @Test
    fun `the screens are given the settings and their changes, what comes next and what was sent, the latest first`() =
        runTest {
            val w = world(settings = NotificationSettings(delay = NotificationDelay.HalfHour))
            val settings = mutableListOf<NotificationSettings>()
            val coming = mutableListOf<List<Coming>?>()
            val sent = mutableListOf<List<SentNotification>>()
            backgroundScope.launch { w.engine.observeSettings().collect { settings += it } }
            backgroundScope.launch { w.engine.observeComing().collect { coming += it } }
            backgroundScope.launch { w.engine.observeSent().collect { sent += it } }
            runCurrent()
            assertEquals(listOf(NotificationSettings(delay = NotificationDelay.HalfHour)), settings)
            assertEquals(listOf(listOf(Coming(title, 1, week(0), week(0).plus(halfHour)))), coming)
            assertEquals(listOf(emptyList<SentNotification>()), sent)

            // The alarm is lost and the app is opened a quarter of an hour after the notification time.
            w.alarm.gone()
            w.clock.now = week(0).plus(Duration.ofMinutes(45))
            w.engine.run(RunTrigger.AppOpened)
            w.pass(week(1).plus(halfHour))
            val first = SentNotification(title, 1, week(0), week(0).plus(halfHour), week(0).plus(Duration.ofMinutes(45)), Recheck.NotAsked)
            val second = SentNotification(title, 2, week(1), week(1).plus(halfHour), week(1).plus(halfHour), Recheck.Answered)
            assertEquals(listOf(second, first), sent.last())
            assertEquals(listOf(Coming(title, 3, week(2), week(2).plus(halfHour))), coming.last())

            assertEquals(Outcome.Success(Unit), w.engine.changeSettings { it.copy(alsoPlanned = true, delay = NotificationDelay.None) })
            assertEquals("followed at once", listOf(Coming(title, 3, week(2), week(2))), w.engine.observeComing().first())
            assertEquals(week(2), w.alarm.at.value)
            runCurrent()
            assertEquals(NotificationSettings(alsoPlanned = true), settings.last())
            assertEquals(NotificationSettings(alsoPlanned = true), w.inner.observeSettings().first())

            // What was posted longer ago than is kept is not given, also before the store has forgotten it.
            w.clock.now = second.postedAt.plus(day.multipliedBy(30))
            assertEquals(listOf(second), w.engine.observeSent().first())
            w.clock.now = second.postedAt.plus(day.multipliedBy(30)).plusSeconds(1)
            assertEquals(emptyList<SentNotification>(), w.engine.observeSent().first())
            assertEquals(2, w.sent().size)
            assertEquals(emptyList<Throwable>(), w.unexpected)
        }

    @Test
    fun `N5 N7 notifications turned off withdraw what is posted and clear the alarm, and turned on they announce nothing from before`() =
        runTest {
            val w = world()
            w.pass(week(0))
            assertEquals(setOf(title.id), w.showing.keys)

            w.engine.changeSettings { it.copy(on = false) }
            assertEquals(emptyMap<AnimeId, Says>(), w.showing)
            assertNull(w.alarm.at.value)
            w.pass(week(2).plus(hour))
            w.engine.run(RunTrigger.Periodic)
            assertEquals(listOf(title.id to 1), w.alerts)

            w.engine.changeSettings { it.copy(on = true) }
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(week(3), w.alarm.at.value)
            w.pass(week(3))
            assertEquals(listOf(title.id to 1, title.id to 4), w.alerts)
        }

    @Test
    fun `every run is reported with its trigger and what it did, and its caller is given the same`() =
        runTest {
            val w = world()
            assertEquals(listOf(RunReport(RunTrigger.AppOpened, start, start, RunEnd.Done, alarm = week(0))), w.reports.toList())

            w.clock.now = week(0)
            w.alarm.gone()
            val posted = w.engine.run(RunTrigger.Alarm)
            assertEquals(
                RunReport(
                    RunTrigger.Alarm,
                    week(0),
                    week(0),
                    RunEnd.Done,
                    Recheck.Answered,
                    listOf(SentNotification(title, 1, week(0), week(0), week(0), Recheck.Answered)),
                    alarm = week(1),
                ),
                posted,
            )
            w.clock.now = week(0).plus(hour)
            w.write(ListWrite.MarkNextWatched(title))
            val withdrew =
                RunReport(
                    RunTrigger.Changed,
                    week(0).plus(hour),
                    week(0).plus(hour),
                    RunEnd.Done,
                    withdrawn = setOf(title.id),
                    alarm = week(1),
                )
            assertEquals(listOf(posted, withdrew), w.reports.drop(1))

            // A change that a run has already followed starts no other.
            w.engine.changeSettings { it.copy(delay = NotificationDelay.Hour) }
            runCurrent()
            assertEquals(3, w.reports.drop(1).size)
            assertEquals(RunTrigger.Changed, w.reports.last().trigger)
            assertEquals(week(1).plus(hour), w.reports.last().alarm)
        }

    @Test
    fun `a store that fails a write is asked again, and the episode alerts once and is recorded once`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            w.store.writesToFail = 1

            val report = w.engine.run(RunTrigger.Alarm)

            assertEquals(RunEnd.Done, report.end)
            assertEquals("the airing source is asked once", listOf(setOf(title.id)), w.source.calls.toList())
            assertEquals(Recheck.Answered, report.recheck)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.shown.posts.size)
            assertEquals(listOf(1), report.posted.map { it.episode })
            assertEquals(listOf(1), w.sent().map { it.episode })
            assertEquals("the first of the store's streak", 1, w.unexpected.size)
        }

    @Test
    fun `a store that keeps failing fails the run, and the next run records what was posted without a second alert`() =
        runTest {
            val w = world()
            val record = w.record()
            w.clock.now = week(0)
            w.store.failingWrites = true

            val failed = w.engine.run(RunTrigger.AppOpened)

            assertEquals(RunEnd.Failed, failed.end)
            assertEquals(listOf(1), failed.posted.map { it.episode })
            assertEquals("the near alarm stands", week(0).plus(nearAlarm), failed.alarm)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(record, w.record())
            assertEquals(emptyList<SentNotification>(), w.sent())
            assertEquals(Outcome.Failure(DataError.StorageError), w.engine.changeSettings { it.copy(on = false) })
            assertEquals(NotificationSettings(), w.engine.observeSettings().first())

            w.store.failingWrites = false
            w.store.unreadable = true
            assertEquals(RunEnd.Failed, w.engine.run(RunTrigger.AppOpened).end)
            w.store.unreadable = false
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(1, w.shown.posts.size)
            assertEquals(listOf(1), w.sent().map { it.episode })
        }

    @Test
    fun `a notification that cannot be posted is announced by the next run, and an alarm that cannot be set keeps none back`() =
        runTest {
            val w = world()
            val record = w.record()
            w.clock.now = week(0)
            val broken = IllegalStateException("The device refused")
            w.shows.throwing = broken

            assertEquals(RunEnd.Failed, w.engine.run(RunTrigger.AppOpened).end)
            assertEquals(listOf<Throwable>(broken), w.unexpected.toList())
            assertEquals(record, w.record())
            assertEquals(emptyList<Pair<AnimeId, Int>>(), w.alerts)

            w.shows.throwing = null
            w.alarms.throwing = broken
            val report = w.engine.run(RunTrigger.AppOpened)
            assertEquals(RunEnd.Failed, report.end)
            assertTrue(report.alarmFailed)
            assertNull(report.alarm)
            assertEquals(listOf(title.id to 1), w.alerts)
            assertEquals(listOf(1), w.sent().map { it.episode })
            assertEquals(listOf<Throwable>(broken, broken, broken), w.unexpected.toList())

            w.alarms.throwing = null
            assertEquals(RunEnd.Done, w.engine.run(RunTrigger.AppOpened).end)
            assertEquals(week(1), w.alarm.at.value)
            assertEquals(listOf(title.id to 1), w.alerts)
        }

    // The work of a run asked for once the scope is ending is cancelled as it is made, before its caller hears of the end.
    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `a run asked for in the engine's own scope while that scope is ending ends cancelled with it`() =
        runTest {
            val w = world()
            w.clock.now = week(0)
            val process = checkNotNull(w.process.scope)
            val ending = CompletableDeferred<Unit>()
            lateinit var run: Deferred<RunReport>
            var raced = false
            process.launch { awaitCancellation() }.invokeOnCompletion(onCancelling = true) {
                ending.complete(Unit)
                testScheduler.runCurrent()
                raced = run.isCompleted
            }
            run =
                process.async {
                    ending.await()
                    w.engine.run(RunTrigger.Alarm)
                }
            runCurrent()

            process.cancel()
            runCurrent()

            assertTrue("the run ended before the scope's end reached it", raced)
            assertTrue("${run.getCompletionExceptionOrNull()}", run.getCompletionExceptionOrNull() is CancellationException)
            assertEquals(emptyList<Throwable>(), w.unexpected.toList())
        }

    private companion object {
        const val MOST_ALARMS = 100
    }
}
