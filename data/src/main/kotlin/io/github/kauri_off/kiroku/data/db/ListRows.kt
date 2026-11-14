package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.kauri_off.kiroku.core.list.ListRecord
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredList
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.SignInId

internal data class RecordColumns(
    val status: String,
    val watched: Int,
    val score: Int?,
    @ColumnInfo(name = "start_date") val startDate: String?,
    @ColumnInfo(name = "finish_date") val finishDate: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toRecord(): ListRecord =
        ListRecord(
            ListValues(listStatuses.of(status), watched, score, startDate?.let(::partialDateOf), finishDate?.let(::partialDateOf)),
            instantOf(updatedAt),
        )
}

internal fun ListRecord.toColumns(): RecordColumns =
    RecordColumns(
        status = listStatuses.toColumn(values.status),
        watched = values.watchedEpisodes,
        score = values.score,
        startDate = values.startDate?.toColumn(),
        finishDate = values.finishDate?.toColumn(),
        updatedAt = updatedAt.toColumn(),
    )

// A title's entry on the user's list. A title that has one cannot be deleted.
@Entity(
    tableName = ListEntryRow.TABLE,
    foreignKeys = [ForeignKey(TitleRow::class, parentColumns = ["id"], childColumns = ["title_id"])],
)
internal data class ListEntryRow(
    @PrimaryKey @ColumnInfo(name = "title_id") val titleId: Long,
    @Embedded(prefix = "shown_") val shown: RecordColumns?,
    @Embedded(prefix = "confirmed_") val confirmed: RecordColumns?,
    val unconfirmed: Boolean,
) {
    fun toStored(): StoredEntry = StoredEntry(shown?.toRecord(), confirmed?.toRecord(), unconfirmed)

    companion object {
        const val TABLE = "list_entry"
    }
}

internal fun StoredEntry.toRow(titleId: Long): ListEntryRow = ListEntryRow(titleId, shown?.toColumns(), confirmed?.toColumns(), unconfirmed)

// Whose list is stored and when it was downloaded, in the one row with [ID]; no list query reads it.
@Entity(tableName = "list_state")
internal data class ListStateRow(
    @PrimaryKey val id: Int,
    val owner: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long?,
) {
    fun toStored(): StoredList = StoredList(owner?.let(::SignInId), syncedAt?.let(::instantOf))

    companion object {
        const val ID = 0
        val none = ListStateRow(ID, owner = null, syncedAt = null)
    }
}

// A shown entry with its title's summary.
internal data class ListedRow(
    @Embedded val title: TitleRow,
    @Embedded(prefix = "shown_") val shown: RecordColumns,
) {
    fun toEntry(): ListEntry = shown.toRecord().let { ListEntry(title.toAnime(), it.values, it.updatedAt) }
}

internal data class TitleWithEntry(
    @Embedded val title: TitleRow,
    @Relation(parentColumn = "id", entityColumn = "title_id") val entry: ListEntryRow?,
) {
    fun toStored(): StoredTitle = StoredTitle(title.toAnime(), entry?.toStored())
}
