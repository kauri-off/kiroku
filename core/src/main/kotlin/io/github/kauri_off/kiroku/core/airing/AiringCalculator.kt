package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.lastDay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

// A4: the episodes by which a schedule that begins after episode 1 may count past MAL's total, which a recap explains.
const val RECAP_EPISODES = 1

// A4: how far ahead of one episode a week from MAL's start date a schedule's first stored episode may be.
const val EPISODES_AHEAD = 3

// A4: the weeks of airing that allow one more episode ahead, for the specials of a long run.
const val WEEKS_PER_EPISODE_AHEAD = 10

// A5: a schedule with nothing coming is a break for this long after its last episode, then it has stopped.
val SCHEDULE_STOPPED_AFTER: Duration = Duration.ofDays(4 * 7)

// A6: the weeks from the first slot for which a title without a total is estimated.
const val ESTIMATED_WEEKS_WITHOUT_TOTAL = 13L

// A6: a slot before this hour on the eve of MAL's start date is a late-night one, written under the evening's weekday.
val LATE_NIGHT_UNTIL: LocalTime = LocalTime.of(5, 0)

private val WEEK: Duration = Duration.ofDays(7)

// What has aired of [anime] at [now], from MAL's data and a stored schedule of episodes with their times.
fun airingAt(
    anime: Anime,
    schedule: AiringSchedule?,
    now: Instant,
): Airing {
    val counted = countedAt(anime, schedule, now)
    val premiere = premiereOf(anime, counted, japanDay(now))
    // A date MAL names for the premiere stands until the last day it names is over in Japan.
    val dateOver =
        anime.startDate
            ?.takeIf { premiere is Premiere.OnDay || premiere is Premiere.InMonth || premiere is Premiere.InYear }
            ?.lastDay()
            ?.plusDays(1)
            ?.atStartOfDay(JAPAN)
            ?.toInstant()
    return counted.copy(premiere = premiere, changesAt = earliest(counted.changesAt, dateOver))
}

private fun countedAt(
    anime: Anime,
    schedule: AiringSchedule?,
    now: Instant,
): Airing {
    val episodes = schedule?.episodes.orEmpty()
    val unread =
        when {
            episodes.isEmpty() -> Inexact.NoSchedule
            numberedDifferently(anime, episodes, now) -> Inexact.NumberedDifferently
            else -> null
        }
    val total = anime.malEpisodes ?: schedule?.totalEpisodes?.takeIf { unread != Inexact.NumberedDifferently }
    val finished = anime.airingStatus.effective == AiringStatus.Finished
    val coming = episodes.filter { it.airsAt.isAfter(now) }
    return when {
        finished && total != null -> {
            val finale = episodes.firstOrNull { it.number == total }?.airsAt.takeIf { unread == null }
            val aired = finale?.takeIf { !it.isAfter(now) }
            Airing(AiredCount.Exact(total), total, null, aired, changesAt = finale.takeIf { aired == null })
        }
        unread != null -> estimated(anime, total, now, unread, wake = coming.minOfOrNull { it.airsAt })
        else -> scheduled(anime, episodes, schedule?.totalEpisodes, total, finished, now)
    }
}

// A4, of a schedule that begins after episode 1: a number past MAL's total, nothing aired, or a first episode too far ahead.
private fun numberedDifferently(
    anime: Anime,
    episodes: List<ScheduledEpisode>,
    now: Instant,
): Boolean {
    val first = episodes.minBy { it.number }
    if (first.number == 1) return false
    val malTotal = anime.malEpisodes
    if (malTotal != null && episodes.any { it.number > malTotal + RECAP_EPISODES }) return true
    if (episodes.none { !it.airsAt.isAfter(now) }) return true
    // A partial date counts from its last day, the latest the title can have begun.
    val start =
        anime.startDate
            ?.lastDay()
            ?.atStartOfDay(JAPAN)
            ?.toInstant() ?: return false
    val weeks = Duration.between(start, first.airsAt).dividedBy(WEEK).coerceAtLeast(0)
    return first.number > 1 + weeks + EPISODES_AHEAD + weeks / WEEKS_PER_EPISODE_AHEAD
}

