package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "manga")
internal data class MangaRow(
    @PrimaryKey val id: Long,
    val romaji: String,
    val english: String?,
    val japanese: String?,
    val poster: String?,
    @ColumnInfo(name = "large_poster") val largePoster: String?,
    val synopsis: String?,
    val format: String?,
    val publication: String?,
    val volumes: Int?,
    val chapters: Int?,
    @ColumnInfo(name = "mean_score") val meanScore: Double?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

@Dao
internal interface MangaDao {
    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun read(id: Long): MangaRow?

    @Query("DELETE FROM manga")
    suspend fun deleteAll()

    @Upsert
    suspend fun save(row: MangaRow)
}
