package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles

@Entity(tableName = "manga_list_state")
internal data class MangaListStateRow(
    @PrimaryKey val owner: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
    @ColumnInfo(name = "needs_refresh", defaultValue = "0") val needsRefresh: Boolean = false,
)

@Entity(
    tableName = "manga_list_entry",
    primaryKeys = ["owner", "manga_id"],
    foreignKeys = [
        ForeignKey(
            entity = MangaListStateRow::class,
            parentColumns = ["owner"],
            childColumns = ["owner"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class MangaListEntryRow(
    val owner: String,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    val romaji: String,
    val english: String?,
    val japanese: String?,
    val synonyms: String,
    val poster: String?,
    val status: String,
    @ColumnInfo(name = "chapters_read") val chaptersRead: Int?,
    @ColumnInfo(name = "volumes_read") val volumesRead: Int?,
    val score: Int?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long?,
) {
    fun toEntry(): MangaListEntry =
        MangaListEntry(
            id = MangaId(mangaId),
            titles = Titles(romaji, english, japanese, textsOf(synonyms)),
            status = MangaReadingStatus(status),
            posterUrl = poster,
            chaptersRead = chaptersRead,
            volumesRead = volumesRead,
            score = score,
            updatedAt = updatedAt?.let(::instantOf),
        )
}

internal fun MangaListEntry.toRow(owner: SignInId): MangaListEntryRow =
    MangaListEntryRow(
        owner = owner.value,
        mangaId = id.value,
        romaji = titles.romaji,
        english = titles.english,
        japanese = titles.japanese,
        synonyms = textsToColumn(titles.synonyms),
        poster = posterUrl,
        status = status.code,
        chaptersRead = chaptersRead,
        volumesRead = volumesRead,
        score = score,
        updatedAt = updatedAt?.toColumn(),
    )

@Dao
internal interface MangaListDao {
    @Query("SELECT * FROM manga_list_state WHERE owner = :owner")
    suspend fun state(owner: String): MangaListStateRow?

    @Query("SELECT * FROM manga_list_entry WHERE owner = :owner ORDER BY manga_id")
    suspend fun entries(owner: String): List<MangaListEntryRow>

    @Query("SELECT * FROM manga_list_entry WHERE owner = :owner AND manga_id = :id")
    suspend fun entry(
        owner: String,
        id: Long,
    ): MangaListEntryRow?

    @Query("UPDATE manga_list_state SET needs_refresh = :needed WHERE owner = :owner")
    suspend fun requireRefresh(
        owner: String,
        needed: Boolean,
    )

    @Query("DELETE FROM manga_list_entry WHERE owner = :owner")
    suspend fun deleteEntries(owner: String)

    @Query("DELETE FROM manga_list_state WHERE :owner IS NULL OR owner != :owner")
    suspend fun deleteAllBut(owner: String?)

    @Upsert
    suspend fun saveState(row: MangaListStateRow)

    @Insert
    suspend fun insertEntries(rows: List<MangaListEntryRow>)
}
