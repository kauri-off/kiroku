package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
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
    val titles: Map<AnimeId, TitleRecord> = emptyMap(),
)

// N5: the record once it has seen the list and the settings as they are at [now], which is the moment it sets.
fun NotificationRecord.following(
    entries: Collection<ListEntry>,
    settings: NotificationSettings,
    now: Instant,
): NotificationRecord {
    val listed = entries.associateBy { it.anime.id }
    val seen =
        (titles.keys + listed.keys).mapNotNull { id ->
            val before = titles[id]
            val entry = listed[id]
            // A title missing from the list is the title it was, while the settings still cover its status.
            val status = entry?.status ?: before?.followed?.status
            val followed =
                when {
                    status == null || !settings.notifies(status) -> null
                    before?.followed?.status == status -> before.followed
                    else -> Followed(status, now)
                }
            val announced = before?.announced
            if (followed == null && announced == null) null else id to TitleRecord(followed, announced, before?.standing == true)
        }
    return NotificationRecord(seen.toMap())
}
