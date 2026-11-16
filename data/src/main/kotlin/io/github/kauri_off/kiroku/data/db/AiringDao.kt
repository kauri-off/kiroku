package io.github.kauri_off.kiroku.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class AiringDao {
    // One statement, so one state of the store, whatever is written beside it.
    @Query(STORED)
    abstract suspend fun stored(): List<AiringEpisodeJoin>

    @Query(STORED)
    abstract fun observeStored(): Flow<List<AiringEpisodeJoin>>

    @Query("DELETE FROM airing_episode WHERE title_id IN (:ids)")
    protected abstract suspend fun deleteEpisodes(ids: List<Long>)

    @Query("DELETE FROM airing WHERE title_id IN (:ids)")
    protected abstract suspend fun delete(ids: List<Long>)

    @Insert
    protected abstract suspend fun insert(rows: List<AiringRow>)

    @Insert
    protected abstract suspend fun insertEpisodes(rows: List<AiringEpisodeRow>)

    // Each title replaced by what is given, or deleted when given none; the caller holds the transaction.
    suspend fun replace(titles: Map<Long, Pair<AiringRow, List<AiringEpisodeRow>>?>) {
        for (ids in titles.keys.chunked(MAX_VARIABLES)) {
            deleteEpisodes(ids)
            delete(ids)
            val given = ids.mapNotNull { titles[it] }
            insert(given.map { it.first })
            insertEpisodes(given.flatMap { it.second })
        }
    }

    private companion object {
        const val STORED =
            "SELECT a.title_id, a.fetched_at, a.known, a.total_episodes, e.number, e.airs_at FROM airing a " +
                "LEFT JOIN airing_episode e ON e.title_id = a.title_id ORDER BY a.title_id, e.number"
    }
}
