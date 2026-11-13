package io.github.kauri_off.kiroku.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class ListDao {
    // Ordered by the title, so no write moves an entry.
    @Query("$LISTED ORDER BY e.title_id")
    abstract suspend fun listed(): List<ListedRow>

    @Query("$LISTED AND e.title_id = :id")
    abstract fun observeListed(id: Long): Flow<ListedRow?>

    @Transaction
    @Query("SELECT * FROM title WHERE id = :id")
    abstract suspend fun title(id: Long): TitleWithEntry?

    @Query("SELECT * FROM list_entry ORDER BY title_id")
    abstract suspend fun entries(): List<ListEntryRow>

    @Query("SELECT * FROM list_entry WHERE title_id IN (:ids)")
    protected abstract suspend fun entries(ids: List<Long>): List<ListEntryRow>

    @Query("SELECT id FROM title WHERE id IN (:ids)")
    protected abstract suspend fun knownTitles(ids: List<Long>): List<Long>

    @Insert
    protected abstract suspend fun insert(rows: List<ListEntryRow>)

    @Update
    protected abstract suspend fun update(rows: List<ListEntryRow>)

    @Query("DELETE FROM list_entry WHERE title_id IN (:ids)")
    protected abstract suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM list_entry")
    abstract suspend fun deleteAll()

    // Each entry in place, the absent ones deleted; one that is stored as given, or whose title is unknown, is not written.
    @Transaction
    open suspend fun put(entries: Map<Long, ListEntryRow?>) {
        for (ids in entries.keys.chunked(MAX_VARIABLES)) {
            val stored = entries(ids).associateBy { it.titleId }
            val titles = knownTitles(ids).toSet()
            val given = ids.mapNotNull { entries[it] }.filter { it.titleId in titles }
            delete(ids.filter { it in stored && entries[it] == null })
            insert(given.filter { it.titleId !in stored })
            update(given.filter { stored[it.titleId].let { old -> old != null && old != it } })
        }
    }

    @Query("SELECT * FROM list_state WHERE id = ${ListStateRow.ID}")
    protected abstract suspend fun storedState(): ListStateRow?

    @Insert
    protected abstract suspend fun insert(state: ListStateRow)

    @Update
    protected abstract suspend fun update(state: ListStateRow)

    suspend fun state(): ListStateRow = storedState() ?: ListStateRow.none

    @Transaction
    open suspend fun setState(change: (ListStateRow) -> ListStateRow) {
        val stored = storedState()
        val next = change(stored ?: ListStateRow.none)
        if (stored == null) {
            insert(next)
        } else if (next != stored) {
            update(next)
        }
    }

    private companion object {
        const val LISTED =
            "SELECT t.*, e.shown_status, e.shown_watched, e.shown_score, e.shown_start_date, e.shown_finish_date, " +
                "e.shown_updated_at FROM list_entry e JOIN title t ON t.id = e.title_id WHERE e.shown_status IS NOT NULL"
    }
}
