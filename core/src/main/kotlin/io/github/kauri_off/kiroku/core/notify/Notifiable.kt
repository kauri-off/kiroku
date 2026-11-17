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
    // The aired episodes not watched, by the title's exact count; null for one that stands while its title has no exact count.
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
    // N7: the titles whose posted announcement no longer stands: not notified, watched, or of an episode that has not aired.
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
    val followed = record.following(entries, settings, now)
    val titles = followed.titles.toMutableMap()
    val due = mutableListOf<Due>()
    val standing = mutableListOf<Announcement>()
    val coming = mutableListOf<Coming>()
    for (entry in entries.associateBy { it.anime.id }.values) {
        val anime = entry.anime
        val title = titles[anime.id] ?: continue
        // N4: a title notifications are not for announces nothing, and what was posted of it is withdrawn below.
        val since = title.followed?.since ?: continue
        val watched = entry.values.watchedEpisodes
        val highest = title.announced?.episode ?: 0
        val lastAiredAt = title.announced?.airedAt
        val airing = calculate(anime, schedules[anime.id], now)
        val count = (airing.aired as? AiredCount.Exact)?.value
        val stands = standsAt(title, airing, count, now)
        // N1, N4, N5: not watched, not announced unless for a later air time, and due after the moment the title is followed from.
        val open =
            airing.airTimes
                .filter { it.number > maxOf(watched, stands) && (it.number > highest || lastAiredAt?.isBefore(it.airsAt) == true) }
                .map { Timed(it.number, it.airsAt, it.airsAt.plus(delay)) }
                .filter { it.notifyAt.isAfter(since) }
        // N2, N6: the highest episode whose notification time has come, no longer ago than the limit.
        val announce = open.filter { !it.notifyAt.isAfter(now) && !now.isAfter(it.notifyAt.plus(limit)) }.maxByOrNull { it.episode }
        if (announce != null && count != null) {
            due += Due(Announcement(anime, announce.episode, count - watched, watched + 1), announce.airsAt, announce.notifyAt)
            titles[anime.id] =
                title.copy(
                    announced = Announced(maxOf(announce.episode, highest), maxOf(announce.airsAt, lastAiredAt ?: announce.airsAt)),
                    takenBackTo = announce.episode.takeIf { it < highest },
                    standing = true,
                )
        } else {
            // N7: an announcement taken back is withdrawn; any other stands while its episode is not watched, without an exact count too.
            val stood = title.standing && stands >= title.standsAt
            if (stood && watched < stands) standing += Announcement(anime, stands, count?.minus(watched), watched + 1)
            titles[anime.id] = title.copy(takenBackTo = stands.takeIf { it < highest }, standing = stood)
        }
        // N8: the soonest time still to come, which announces the highest episode it is the time of.
        open
            .filter { it.notifyAt.isAfter(now) && it.episode > (announce?.episode ?: 0) }
            .minWithOrNull(compareBy<Timed> { it.notifyAt }.thenByDescending { it.episode })
            ?.let { coming += Coming(anime, it.episode, it.airsAt, it.notifyAt) }
    }
    // N7: any other posted announcement is withdrawn.
    val kept = due.mapTo(mutableSetOf()) { it.announcement.anime.id } + standing.map { it.anime.id }
    val withdrawn = followed.titles.filter { (id, title) -> title.standing && id !in kept }.keys
    for (id in withdrawn) titles[id] = titles.getValue(id).copy(standing = false)
    return Notifications(
        due = due.sortedBy { it.notifyAt },
        standing = standing,
        withdrawn = withdrawn,
        coming = coming.sortedBy { it.notifyAt },
        record = followed.copy(titles = titles),
    )
}

// N1: the episode a title's announcements stand at, by what the calculator says has aired.
private fun standsAt(
    title: TitleRecord,
    airing: Airing,
    count: Int?,
    now: Instant,
): Int {
    val announced = title.announced
    if (count == null || announced == null) return title.standsAt
    // An exact count below it says what was announced has not aired: it is taken back, to be announced when it airs.
    if (count < title.standsAt) return count
    // What was taken back and has aired after all, no later than an air time announced, was announced for that airing.
    val times = airing.airTimes.associate { it.number to it.airsAt }
    val asAnnounced = { episode: Int -> times[episode]?.let { !it.isAfter(announced.airedAt) && !it.isAfter(now) } == true }
    // N6: the highest episode announced told of those below it, so they stand with it.
    if (count >= announced.episode && asAnnounced(announced.episode)) return announced.episode
    var stands = title.standsAt
    while (stands < minOf(count, announced.episode) && asAnnounced(stands + 1)) stands++
    return stands
}

private class Timed(
    val episode: Int,
    val airsAt: Instant,
    val notifyAt: Instant,
)
