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

    // [updatedAt] is MAL's time of the entry's last change, or the app's own when the change was made in it.
    fun entry(
        anime: Anime = title,
        status: EntryStatus = ListStatus.Watching,
        watched: Int = 0,
        updatedAt: Instant = Instant.EPOCH,
    ) = ListEntry(anime, ListValues(status, watched, null, null, null), updatedAt)

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
    // Every episode announced, in order, and each with the air time it was announced for.
    val announced = mutableListOf<Pair<AnimeId, Int>>()
    val airings = mutableListOf<Triple<AnimeId, Int, Instant>>()

    // One run: the rule applied at [now] and its result recorded; a run cut off after it posted records nothing.
    fun run(
        now: Instant,
        cutOff: Boolean = false,
    ): Notifications {
        val result = notificationsAt(entries, schedules, settings, record, now)
        if (cutOff) return result
        record = result.record
        announced += result.announced
        airings += result.airings
        return result
    }

    // A run every half hour from [from] to [until], which meets every notification time of the cases.
    fun walk(
        from: Instant,
        until: Instant,
    ): List<Notifications> =
        generateSequence(from) {
            it.plus(Duration.ofMinutes(30))
        }.takeWhile { !it.isAfter(until) }.map { run(it) }.toList()

    fun episodes(id: AnimeId = NotifyCases.title.id): List<Int> = announced.filter { it.first == id }.map { it.second }

    // The status as changed at [at], in the app or on the website.
    fun status(
        status: EntryStatus,
        at: Instant,
        id: AnimeId = NotifyCases.title.id,
    ) {
        entries = entries.map { if (it.anime.id == id) it.copy(values = it.values.copy(status = status), updatedAt = at) else it }
    }

    fun watched(
        count: Int,
        id: AnimeId = NotifyCases.title.id,
    ) {
        entries = entries.map { if (it.anime.id == id) it.copy(values = it.values.copy(watchedEpisodes = count)) else it }
    }

    fun remove(id: AnimeId = NotifyCases.title.id) {
        entries = entries.filter { it.anime.id != id }
    }
}

internal val Notifications.announced: List<Pair<AnimeId, Int>> get() = due.map { it.announcement.anime.id to it.announcement.episode }

internal val Notifications.airings: List<Triple<AnimeId, Int, Instant>>
    get() = due.map { Triple(it.announcement.anime.id, it.announcement.episode, it.airedAt) }

internal val List<Notifications>.announced: List<Pair<AnimeId, Int>> get() = flatMap { it.announced }

// Nothing is announced and nothing is planned.
internal val Notifications.silent: Boolean get() = due.isEmpty() && coming.isEmpty()
