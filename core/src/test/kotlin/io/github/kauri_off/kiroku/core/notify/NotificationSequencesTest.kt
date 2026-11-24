package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.other
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// N1, N2, N7 and N8 over generated sequences of time passing, triggers, deaths, failing ports, dismissals and a clock set back.
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSequencesTest {
    private val start: Instant = week(0).minus(day)

    private class DeviceClock(
        @Volatile var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    // The process an engine runs in: it dies once it has made the step told.
    private class Process(
        val scope: CoroutineScope,
    ) {
        var steps = 0
        var diesAfter: Int? = null

        fun step() {
            steps++
            if (steps == diesAfter) {
                scope.cancel()
                throw CancellationException("The process died")
            }
        }
    }

    // A device with two titles watched, whose ports die and fail as told; what it keeps outlives each process.
    private inner class Device(
        private val test: TestScope,
    ) {
        val clock = DeviceClock(start)
        val entries = MutableStateFlow(listOf(entry(), entry(other)))
        val reports = CopyOnWriteArrayList<RunReport>()
        val store = InMemoryNotificationStore()
        val alarm = InMemoryNotificationAlarm()
        val shown = InMemoryShownNotifications()

        // Each post with the device's time when it was made.
        val postedAt = CopyOnWriteArrayList<Pair<InMemoryShownNotifications.Post, Instant>>()
        lateinit var process: Process
        lateinit var engine: NotificationEngine
        var writesToFail = 0
        var postThrows = false
        var alarmThrows = false

        private val list =
            object : ListSource {
                override fun observeEntries(): Flow<List<ListEntry>> = entries

                override fun observeEntry(id: AnimeId) = error(UNUSED)

                override fun observeEntriesOf(ids: Set<AnimeId>) = error(UNUSED)

                override fun observeSyncedAt() = error(UNUSED)

                override suspend fun sync(expectedOwner: SignInId?) = error(UNUSED)
            }

        private val airing =
            object : AiringRepository {
                override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = MutableStateFlow(NotifyCases.schedules)

                override suspend fun refresh(titles: Set<AnimeId>): Outcome<Unit> = Outcome.Success(Unit)

                override fun observeAiring() = error(UNUSED)

                override fun observePageAiring(page: Anime) = error(UNUSED)

                override fun observeStoredSchedules() = error(UNUSED)

                override suspend fun refreshStale() = error(UNUSED)

                override suspend fun refresh() = error(UNUSED)

                override suspend fun refreshStale(page: Anime) = error(UNUSED)

                override suspend fun refresh(page: Anime) = error(UNUSED)
            }

        private val failingStore =
            object : NotificationStore by store {
                override suspend fun write(
                    record: NotificationRecord,
                    sent: List<SentNotification>,
                    keptFrom: Instant,
                ) {
                    yield()
                    if (writesToFail > 0) {
                        writesToFail--
                        throw IOException("The write was aborted")
                    }
                    store.write(record, sent, keptFrom)
                    process.step()
                }

                override suspend fun clear(owner: SignInId?) {
                    store.clear(owner)
                    process.step()
                }
            }

        private val failingAlarm =
            object : NotificationAlarm {
                override suspend fun set(at: Instant?) {
                    yield()
                    if (alarmThrows) {
                        alarmThrows = false
                        throw IllegalStateException("The alarm was refused")
                    }
                    alarm.set(at)
                    process.step()
                }
            }

        private val failingShown =
            object : ShownNotifications {
                override suspend fun shown(): Map<AnimeId, Shown> {
                    yield()
                    return shown.shown()
                }

                override suspend fun post(
                    anime: Anime,
                    notification: Shown,
                    alert: Boolean,
                ): Boolean {
                    yield()
                    if (postThrows) {
                        postThrows = false
                        throw IllegalStateException("The post was refused")
                    }
                    val showing = shown.post(anime, notification, alert)
                    postedAt += shown.posts.last() to clock.now
                    process.step()
                    return showing
                }

                override suspend fun withdraw(titles: Set<AnimeId>) {
                    shown.withdraw(titles)
                    process.step()
                }
            }

        val alive: Boolean get() = ::process.isInitialized && process.scope.isActive

        // A new process, the one before it dead.
        fun start() {
            if (::process.isInitialized) process.scope.cancel()
            val background = test.backgroundScope
            process = Process(CoroutineScope(background.coroutineContext + Job(background.coroutineContext[Job])))
            engine =
                NotificationEngine(
                    failingStore,
                    failingAlarm,
                    failingShown,
                    list,
                    airing,
                    MutableStateFlow<Session>(Session.SignedIn(SignInId("a"))),
                    object : Connection {
                        override fun observeOnline(): Flow<Boolean> = MutableStateFlow(true)
                    },
                    clock,
                    process.scope,
                    reports = { reports += it },
                    unexpected = {},
                )
        }

        // A trigger starts the app's process when it is dead.
        suspend fun run(trigger: RunTrigger) {
            if (!alive) start()
            runCatching { engine.run(trigger) }
            test.runCurrent()
        }

        val alerts: List<Pair<AnimeId, Int>>
            get() = shown.posts.filter { it.alert }.map { it.anime.id to it.notification.episode }

        suspend fun sent(): List<Pair<AnimeId, Int>> = store.observeSent().first().map { it.anime.id to it.episode }

        // Time passes to [until]; the alarm fires whenever its time comes.
        suspend fun pass(until: Instant) {
            repeat(MOST_ALARMS) {
                val at = alarm.at.value
                if (at == null || at.isAfter(until)) {
                    clock.now = maxOf(clock.now, until)
                    test.runCurrent()
                    return
                }
                clock.now = maxOf(clock.now, at)
                alarm.gone()
                run(RunTrigger.Alarm)
            }
            error("The alarm keeps firing")
        }
    }

    private fun trigger(random: Random) = RunTrigger.entries[random.nextInt(RunTrigger.entries.size)]

    // The earliest air time after [now], which is the alarm a run leaves when no delay is set.
    private fun nextAirTime(now: Instant): Instant? =
        NotifyCases.schedules.values
            .flatMap { it.episodes }
            .map { it.airsAt }
            .filter { it.isAfter(now) }
            .minOrNull()

    // One sequence; what broke in it, with its last steps. [clockBack] also sets the clock back, and is then not held to N2 and N8.
    private fun sequence(
        seed: Int,
        clockBack: Boolean,
    ): String? {
        var broken: String? = null
        runTest(timeout = 60.seconds) {
            val random = Random(seed)
            val device = Device(this)
            device.run(RunTrigger.AppOpened)
            val log = mutableListOf<String>()
            // N1's one exception: an alert more for each dismissal of a notification not recorded yet.
            val allowed = mutableMapOf<Pair<AnimeId, Int>, Int>()
            val dismissed = mutableSetOf<Pair<AnimeId, Int>>()
            var wentBack = false
            val end = week(11).plus(day)
            var steps = 0
            while (broken == null && device.clock.now.isBefore(end) && steps++ < MOST_STEPS) {
                val reports = device.reports.size
                when (random.nextInt(if (clockBack) 11 else 10)) {
                    0, 1, 2 -> {
                        val by = Duration.ofMinutes(random.nextLong(30, 60 * 60))
                        log += "pass ${by.toHours()}h"
                        device.pass(device.clock.now.plus(by))
                    }
                    3 -> {
                        val trigger = trigger(random)
                        log += "run $trigger"
                        device.run(trigger)
                    }
                    4 -> {
                        val both = listOf(trigger(random), trigger(random))
                        log += "runs $both"
                        if (!device.alive) device.start()
                        runCatching { supervisorScope { both.map { async { device.engine.run(it) } }.awaitAll() } }
                        runCurrent()
                    }
                    5 -> {
                        val showing = device.shown.notifications.value
                        if (showing.isNotEmpty()) {
                            val post = showing.values.elementAt(random.nextInt(showing.size))
                            val key = post.anime.id to post.notification.episode
                            if (key in device.sent()) dismissed += key else allowed[key] = (allowed[key] ?: 0) + 1
                            log += "dismiss $key"
                            device.shown.dismiss(post.anime.id)
                        }
                    }
                    6 -> {
                        if (!device.alive) device.start()
                        val after = random.nextInt(1, 5)
                        device.process.diesAfter = device.process.steps + after
                        log += "die after $after steps"
                    }
                    7 -> {
                        device.writesToFail = random.nextInt(1, 5)
                        log += "${device.writesToFail} writes fail"
                    }
                    8 -> {
                        // A refused alarm with a death can lose an episode until another trigger comes, so N2 is held without it.
                        if (clockBack && random.nextBoolean()) {
                            device.alarmThrows = true
                            log += "the alarm is refused"
                        } else {
                            device.postThrows = true
                            log += "a post is refused"
                        }
                    }
                    9 -> {
                        // The person watches the next episode of a title, one already announced.
                        val announced = device.alerts.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
                        val all = device.entries.value
                        val index = random.nextInt(all.size)
                        val entry = all[index]
                        val watched = entry.values.watchedEpisodes
                        if (watched < (announced[entry.anime.id] ?: 0)) {
                            log += "watch ${entry.anime.id} to ${watched + 1}"
                            val values = entry.values.copy(watchedEpisodes = watched + 1)
                            val changed = entry.copy(values = values, updatedAt = device.clock.now)
                            device.entries.value = all.toMutableList().also { it[index] = changed }
                            runCurrent()
                        }
                    }
                    else -> {
                        val by = Duration.ofHours(random.nextLong(1, 48))
                        log += "clock back ${by.toHours()}h"
                        wentBack = true
                        device.clock.now = device.clock.now.minus(by)
                        runCurrent()
                    }
                }
                val alerts = device.alerts.groupingBy { it }.eachCount()
                val sent = device.sent().groupingBy { it }.eachCount()
                val showing =
                    device.shown.notifications.value.values
                        .map { it.anime.id to it.notification.episode }
                val again = showing.filter { it in dismissed }
                // N8 is held where a run of this step ended as it should and its process still lives.
                val ended = device.reports.size > reports && device.reports.last().end == RunEnd.Done
                val alarm = device.alarm.at.value
                broken =
                    when {
                        alerts.any { (key, times) -> times > 1 + (allowed[key] ?: 0) } -> "N1 alerted twice: $alerts, allowed $allowed"
                        sent.any { it.value > 1 } -> "sent twice: $sent"
                        again.isNotEmpty() -> "N7 dismissed after it was recorded and shown again: $again"
                        !wentBack && ended && device.alive && alarm != nextAirTime(device.clock.now) ->
                            "N8 the alarm is at $alarm at ${device.clock.now}"
                        else -> null
                    }?.let { "seed $seed: $it after ${log.takeLast(STEPS_TOLD)}" }
            }
            if (broken != null || wentBack) return@runTest
            // N2: each episode aired since the first run is covered within three days by an alert of it or of a higher one.
            device.pass(device.clock.now.plus(day))
            val since =
                device.store
                    .read()
                    .record.since ?: return@runTest
            for ((id, schedule) in NotifyCases.schedules) {
                for (episode in schedule.episodes) {
                    if (!episode.airsAt.isAfter(since) || episode.airsAt.isAfter(device.clock.now.minus(day))) continue
                    val limit = episode.airsAt.plus(Duration.ofDays(3))
                    val covered =
                        device.postedAt.any { (post, at) ->
                            post.alert && post.anime.id == id && post.notification.episode >= episode.number && !at.isAfter(limit)
                        }
                    if (!covered) {
                        broken = "seed $seed: N2 $id episode ${episode.number} of ${episode.airsAt} was not alerted in three days: " +
                            log.filter { !it.startsWith("pass") }.takeLast(STEPS_TOLD)
                        return@runTest
                    }
                }
            }
        }
        return broken
    }

    @Test
    fun `N1 N2 N7 N8 over sequences of deaths, failing ports, triggers at once and dismissals, an episode alerts once and none is lost`() {
        assertEquals(emptyList<String>(), (0 until SEQUENCES).mapNotNull { sequence(it, clockBack = false) })
    }

    @Test
    fun `N1 N7 with the clock also set back and the alarm refused, an episode alerts once and is told as sent once`() {
        val seeds = CLOCK_BACK_FROM until CLOCK_BACK_FROM + CLOCK_BACK_SEQUENCES
        assertEquals(emptyList<String>(), seeds.mapNotNull { sequence(it, clockBack = true) })
    }

    private companion object {
        const val SEQUENCES = 40
        const val CLOCK_BACK_FROM = 100
        const val CLOCK_BACK_SEQUENCES = 30
        const val MOST_STEPS = 80
        const val MOST_ALARMS = 400
        const val STEPS_TOLD = 12
        const val UNUSED = "Not used by the engine"
    }
}
