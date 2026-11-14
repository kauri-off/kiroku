package io.github.kauri_off.kiroku.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TitleDao {
    @Query("SELECT * FROM title WHERE id = :id")
    abstract fun observe(id: Long): Flow<TitleRow?>

    @Transaction
    @Query("SELECT * FROM title_details WHERE title_id = :id")
    abstract fun observeDetails(id: Long): Flow<DetailsWithTitles?>

    @Query("SELECT * FROM title WHERE id IN (:ids)")
    protected abstract suspend fun rows(ids: List<Long>): List<TitleRow>

    @Insert
    protected abstract suspend fun insert(rows: List<TitleRow>)

    @Update
    protected abstract suspend fun update(rows: List<TitleRow>)

    // For a title known only in part: what it cannot leave absent may be a guess, so it changes no stored title.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(rows: List<TitleRow>)

    // The one door of a whole summary from MAL, by TitleRow.mergedWith; a title it does not change is not written.
    @Transaction
    open suspend fun merge(rows: List<TitleRow>) {
        val newer = rows.associateBy { it.id }
        val stored =
            newer.keys
                .chunked(MAX_VARIABLES)
                .flatMap { rows(it) }
                .associateBy { it.id }
        insert(newer.values.filter { it.id !in stored })
        update(stored.values.mapNotNull { old -> old.mergedWith(newer.getValue(old.id)).takeIf { it != old } })
    }

    // Every title with what its page holds; no entry may be left.
    @Query("DELETE FROM title")
    abstract suspend fun deleteAll()

    @Query("SELECT * FROM title_details WHERE title_id = :id")
    protected abstract suspend fun details(id: Long): TitleDetailsRow?

    @Insert
    protected abstract suspend fun insert(row: TitleDetailsRow)

    @Update
    protected abstract suspend fun update(row: TitleDetailsRow)

    @Query("SELECT * FROM title_relation WHERE title_id = :id ORDER BY position")
    protected abstract suspend fun relations(id: Long): List<TitleRelationRow>

    @Query("DELETE FROM title_relation WHERE title_id = :id")
    protected abstract suspend fun deleteRelations(id: Long)

    @Insert
    protected abstract suspend fun insertRelations(rows: List<TitleRelationRow>)

    @Query("SELECT * FROM title_recommendation WHERE title_id = :id ORDER BY position")
    protected abstract suspend fun recommendations(id: Long): List<TitleRecommendationRow>

    @Query("DELETE FROM title_recommendation WHERE title_id = :id")
    protected abstract suspend fun deleteRecommendations(id: Long)

    @Insert
    protected abstract suspend fun insertRecommendations(rows: List<TitleRecommendationRow>)

    // A title's details as MAL gave them now: values by TitleDetailsRow.mergedWith, the lists as given.
    @Transaction
    open suspend fun mergeDetails(
        row: TitleDetailsRow,
        related: List<TitleRelationRow>,
        recommended: List<TitleRecommendationRow>,
    ) {
        val stored = details(row.titleId)
        if (stored == null) insert(row) else update(stored.mergedWith(row))
        if (relations(row.titleId) != related) {
            deleteRelations(row.titleId)
            insertRelations(related)
        }
        if (recommendations(row.titleId) != recommended) {
            deleteRecommendations(row.titleId)
            insertRecommendations(recommended)
        }
    }
}
