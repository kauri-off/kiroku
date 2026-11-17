package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.NotificationRecord
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.notify.StoredNotifications
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

// The record and what was posted in the database, mapped on [dispatcher], and the settings in [preferences]; [unexpected] hears of a row left out as unreadable.
class RoomNotificationStore(
    private val database: KirokuDatabase,
    private val preferences: StoredPreferences,
    private val dispatcher: CoroutineDispatcher,
    private val unexpected: (Throwable) -> Unit,
) : NotificationStore {
    private val dao = database.notifications()

    override fun observeSettings(): Flow<NotificationSettings> = preferences.observeNotificationSettings()

    override suspend fun writeSettings(settings: NotificationSettings) = preferences.writeNotificationSettings(settings)

    override suspend fun read(): StoredNotifications =
        withContext(dispatcher) {
            val (state, titles) = database.transaction { dao.state() to dao.titles() }
            val readTitles = titles.mapNotNull { readable(unexpected) { it.toRecord() } }.toMap()
            // A state the record refuses is nobody's record, which the engine's next run forgets and starts again from (N5).
            val record =
                readable(unexpected) {
                    NotificationRecord(state?.since?.let(::instantOf), state?.plannedSince?.let(::instantOf), readTitles)
                }
            if (record == null) StoredNotifications(null, NotificationRecord()) else StoredNotifications(state?.signIn, record)
        }

    override fun observeSent(): Flow<List<SentNotification>> =
        dao
            .observeSent()
            .distinctUntilChanged()
            .map { rows -> rows.mapNotNull { readable(unexpected) { it.toSent() } } }
            .flowOn(dispatcher)

    override suspend fun write(
        record: NotificationRecord,
        sent: List<SentNotification>,
        keptFrom: Instant,
    ) {
        val titles = record.titles.map { (id, title) -> title.toRow(id) }
        val posted = sent.map { it.toRow() }
        withContext(dispatcher) {
            database.transaction {
                val owner = dao.state()?.owner
                dao.setState(
                    NotificationStateRow(NotificationStateRow.ID, owner, record.since?.toColumn(), record.plannedSince?.toColumn()),
                )
                dao.deleteTitles()
                dao.insertTitles(titles)
                dao.insertSent(posted)
                dao.deleteSentBefore(keptFrom.toColumn())
            }
        }
    }

    override suspend fun clear(owner: SignInId?) {
        withContext(dispatcher) {
            database.transaction {
                dao.deleteTitles()
                dao.deleteSent()
                dao.setState(NotificationStateRow(NotificationStateRow.ID, owner?.value, since = null, plannedSince = null))
            }
        }
    }
}
