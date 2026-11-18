package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.Announced
import io.github.kauri_off.kiroku.core.notify.Followed
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.notify.TitleRecord

// The tables of the record (N10) have no key to the title table: the list's clearing of titles never takes a row of them.

// Whose record is stored and its two moments, in the one row with [ID]; no row is a record of nobody's.
@Entity(tableName = NotificationStateRow.TABLE)
internal data class NotificationStateRow(
    @PrimaryKey val id: Int,
    val owner: String?,
    val since: Long?,
    @ColumnInfo(name = "planned_since") val plannedSince: Long?,
) {
    val signIn: SignInId? get() = owner?.let(::SignInId)

    companion object {
        const val TABLE = "notification_state"
        const val ID = 0
    }
}

// What notifications remember of one title.
@Entity(tableName = NotificationTitleRow.TABLE)
internal data class NotificationTitleRow(
    @PrimaryKey @ColumnInfo(name = "title_id") val titleId: Long,
    @ColumnInfo(name = "followed_status") val followedStatus: String?,
    @ColumnInfo(name = "followed_since") val followedSince: Long?,
    @ColumnInfo(name = "announced_episode") val announcedEpisode: Int?,
    @ColumnInfo(name = "announced_aired_at") val announcedAiredAt: Long?,
    @ColumnInfo(name = "taken_back_to") val takenBackTo: Int?,
    val standing: Boolean,
) {
    fun toRecord(): Pair<AnimeId, TitleRecord> {
        check((followedStatus == null) == (followedSince == null) && (announcedEpisode == null) == (announcedAiredAt == null))
        val followed = followedStatus?.let { Followed(listStatuses.of(it), instantOf(checkNotNull(followedSince))) }
        val announced = announcedEpisode?.let { Announced(it, instantOf(checkNotNull(announcedAiredAt))) }
        return AnimeId(titleId) to TitleRecord(followed, announced, takenBackTo, standing)
    }

    companion object {
        const val TABLE = "notification_title"
    }
}

internal fun TitleRecord.toRow(id: AnimeId): NotificationTitleRow =
    NotificationTitleRow(
        titleId = id.value,
        followedStatus = followed?.status?.let(listStatuses::toColumn),
        followedSince = followed?.since?.toColumn(),
        announcedEpisode = announced?.episode,
        announcedAiredAt = announced?.airedAt?.toColumn(),
        takenBackTo = takenBackTo,
        standing = standing,
    )

// One notification posted, in the order written; the title is read from the title table.
@Entity(tableName = SentRow.TABLE)
internal data class SentRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title_id") val titleId: Long,
    val episode: Int,
    @ColumnInfo(name = "aired_at") val airedAt: Long,
    @ColumnInfo(name = "notify_at") val notifyAt: Long,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    val recheck: String,
    val shown: Boolean,
) {
    companion object {
        const val TABLE = "notification_sent"
    }
}

internal fun SentNotification.toRow(): SentRow =
    SentRow(
        titleId = anime.id.value,
        episode = episode,
        airedAt = airedAt.toColumn(),
        notifyAt = notifyAt.toColumn(),
        postedAt = postedAt.toColumn(),
        recheck = rechecks.toColumn(recheck),
        shown = shown,
    )

// A sent row with its title's summary.
internal data class SentWithTitle(
    @Embedded val title: TitleRow,
    val episode: Int,
    @ColumnInfo(name = "aired_at") val airedAt: Long,
    @ColumnInfo(name = "notify_at") val notifyAt: Long,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    val recheck: String,
    val shown: Boolean,
) {
    fun toSent(): SentNotification =
        SentNotification(
            title.toAnime(),
            episode,
            instantOf(airedAt),
            instantOf(notifyAt),
            instantOf(postedAt),
            rechecks.of(recheck),
            shown,
        )
}

// The app's own codes for how a re-check ended.
internal val rechecks =
    Codes(Recheck.entries) {
        when (it) {
            Recheck.NotAsked -> "not_asked"
            Recheck.Offline -> "offline"
            Recheck.Answered -> "answered"
            Recheck.Failed -> "failed"
            Recheck.Late -> "late"
        }
    }
