package io.github.kauri_off.kiroku.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class NotificationDao {
    @Query("SELECT * FROM notification_state WHERE id = ${NotificationStateRow.ID}")
    abstract suspend fun state(): NotificationStateRow?

    @Upsert
    abstract suspend fun setState(row: NotificationStateRow)

    @Query("SELECT * FROM notification_title")
    abstract suspend fun titles(): List<NotificationTitleRow>

    @Query("DELETE FROM notification_title")
    abstract suspend fun deleteTitles()

    @Insert
    abstract suspend fun insertTitles(rows: List<NotificationTitleRow>)

    // An entry whose title row is gone is left out: only between the list's clearing at a sign-out and the record's.
    @Query(
        "SELECT t.*, s.episode, s.aired_at, s.notify_at, s.posted_at, s.recheck, s.shown FROM notification_sent s " +
            "JOIN title t ON t.id = s.title_id ORDER BY s.id",
    )
    abstract fun observeSent(): Flow<List<SentWithTitle>>

    @Insert
    abstract suspend fun insertSent(rows: List<SentRow>)

    @Query("DELETE FROM notification_sent WHERE posted_at < :before")
    abstract suspend fun deleteSentBefore(before: Long)

    @Query("DELETE FROM notification_sent")
    abstract suspend fun deleteSent()
}
