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
internal object NotifyCases {
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

    fun entry(
        anime: Anime = title,
        status: EntryStatus = ListStatus.Watching,
        watched: Int = 0,
    ) = ListEntry(anime, ListValues(status, watched, null, null, null), Instant.EPOCH)

    // A device whose record was first written a day before episode 1, so every episode is from after it.
    fun followed(
        vararg entries: ListEntry,
        schedules: Map<AnimeId, AiringSchedule> = this.schedules,
        settings: NotificationSettings = NotificationSettings(),
    ) = Device(entries.toList(), schedules, settings).also { it.run(week(0).minus(day)) }
}

// What a device stores: the list, the schedules, the settings, and the record as each run leaves it.
internal class Device(
    var entries: List<ListEntry>,
    var schedules: Map<AnimeId, AiringSchedule> = NotifyCases.schedules,
    var settings: NotificationSettings = NotificationSettings(),
    var record: NotificationRecord = NotificationRecord(),
) {
    // Every episode announced, in order.
    val announced = mutableListOf<Pair<AnimeId, Int>>()

    // One run: the rule applied at [now] and its result recorded.
    fun run(now: Instant): Notifications {
        val result = notificationsAt(entries, schedules, settings, record, now)
        record = result.record
        announced += result.announced
        return result
    }

    // A run every half hour from [from] to [until], which meets every notification time of the cases.
    fun walk(
        from: Instant,
        until: Instant,
    ): List<Notifications> = generateSequence(from) { it.plus(Duration.ofMinutes(30)) }.takeWhile { !it.isAfter(until) }.map(::run).toList()

    fun episodes(id: AnimeId = NotifyCases.title.id): List<Int> = announced.filter { it.first == id }.map { it.second }

    fun change(
        id: AnimeId = NotifyCases.title.id,
        to: (ListValues) -> ListValues,
    ) {
        entries = entries.map { if (it.anime.id == id) it.copy(values = to(it.values)) else it }
    }

    fun status(
        status: EntryStatus,
        id: AnimeId = NotifyCases.title.id,
    ) = change(id) { it.copy(status = status) }

    fun watched(
        count: Int,
        id: AnimeId = NotifyCases.title.id,
    ) = change(id) { it.copy(watchedEpisodes = count) }
}

internal val Notifications.announced: List<Pair<AnimeId, Int>> get() = due.map { it.announcement.anime.id to it.announcement.episode }

internal val List<Notifications>.announced: List<Pair<AnimeId, Int>> get() = flatMap { it.announced }

// Nothing is announced and nothing is planned.
internal val Notifications.silent: Boolean get() = due.isEmpty() && coming.isEmpty()
