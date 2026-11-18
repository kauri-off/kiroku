package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.StoredAiring

// A title's last answer about its airing; no title row is needed, so the list and a title's page never wait on it.
@Entity(tableName = AiringRow.TABLE)
internal data class AiringRow(
    @PrimaryKey @ColumnInfo(name = "title_id") val titleId: Long,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    // False when the source did not know the title: it then has no schedule, total or episodes.
    val known: Boolean,
    @ColumnInfo(name = "total_episodes") val totalEpisodes: Int?,
    @ColumnInfo(name = "doubted_over") val doubtedOver: Int?,
) {
    companion object {
        const val TABLE = "airing"
    }
}

// One episode of a stored schedule, at most one per number; it goes when its title's row goes.
@Entity(
    tableName = AiringEpisodeRow.TABLE,
    primaryKeys = ["title_id", "number"],
    foreignKeys = [
        ForeignKey(AiringRow::class, parentColumns = ["title_id"], childColumns = ["title_id"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class AiringEpisodeRow(
    @ColumnInfo(name = "title_id") val titleId: Long,
    val number: Int,
    @ColumnInfo(name = "airs_at") val airsAt: Long,
) {
    companion object {
        const val TABLE = "airing_episode"
    }
}

// A title's row beside one of its episodes, or beside none.
internal data class AiringEpisodeJoin(
    @ColumnInfo(name = "title_id") val titleId: Long,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    val known: Boolean,
    @ColumnInfo(name = "total_episodes") val totalEpisodes: Int?,
    @ColumnInfo(name = "doubted_over") val doubtedOver: Int?,
    val number: Int?,
    @ColumnInfo(name = "airs_at") val airsAt: Long?,
)

// The rows of one title, in the order of its episodes.
internal fun List<AiringEpisodeJoin>.toStored(): StoredAiring {
    val title = first()
    val schedule =
        if (title.known) {
            AiringSchedule(
                episodes = mapNotNull { row -> row.number?.let { ScheduledEpisode(it, instantOf(checkNotNull(row.airsAt))) } },
                totalEpisodes = title.totalEpisodes,
                doubtedOver = title.doubtedOver,
            )
        } else {
            check(title.totalEpisodes == null && title.doubtedOver == null && all { it.number == null })
            null
        }
    return StoredAiring(schedule, instantOf(title.fetchedAt))
}

internal fun StoredAiring.toRow(titleId: Long): AiringRow =
    AiringRow(
        titleId,
        fetchedAt.toColumn(),
        known = schedule != null,
        totalEpisodes = schedule?.totalEpisodes,
        doubtedOver = schedule?.doubtedOver,
    )

internal fun StoredAiring.toEpisodeRows(titleId: Long): List<AiringEpisodeRow> =
    schedule?.episodes.orEmpty().map { AiringEpisodeRow(titleId, it.number, it.airsAt.toColumn()) }
