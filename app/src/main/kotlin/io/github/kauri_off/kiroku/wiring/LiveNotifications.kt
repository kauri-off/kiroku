package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.NotificationEngine
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.NotificationsRepository
import io.github.kauri_off.kiroku.data.db.RoomNotificationStore
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.notify.SystemAlarm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

// The record and the settings of a live source's notifications: its own database's, and the choices in its one preferences file.
internal fun liveNotificationStore(
    parts: LiveParts,
    preferences: StoredPreferences,
): NotificationStore = RoomNotificationStore(parts.database, preferences, Dispatchers.IO, parts.unexpected)

// A live source's notifications: the engine over its record, the system's alarm and the device's own, built at its first call.
internal class LiveNotifications(
    private val parts: LiveParts,
    session: Flow<Session>,
    list: () -> ListSource,
    airing: () -> AiringRepository,
    connection: () -> Connection,
    shown: () -> EpisodeNotifications,
    store: () -> NotificationStore,
    // S7: renews the tokens that are due, by MAL's way in for work without a screen.
    private val renew: suspend () -> Unit,
) : NotificationsRepository,
    NotificationRuns {
    // A run has ended in this process: before one, what is stored may be of a sign-out its process did not finish (S9).
    @Volatile
    private var ran = false

    private val built =
        lazy {
            NotificationEngine(
                store(),
                SystemAlarm(parts.context),
                shown(),
                list(),
                airing(),
                session,
                connection(),
                parts.clock,
                parts.scope,
                reports = { ran = true },
                unexpected = parts.unexpected,
            )
        }

    private suspend fun engine(): NotificationEngine = built.onFirstUse(parts.scope)

    // S7: only the opening renews, beside its run. A broadcast's run is let go by its hold, and a renewal cut there loses the session.
    override suspend fun run(trigger: RunTrigger): RunReport =
        coroutineScope {
            val renewal = if (trigger == RunTrigger.AppOpened) launch { reported { renew() } } else null
            try {
                engine().run(trigger)
            } catch (e: Exception) {
                // An engine that cannot be built or run does not take the renewal with it.
                renewal?.join()
                throw e
            }
        }

    override fun observeSettings(): Flow<NotificationSettings> = flow { emitAll(engine().observeSettings()) }

    override suspend fun changeSettings(change: (NotificationSettings) -> NotificationSettings): Outcome<Unit> =
        engine().changeSettings(change)

    override fun observeComing(): Flow<List<Coming>?> = flow { emitAll(engine().observeComing()) }

    // Nothing reads what was sent before a run of this process has ended, so none of it is another sign-in's.
    override fun observeSent(): Flow<List<SentNotification>> =
        flow {
            val engine = engine()
            if (!ran) engine.run(RunTrigger.AppOpened)
            emitAll(engine.observeSent())
        }

    // S9, after the session is signed out: what is shown, the alarm and the record go; the next start makes a run that failed.
    suspend fun forget() = reported { engine().run(RunTrigger.Changed) }

    private suspend fun reported(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            parts.unexpected(e)
        }
    }
}
