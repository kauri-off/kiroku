package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.error.StoreCalls
import io.github.kauri_off.kiroku.core.error.StoreFailure
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
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
    }

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

    // N1: held for the whole of a run and of a change of the settings, so no two overlap.
    private val mutex = Mutex()

    @Volatile
    private var seen: Seen? = null

    // The store was cleared for nobody in this process, and nobody has signed in since.
    private var forgotten = false

    // What comes next as the last run found it; none before the first.
    private val coming = MutableStateFlow<List<Coming>?>(null)

    private val follows = Any()
    private var following: Job? = null

    init {
        check(scope.isActive) { STOPPED }
        follow()
    }

    override suspend fun run(trigger: RunTrigger): RunReport = inScope { mutex.withLock { attempt(trigger) } }

    override fun observeSettings(): Flow<NotificationSettings> =
        flow {
            follow()
            emitAll(local.observeSettings().retriedOverStore())
        }.distinctUntilChanged()

    override suspend fun changeSettings(change: (NotificationSettings) -> NotificationSettings): Outcome<Unit> =
        inScope {
            mutex.withLock {
                try {
                    val before = local.observeSettings().first()
                    val after = change(before)
                    if (after == before) return@withLock Outcome.Success(Unit)
                    local.writeSettings(after)
                } catch (_: StoreFailure) {
                    return@withLock Outcome.Failure(DataError.StorageError)
                }
                attempt(RunTrigger.Changed)
                Outcome.Success(Unit)
            }
        }

    override fun observeComing(): Flow<List<Coming>> =
        flow {
            follow()
            emitAll(coming.filterNotNull())
        }

    override fun observeSent(): Flow<List<SentNotification>> =
        flow {
            follow()
            emitAll(local.observeSent().retriedOverStore().map { it.asReversed() })
        }.distinctUntilChanged()

    // The work goes on in the engine's scope when its caller is cancelled, and what it throws is the caller's alone.
    private suspend fun <T> inScope(work: suspend () -> T): T {
        follow()
        val result =
            scope.async {
                try {
                    Result.success(work())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        try {
            return result.await().getOrThrow()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    // N8: while the process lives, a change of the session, the list, the airing data or a setting is followed by a run.
    private fun follow() {
        synchronized(follows) {
            if (following?.isActive == true) return
            following =
                scope.launch {
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
                        // A list or a store that cannot be read is followed again at the engine's next use.
                    } catch (e: Exception) {
                        unexpected(e)
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
            // The list cannot be read: nothing is known to be removed from it, so nothing is touched.
        } catch (e: Exception) {
            unexpected(e)
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
            )
        try {
            reports(report)
        } catch (e: Exception) {
            unexpected(e)
        }
        return report
    }

    private suspend fun carryOut(
        trigger: RunTrigger,
        did: Did,
    ): RunEnd {
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
        val posted = shown.shown()
        // N8, before anything is posted: a run cut off later has left the alarm at what comes next.
        try {
            alarm.set(result.nextAt)
            did.alarm = result.nextAt
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An alarm that cannot be set keeps no announcement back.
            unexpected(e)
            did.alarmFailed = true
        }
        for (due in result.due) {
            val announcement = due.announcement
            val was = posted[announcement.anime.id]
            // N1: a run cut off before it recorded finds its notification shown, and does not alert for the episode again.
            if (was != announcement.shown) shown.post(announcement, alert = was?.episode != announcement.episode)
            did.posted +=
                SentNotification(
                    announcement.anime,
                    announcement.episode,
                    due.airedAt,
                    due.notifyAt,
                    clock.instant(),
                    did.recheck ?: Recheck.NotAsked,
                )
        }
        for (announcement in result.standing) {
            // One the person dismissed is not shown again; one that reads otherwise now is replaced in silence.
            val was = posted[announcement.anime.id] ?: continue
            if (was != announcement.shown) shown.post(announcement, alert = false)
        }
        // N7: nothing else stays shown, one that a cut-off run posted and never recorded included.
        val kept = (result.due.map { it.announcement } + result.standing).mapTo(mutableSetOf()) { it.anime.id }
        withdraw(posted.keys - kept, did)
        // N1: recorded only now, so a run cut off before this announces again rather than loses its episode.
        if (result.record != read.record || did.posted.isNotEmpty()) {
            local.write(result.record, did.posted.toList(), clock.instant() - sentKeptFor.toJavaDuration())
        }
        coming.value = result.coming
        seen = seen(read.owner, read.entries, read.schedules, read.settings)
        return if (did.alarmFailed) RunEnd.Failed else RunEnd.Done
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

        private const val STOPPED = "The notification engine's scope has ended"
    }
}
