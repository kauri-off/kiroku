package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import java.time.Instant

// N5: the list status a title is followed in, and the moment it is followed from.
data class Followed(
    val status: EntryStatus,
    val since: Instant,
)

// What notifications remember of one title.
data class TitleRecord(
    // Null while the title is not one notifications are for.
    val followed: Followed?,
    // N1: the highest episode announced; none up to it is announced again.
    val announced: Int?,
    // N7: that announcement was posted and has not been withdrawn.
    val standing: Boolean = false,
) {
    init {
        require(followed != null || announced != null)
        require(announced == null || announced > 0)
        require(!standing || announced != null)
    }
}

// All that notifications remember; a sign-out forgets it (S9).
data class NotificationRecord(
    // N5: when notifications came to cover the list, at a sign-in's or a build's first run over it or at their turning on.
    val since: Instant? = null,
    // The same of its planned titles; null while notifications are not for them.
    val plannedSince: Instant? = null,
    val titles: Map<AnimeId, TitleRecord> = emptyMap(),
) {
    init {
        require(plannedSince == null || since != null)
        require(since != null || titles.values.none { it.followed != null })
    }
}

// N5: the record once it has seen the list and the settings as they are at [now]; no moment in it is later than [now].
fun NotificationRecord.following(
    entries: Collection<ListEntry>,
    settings: NotificationSettings,
    now: Instant,
): NotificationRecord {
    val listed = entries.associateBy { it.anime.id }
    // A record that has seen no list keeps none, so nothing of a signed-out time is carried into a sign-in.
    val since = if (settings.on && (since != null || listed.isNotEmpty())) minOf(since ?: now, now) else null
    val plannedSince = if (since != null && settings.alsoPlanned) minOf(plannedSince ?: now, now) else null
    val seen =
        (titles.keys + listed.keys).mapNotNull { id ->
            val before = titles[id]
            // A title that is not on the list is followed no longer, and is a new one when it is there again.
            val entry = listed[id]
            val covered = if (entry?.status == ListStatus.Planned) plannedSince else since
            val followed =
                when {
                    entry == null || covered == null || !settings.notifies(entry.status) -> null
                    before?.followed?.status == entry.status -> before.followed.copy(since = minOf(before.followed.since, now))
                    // Followed from when the title was added or its status changed, as its entry tells, and not from before [covered].
                    else -> Followed(entry.status, maxOf(covered, minOf(entry.updatedAt, now)))
                }
            val announced = before?.announced
            if (followed == null && announced == null) null else id to TitleRecord(followed, announced, before?.standing == true)
        }
    return NotificationRecord(since, plannedSince, seen.toMap())
}
