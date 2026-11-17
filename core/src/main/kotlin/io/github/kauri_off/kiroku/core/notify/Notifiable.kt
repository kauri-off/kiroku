package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import java.time.Instant
import kotlin.time.toJavaDuration

// What a notification says of its title.
data class Announcement(
    val anime: Anime,
    val episode: Int,
    // The aired episodes not watched, by the title's exact count; null for one that stands while no exact count reaches its episode.
    val toWatch: Int?,
    // N9: the episode "Mark watched" marks, the next one not watched.
    val marks: Int,
)

// An announcement to post now.
data class Due(
    val announcement: Announcement,
    val airedAt: Instant,
    val notifyAt: Instant,
)

// A title's next announcement, as the stored data has it.
data class Coming(
    val anime: Anime,
    val episode: Int,
    val airsAt: Instant,
    val notifyAt: Instant,
)

data class Notifications(
    // N6: one per title, for its highest due episode.
    val due: List<Due>,
    // The announcements posted earlier that still stand, as they read now.
    val standing: List<Announcement>,
    // N7: the titles whose posted announcement no longer stands.
    val withdrawn: Set<AnimeId>,
    // Each title's next, the soonest first.
    val coming: List<Coming>,
    // The record once all of this is carried out.
    val record: NotificationRecord,
) {
    // N8: the earliest notification time still to come.
    val nextAt: Instant? get() = coming.firstOrNull()?.notifyAt
}

// What is notifiable at [now] (section 5.1), from the stored list, the stored schedules, the settings and the record.
fun notificationsAt(
    entries: Collection<ListEntry>,
    schedules: Map<AnimeId, AiringSchedule>,
    settings: NotificationSettings,
    record: NotificationRecord,
    now: Instant,
): Notifications = notificationsAt(entries, schedules, settings, record, now, ::airingAt)

// A1: whether a count is exact and when an episode airs is [calculate]'s answer alone.
internal fun notificationsAt(
    entries: Collection<ListEntry>,
    schedules: Map<AnimeId, AiringSchedule>,
    settings: NotificationSettings,
    record: NotificationRecord,
    now: Instant,
    calculate: (Anime, AiringSchedule?, Instant) -> Airing,
): Notifications {
    val delay = settings.delay.duration.toJavaDuration()
    val limit = announcedNoLaterThan.toJavaDuration()
    val titles = record.following(entries, settings, now).titles.toMutableMap()
    val due = mutableListOf<Due>()
    val standing = mutableListOf<Announcement>()
    val coming = mutableListOf<Coming>()
    for (entry in entries.associateBy { it.anime.id }.values) {
        val anime = entry.anime
        val title = titles[anime.id] ?: continue
        // N4: a title notifications are not for announces nothing, and what was posted of it is withdrawn below.
        val since = title.followed?.since ?: continue
        val watched = entry.values.watchedEpisodes
        val announced = title.announced ?: 0
        val airing = calculate(anime, schedules[anime.id], now)
        val count = (airing.aired as? AiredCount.Exact)?.value
        // N1, N4, N5: not announced, not watched, and with a notification time after the moment the title is followed from.
        val open =
            airing.airTimes
                .filter { it.number > maxOf(watched, announced) }
                .map { Timed(it.number, it.airsAt, it.airsAt.plus(delay)) }
                .filter { it.notifyAt.isAfter(since) }
        // N6: the highest episode whose notification time has come; N2: unless that was too long ago.
        val passed = open.filter { !it.notifyAt.isAfter(now) }.maxByOrNull { it.episode }
        val announce = passed?.takeIf { !now.isAfter(it.notifyAt.plus(limit)) }
        if (announce != null && count != null) {
            due += Due(Announcement(anime, announce.episode, count - watched, watched + 1), announce.airsAt, announce.notifyAt)
            titles[anime.id] = title.copy(announced = announce.episode, standing = true)
        } else if (title.standing && watched < announced) {
            standing += Announcement(anime, announced, count?.takeIf { it >= announced }?.minus(watched), watched + 1)
        }
        // N8: the soonest time still to come, which announces the highest episode it is the time of.
        open
            .filter { it.notifyAt.isAfter(now) && it.episode > (passed?.episode ?: 0) }
            .minWithOrNull(compareBy<Timed> { it.notifyAt }.thenByDescending { it.episode })
            ?.let { coming += Coming(anime, it.episode, it.airsAt, it.notifyAt) }
    }
    // N7: a posted announcement stands while its title is one notifications are for and its episode is not watched.
    val kept = due.mapTo(mutableSetOf()) { it.announcement.anime.id } + standing.map { it.anime.id }
    val withdrawn = titles.filter { (id, title) -> title.standing && id !in kept }.keys
    for (id in withdrawn) titles[id] = titles.getValue(id).copy(standing = false)
    return Notifications(
        due = due.sortedWith(compareBy({ it.notifyAt }, { it.announcement.anime.id.value })),
        standing = standing.sortedBy { it.anime.id.value },
        withdrawn = withdrawn,
        coming = coming.sortedWith(compareBy({ it.notifyAt }, { it.anime.id.value })),
        record = NotificationRecord(titles),
    )
}

private class Timed(
    val episode: Int,
    val airsAt: Instant,
    val notifyAt: Instant,
)