private fun scheduled(
    anime: Anime,
    episodes: List<ScheduledEpisode>,
    scheduleTotal: Int?,
    total: Int?,
    finished: Boolean,
    now: Instant,
): Airing {
    val aired = episodes.filter { !it.airsAt.isAfter(now) }
    val coming = episodes.filter { it.airsAt.isAfter(now) }
    val highest = aired.maxOfOrNull { it.number } ?: 0
    val count = if (total == null) highest else minOf(highest, total)
    val next = coming.filter { it.number > count && (total == null || it.number <= total) }.minByOrNull { it.number }
    // A5: the schedule's total is the one it states, and at least its highest number.
    val malTotal = anime.malEpisodes
    val totalsDisagree = malTotal != null && ((scheduleTotal != null && scheduleTotal != malTotal) || episodes.any { it.number > malTotal })
    val complete = finished || (total != null && count >= total)
    val stopsAt = aired.maxOfOrNull { it.airsAt }?.takeIf { coming.isEmpty() && !complete }?.plus(SCHEDULE_STOPPED_AFTER)
    val stopped = stopsAt != null && !now.isBefore(stopsAt)
    val doubt =
        when {
            totalsDisagree -> Inexact.TotalsDisagree
            stopped -> Inexact.ScheduleStopped
            else -> null
        }
    return Airing(
        aired = if (doubt == null) AiredCount.Exact(count) else AiredCount.Estimated(count),
        total = total,
        nextEpisode = next.takeIf { doubt == null },
        latestAiredAt = aired.filter { it.number <= count }.maxByOrNull { it.number }?.airsAt,
        inexact = doubt,
        changesAt = earliest(coming.minOfOrNull { it.airsAt }, stopsAt.takeIf { !stopped }),
    )
}

// A6: one episode at each weekly slot from MAL's start date, while that cannot run away.
private fun estimated(
    anime: Anime,
    total: Int?,
    now: Instant,
    reason: Inexact,
    wake: Instant?,
): Airing {
    val first = firstSlot(anime)
    val runsUntil = first?.plus(WEEK.multipliedBy(ESTIMATED_WEEKS_WITHOUT_TOTAL)).takeIf { total == null }
    if (first == null || (runsUntil != null && !now.isBefore(runsUntil))) {
        val notStarted = anime.airingStatus.effective == AiringStatus.NotYetAired
        val count = if (notStarted) AiredCount.Estimated(0) else AiredCount.Unknown
        return Airing(count, total, null, null, reason, wake)
    }
    if (now.isBefore(first)) return Airing(AiredCount.Estimated(0), total, null, null, reason, earliest(first, wake))
    val slots = Duration.between(first, now).dividedBy(WEEK) + 1
    val count = if (total == null) slots else minOf(slots, total.toLong())
    val nextSlot = first.plus(WEEK.multipliedBy(slots)).takeIf { total == null || slots < total }
    return Airing(
        aired = AiredCount.Estimated(count.toInt()),
        total = total,
        nextEpisode = null,
        latestAiredAt = first.plus(WEEK.multipliedBy(count - 1)),
        inexact = reason,
        changesAt = earliest(nextSlot, runsUntil, wake),
    )
}

// The first start of MAL's weekly slot on or after its start date; null unless MAL gives a full date, a weekday and a time.
private fun firstSlot(anime: Anime): Instant? {
    val start = (anime.startDate as? PartialDate.Day)?.date ?: return null
    val broadcast = anime.broadcast ?: return null
    val time = broadcast.japanTime ?: return null
    val eve = start.minusDays(1)
    val day =
        when {
            broadcast.dayOfWeek != eve.dayOfWeek -> start.with(TemporalAdjusters.nextOrSame(broadcast.dayOfWeek))
            // The weekday before the start date: that night's slot, which falls on the start date when it is past midnight.
            time.isBefore(LATE_NIGHT_UNTIL) -> start
            else -> eve
        }
    return day.atTime(time).atZone(JAPAN).toInstant()
}

private fun earliest(vararg instants: Instant?): Instant? = instants.filterNotNull().minOrNull()
