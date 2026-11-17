package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.JAPAN
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// A title of twelve episodes, one a week from a Saturday at 23:30 in Japan: week(n) is the air time of episode n + 1.
object NotifyCases {
    private val start = LocalDate.of(2026, 1, 3)
    private val slot = LocalTime.of(23, 30)

    val day: Duration = Duration.ofDays(1)
    val hour: Duration = Duration.ofHours(1)

    val title: Anime =
        FixtureAnime.exactToWatch.copy(
            airingStatus = AiringStatus.Airing,
            malEpisodes = 12,
            startDate = PartialDate.Day(start),
            broadcast = Broadcast(DayOfWeek.SATURDAY, slot),
        )

    // A second title, whose episodes air five days after [title]'s.
    val other: Anime = title.copy(id = FixtureAnime.exactCaughtUp.id, titles = FixtureAnime.exactCaughtUp.titles)

    val otherAfter: Duration = Duration.ofDays(5)

    val schedules: Map<AnimeId, AiringSchedule> =
        mapOf(
            title.id to weekly(),
            other.id to weekly().let { it.copy(episodes = it.episodes.map { e -> e.copy(airsAt = e.airsAt.plus(otherAfter)) }) },
        )

    fun week(n: Int): Instant =
        start
            .atTime(slot)
            .atZone(JAPAN)
            .toInstant()
            .plus(Duration.ofDays(7L * n))

    // One episode a week, the first of [numbers] at week [from].
    fun weekly(
        numbers: IntRange = 1..12,
        from: Int = numbers.first - 1,
        total: Int? = 12,
    ) = AiringSchedule(numbers.mapIndexed { index, number -> ScheduledEpisode(number, week(from + index)) }, total)

    // [updatedAt] is the time of the entry's last change: the list provider's, or the app's own when the change was made in it.
    fun entry(
        anime: Anime = title,
        status: EntryStatus = ListStatus.Watching,
        watched: Int = 0,
        updatedAt: Instant = Instant.EPOCH,
    ) = ListEntry(anime, ListValues(status, watched, null, null, null), updatedAt)
}
