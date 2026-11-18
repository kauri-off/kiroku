package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.error.StoreCalls
import io.github.kauri_off.kiroku.core.error.StoreFailure
import io.github.kauri_off.kiroku.core.error.endedCall
import io.github.kauri_off.kiroku.core.error.retriedOverStore
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.NotificationsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// Notifications over a store, the alarm and what the device shows: the one run that carries out what is notifiable (section 5).
class NotificationEngine(
    store: NotificationStore,
    private val alarm: NotificationAlarm,
    private val shown: ShownNotifications,
    // Where the stored list is read; nothing here syncs or writes it.
    private val list: ListSource,
    // Read for the schedules a notification may read, and asked by the alarm's run alone.
    private val airing: AiringRepository,
    // Replays the session to each collector; before it gives one, nothing runs.
    private val sessions: Flow<Session>,
    private val connection: Connection,
    private val clock: Clock,
    private val scope: CoroutineScope,
    // N3: how long the alarm's run waits for the airing source.
    private val recheckWithin: Duration = RECHECK_WITHIN,
    // N2: how soon the alarm comes again while a due episode is not recorded.
    private val retryAfter: Duration = RETRY_AFTER,
    // Hears of every run as it ends.
    private val reports: (RunReport) -> Unit = {},
    // Hears of what nobody expected: a port's and the engine's own exceptions, and the first of a store's streak.
    private val unexpected: (Throwable) -> Unit,
) : NotificationsRepository,
    NotificationRuns {
    // What a run reads: the stored state of the list of one sign-in.
    private class Read(
        val owner: SignInId,
        val record: NotificationRecord,
        val settings: NotificationSettings,
        val entries: List<ListEntry>,
        val schedules: Map<AnimeId, AiringSchedule>,
    )

    // What a run has done so far.
    private class Did(
        var recheck: Recheck?,
    ) {
        val posted = mutableListOf<SentNotification>()
        val withdrawn = mutableSetOf<AnimeId>()
        var alarm: Instant? = null
        var alarmFailed = false

        // An episode is known to be due, so while it is not recorded the near alarm stands.
        var due = false
    }

    private class OwnerChanged : Exception()

    // What the last run that ended read, by which a change is told from what a run has already followed.
    private data class Seen(
        val owner: SignInId?,
        val entries: List<ListEntry>,
        val schedules: Map<AnimeId, AiringSchedule>,
        val settings: NotificationSettings?,
    )

    private val stores = StoreCalls(scope, unexpected)

    private val local =
        object : NotificationStore {
            override fun observeSettings() = stores.observing(store.observeSettings())

            override suspend fun writeSettings(settings: NotificationSettings) = stores.writing { store.writeSettings(settings) }

            override suspend fun read() = stores.reading { store.read() }

            override fun observeSent() = stores.observing(store.observeSent())

            override suspend fun write(
                record: NotificationRecord,
                sent: List<SentNotification>,
                keptFrom: Instant,
            ) = stores.writing { store.write(record, sent, keptFrom) }

            override suspend fun clear(owner: SignInId?) = stores.writing { store.clear(owner) }
        }

    // N1: held for the whole of a run, so no two overlap.
    private val mutex = Mutex()

    // Held while a setting is read, changed and stored; never while a run is waited for.
    private val settingsLock = Mutex()

    // N3: a run has ended. Until one that was asked for has, none starts by itself: the alarm's run is the first of its process.
    @Volatile
    private var started = false

    @Volatile
    private var seen: Seen? = null

    // The runs in a row that failed with no episode known to be due, as when the store or the list cannot be read.
    private var failures = 0

    // The store was cleared for nobody in this process, and nobody has signed in since.
    private var forgotten = false

    // What comes next as the last run found it; none before the first.
    private val coming = MutableStateFlow<List<Coming>?>(null)

    private val follows = Any()
    private var following: Job? = null

    init {
        check(scope.isActive) { STOPPED }
    }

    override suspend fun run(trigger: RunTrigger): RunReport = inScope { mutex.withLock { attempt(trigger) } }

    override fun observeSettings(): Flow<NotificationSettings> =
        flow {
            follow()
            emitAll(local.observeSettings().retriedOverStore())
        }.distinctUntilChanged()

    override suspend fun changeSettings(change: (NotificationSettings) -> NotificationSettings): Outcome<Unit> =
        inScope {
            val changed =
                try {
                    settingsLock.withLock {
                        val before = local.observeSettings().first()
                        change(before).takeIf { it != before }?.also { local.writeSettings(it) }
                    }
                } catch (_: StoreFailure) {
                    return@inScope Outcome.Failure(DataError.StorageError)
                }
            // A run that has read the new settings meanwhile has followed them.
            if (changed != null) mutex.withLock { if (seen?.settings != changed) attempt(RunTrigger.Changed) }
            Outcome.Success(Unit)
        }

    override fun observeComing(): Flow<List<Coming>?> =
        flow {
            follow()
            emitAll(coming)
        }

    override fun observeSent(): Flow<List<SentNotification>> =
        flow {
            follow()
            emitAll(
                local.observeSent().retriedOverStore().map { sent ->
                    val keptFrom = clock.instant() - sentKeptFor.toJavaDuration()
                    sent.filter { !it.postedAt.isBefore(keptFrom) }.asReversed()
                },
            )
        }.distinctUntilChanged()

    // The work goes on in the engine's scope when its caller is cancelled, and what it throws is the caller's alone.
    private suspend fun <T> inScope(work: suspend () -> T): T {
        val result =
            scope.async {
                try {
                    Result.success(work()).also { follow() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        try {
            return result.await().getOrThrow()
        } catch (e: CancellationException) {
            scope.endedCall(IllegalStateException(STOPPED, e))
        }
    }

    // N8: while the process lives, a change of the session, the list, the airing data or a setting is followed by a run.
    private fun follow() {
        synchronized(follows) {
            if (!started || following?.isActive == true) return
            following =
                scope.launch {
                    while (true) {
                        try {
                            combine(
                                sessions,
                                list.observeEntries(),
                                airing.observeSchedules(),
                                local.observeSettings().retriedOverStore(),
                            ) { session, entries, schedules, settings -> seen(session.owner, entries, schedules, settings) }
                                .conflate()
                                .collect { now -> if (now != seen) mutex.withLock { if (now != seen) attempt(RunTrigger.Changed) } }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: DataException) {
                            // A list or a store that cannot be read once is read again, so later changes are still followed.
                        } catch (e: Exception) {
                            unexpected(e)
                            return@launch
                        }
                        delay(FOLLOWED_AGAIN_AFTER)
                    }
                }
        }
    }

    // Signed out there is no list, whatever is read meanwhile.
    private fun seen(
        owner: SignInId?,
        entries: List<ListEntry>,
        schedules: Map<AnimeId, AiringSchedule>,
        settings: NotificationSettings,
    ) = if (owner == null) Seen(null, emptyList(), emptyMap(), null) else Seen(owner, entries, schedules, settings)

    // One run, reported. A store that fails is asked a few times in a row; the next run does what this one could not.
    private suspend fun attempt(trigger: RunTrigger): RunReport {
        val began = clock.instant()
        var did = Did(null)
        var end = RunEnd.Failed
        try {
            for (attempt in 0 until StoreCalls.ATTEMPTS) {
                if (attempt > 0) delay(StoreCalls.RETRY)
                // The airing source is asked once, however often the store fails.
                did = Did(did.recheck)
                try {
                    end = carryOut(trigger, did)
                    break
                } catch (_: StoreFailure) {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: DataException) {
            // The list or the airing data cannot be read: nothing is known to be removed, so nothing is touched.
        } catch (e: Exception) {
            unexpected(e)
        }
        if (end == RunEnd.Failed && !did.due) {
            // With nothing known to be due, the alarm's next try waits longer after each failure in a row.
            failures++
            if (trigger == RunTrigger.Alarm) setAlarm(clock.instant() + backOff().toJavaDuration(), did)
        } else {
            failures = 0
        }
        val report =
            RunReport(
                trigger,
                began,
                clock.instant(),
                end,
                did.recheck ?: Recheck.NotAsked,
                did.posted.toList(),
                did.withdrawn.toSet(),
                did.alarm,
                did.alarmFailed,
            )
        try {
            reports(report)
        } catch (e: Exception) {
            unexpected(e)
        }
        started = true
        return report
    }

    // A run starts again from its reading when the session's owner changes under it (S9).
    private suspend fun carryOut(
        trigger: RunTrigger,
        did: Did,
    ): RunEnd {
        while (true) {
            try {
                return carryOutOnce(trigger, did)
            } catch (_: OwnerChanged) {
                // What this run posted for the owner that left goes with it, also where the device does not list it yet.
                withdraw(did.posted.mapTo(mutableSetOf()) { it.anime.id }, did)
                did.posted.clear()
            }
        }
    }

    private suspend fun carryOutOnce(
        trigger: RunTrigger,
        did: Did,
    ): RunEnd {
        // N2: the alarm that started this run is spent, so another stands before anything else is done.
        if (trigger == RunTrigger.Alarm) setAlarm(near(null), did)
        var read = read(did) ?: return forget(did)
        var result = notificationsAt(read.entries, read.schedules, read.settings, read.record, clock.instant())
        if (trigger == RunTrigger.Alarm && did.recheck == null) {
            // N3: the due titles the airing source is still asked about, and no other.
            val due = result.due.mapTo(mutableSetOf()) { it.announcement.anime.id }
            val asked = read.entries.filter { it.tracked && it.anime.id in due }.mapTo(mutableSetOf()) { it.anime.id }
            if (asked.isNotEmpty()) {
                did.recheck = recheck(asked)
                // The same record over what is stored now: a postponed episode is no longer due and is planned again.
                read = read(did) ?: return forget(did)
                result = notificationsAt(read.entries, read.schedules, read.settings, read.record, clock.instant())
            }
        }
        did.due = result.due.isNotEmpty()
        val posted = shown.shown()
        val kept = mutableSetOf<AnimeId>()
        // N2: while a due episode is not recorded a near alarm stands, so a run cut off or failed here is made again soon.
        if (result.due.isNotEmpty()) {
            mine(read.owner)
            setAlarm(near(result.nextAt), did)
        }
        for (due in result.due) {
            val announcement = due.announcement
            val notification = Shown(announcement.episode, announcement.toWatch, announcement.marks, due.airedAt, due.notifyAt)
            val was = posted[announcement.anime.id]
            kept += announcement.anime.id
            mine(read.owner)
            // N1: a run cut off before it recorded finds its notification shown, and does not alert for that airing again.
            val alert = was?.episode != notification.episode || was.airedAt != notification.airedAt
            val showing = was == notification || shown.post(announcement.anime, notification, alert)
            // Recorded as sent also when the device shows nothing, so that a later grant announces no old episode.
            did.posted +=
                SentNotification(
                    announcement.anime,
                    announcement.episode,
                    due.airedAt,
                    due.notifyAt,
                    clock.instant(),
                    did.recheck ?: Recheck.NotAsked,
                    showing,
                )
        }
        for (announcement in result.standing) {
            // N7: only the notification of the episode that stands is its own; one the person dismissed is not shown again.
            val was = posted[announcement.anime.id]?.takeIf { it.episode == announcement.episode } ?: continue
            kept += announcement.anime.id
            val notification = was.copy(toWatch = announcement.toWatch, marks = announcement.marks)
            if (was != notification) {
                mine(read.owner)
                shown.post(announcement.anime, notification, alert = false)
            }
        }
        // N7: nothing else stays shown. What a cut-off run posted and never recorded alerted, so it is told as sent first.
        val others = posted - kept
        if (others.isNotEmpty()) {
            val now = clock.instant()
            val unrecorded = unrecorded(others, now)
            // The same airing still to come, as when the clock went back or the delay grew: the run at its time records it.
            val left =
                unrecorded
                    .filter { (id, was) ->
                        result.coming.any { it.anime.id == id && it.episode == was.episode && it.airsAt == was.airedAt }
                    }.keys
            for ((id, was) in unrecorded - left) {
                val anime = read.entries.firstOrNull { it.anime.id == id }?.anime ?: continue
                did.posted += SentNotification(anime, was.episode, was.airedAt, was.notifyAt, now, Recheck.NotAsked)
            }
            mine(read.owner)
            withdraw(others.keys - left, did)
        }
        mine(read.owner)
        // N1: recorded only now, so a run cut off before this announces again rather than loses its episode.
        if (result.record != read.record || did.posted.isNotEmpty()) {
            local.write(result.record, did.posted.toList(), clock.instant() - sentKeptFor.toJavaDuration())
        }
        // N8: the earliest coming time, in place of the near alarm.
        setAlarm(result.nextAt, did)
        coming.value = result.coming
        seen = seen(read.owner, read.entries, read.schedules, read.settings)
        return if (did.alarmFailed) RunEnd.Failed else RunEnd.Done
    }

    // The notifications that no sent entry tells of, among those recent enough to have one.
    private suspend fun unrecorded(
        notifications: Map<AnimeId, Shown>,
        now: Instant,
    ): Map<AnimeId, Shown> {
        val keptFrom = now - sentKeptFor.toJavaDuration()
        val sent = local.observeSent().first()
        return notifications.filter { (id, was) ->
            !was.notifyAt.isBefore(keptFrom) && sent.none { it.anime.id == id && it.episode == was.episode && it.airedAt == was.airedAt }
        }
    }

    // How long the alarm waits after this failure: twice as long after each in a row, up to a bound.
    private fun backOff(): Duration {
        var wait = retryAfter
        repeat(minOf(failures - 1, MOST_DOUBLINGS)) { wait = minOf(wait * 2, BACK_OFF_BOUND) }
        return wait
    }

    // N2: no later than a short while from now.
    private fun near(next: Instant?): Instant {
        val soon = clock.instant() + retryAfter.toJavaDuration()
        return if (next != null && next.isBefore(soon)) next else soon
    }

    // An alarm that cannot be set keeps no announcement back; the run ends as failed unless a later one is set.
    private suspend fun setAlarm(
        at: Instant?,
        did: Did,
    ) {
        try {
            alarm.set(at)
            did.alarm = at
            did.alarmFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
            did.alarmFailed = true
        }
    }

    // S9: nothing is posted, withdrawn or recorded for an owner the session has left.
    private suspend fun mine(owner: SignInId) {
        if (sessions.first().owner != owner) throw OwnerChanged()
    }

    // The stored state of the session's list, once that list has been read; null when nobody is signed in.
    private suspend fun read(did: Did): Read? {
        while (true) {
            val owner = sessions.first().owner ?: return null
            var stored = local.read()
            if (stored.owner != owner) {
                // S9: what is stored and shown is of another sign-in's list, or of a sign-out its process did not finish.
                withdraw(shown.shown().keys, did)
                local.clear(owner)
                coming.value = null
                stored = StoredNotifications(owner, NotificationRecord())
            }
            forgotten = false
            val settings = local.observeSettings().first()
            // The list as stored, never a value given in its place while it is read: a title missing from it is a title removed.
            val entries = list.observeEntries().first()
            val schedules = airing.observeSchedules().first()
            // A list read across a change of the session may be the emptied one, which no rule is applied to.
            if (sessions.first().owner == owner) return Read(owner, stored.record, settings, entries, schedules)
        }
    }

    // S9: signed out nothing is shown, no alarm is left and nothing is remembered.
    private suspend fun forget(did: Did): RunEnd {
        withdraw(shown.shown().keys, did)
        alarm.set(null)
        did.alarm = null
        did.alarmFailed = false
        if (!forgotten) {
            local.clear(null)
            forgotten = true
        }
        coming.value = emptyList()
        seen = Seen(null, emptyList(), emptyMap(), null)
        return RunEnd.SignedOut
    }

    private suspend fun withdraw(
        titles: Set<AnimeId>,
        did: Did,
    ) {
        if (titles.isEmpty()) return
        shown.withdraw(titles)
        did.withdrawn += titles
    }

    // N3: an answer that fails or is late leaves the stored data in use.
    private suspend fun recheck(titles: Set<AnimeId>): Recheck =
        try {
            if (!connection.observeOnline().first()) {
                Recheck.Offline
            } else {
                when (withTimeoutOrNull(recheckWithin) { airing.refresh(titles) }) {
                    null -> Recheck.Late
                    is Outcome.Success -> Recheck.Answered
                    is Outcome.Failure -> Recheck.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
            Recheck.Failed
        }

    companion object {
        val RECHECK_WITHIN: Duration = 5.seconds
        val RETRY_AFTER: Duration = 15.minutes
        val BACK_OFF_BOUND: Duration = 6.hours
        private const val MOST_DOUBLINGS = 16
        private val FOLLOWED_AGAIN_AFTER: Duration = 1.minutes

        private const val STOPPED = "The notification engine's scope has ended"
    }
}
