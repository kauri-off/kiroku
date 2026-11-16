package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth

// A title of twelve episodes that MAL starts on a Saturday, weekly at 23:30 in Japan; week(n) is that slot n weeks on.
class AiringCalculatorTest {
    private val start = LocalDate.of(2026, 1, 3)
    private val slot = LocalTime.of(23, 30)
    private val first = start.atTime(slot).atZone(JAPAN).toInstant()
    private val hour = Duration.ofHours(1)

    private val title =
        FixtureAnime.exactToWatch.copy(
            airingStatus = AiringStatus.Airing,
            malEpisodes = 12,
            startDate = PartialDate.Day(start),
            broadcast = Broadcast(DayOfWeek.SATURDAY, slot),
        )
    private val finished = title.copy(airingStatus = AiringStatus.Finished)
    private val notYetAired = title.copy(airingStatus = AiringStatus.NotYetAired)
    private val noSlot = title.copy(broadcast = null)

    private fun week(n: Int): Instant = first.plus(Duration.ofDays(7L * n))

    // One episode a week, the first of [numbers] at week [from].
    private fun weekly(
        numbers: IntRange,
        from: Int = numbers.first - 1,
        total: Int? = 12,
    ) = AiringSchedule(numbers.mapIndexed { index, number -> ScheduledEpisode(number, week(from + index)) }, total)

    private fun episode(number: Int) = ScheduledEpisode(number, week(number - 1))

    private fun exact(
        count: Int,
        total: Int?,
        next: Int?,
    ) = Airing(AiredCount.Exact(count), total, next?.let(::episode), week(count - 1).takeIf { count > 0 })

    private fun Airing.timeless() = copy(changesAt = null)

    private fun at(
        anime: Anime,
        schedule: AiringSchedule?,
        now: Instant,
    ) = airingAt(anime, schedule, now).timeless()

    @Test
    fun `an episode has aired from its air time on, and the count is the highest number aired`() {
        val schedule = weekly(1..12)
        assertEquals(exact(0, 12, next = 1), at(title, schedule, week(0).minusNanos(1)))
        assertEquals(exact(1, 12, next = 2), at(title, schedule, week(0)))
        assertEquals(exact(5, 12, next = 6), at(title, schedule, week(5).minusNanos(1)))
        assertEquals(exact(12, 12, next = null), at(title, schedule, week(11)))
        val gap = AiringSchedule(listOf(episode(1), episode(2), episode(5), episode(6)), 12)
        assertEquals(exact(5, 12, next = 6), at(title, gap, week(4)))
    }

    @Test
    fun `A3 the total is MAL's, otherwise the schedule's, otherwise unknown`() {
        val noMalTotal = title.copy(malEpisodes = null)
        assertEquals(12, airingAt(title, weekly(1..12, total = 13), week(3)).total)
        assertEquals(12, airingAt(title, null, week(3)).total)
        assertEquals(24, airingAt(noMalTotal, weekly(1..12, total = 24), week(3)).total)
        assertEquals(24, airingAt(noMalTotal, AiringSchedule(emptyList(), 24), week(3)).total)
        assertNull(airingAt(noMalTotal, weekly(1..12, total = null), week(3)).total)
        assertNull(airingAt(noMalTotal, null, week(3)).total)
    }

    @Test
    fun `A3 a title MAL marks finished has aired completely, whatever the schedule says`() {
        assertEquals(Airing(AiredCount.Exact(12), 12, null, null), at(finished, null, week(3)))
        assertEquals(Airing(AiredCount.Exact(12), 12, null, null), at(finished, weekly(1..12), week(3)))
        assertEquals(Airing(AiredCount.Exact(12), 12, null, null), at(finished, weekly(1..8), week(30)))
        assertEquals(Airing(AiredCount.Exact(12), 12, null, week(11)), at(finished, weekly(1..12), week(11)))
        assertEquals(week(11), airingAt(finished, weekly(1..12), week(3)).changesAt)
        assertEquals(Airing(AiredCount.Exact(12), 12, null, null), at(finished, weekly(13..24), week(30)))
    }

    @Test
    fun `A3 the clamp, the aired count stops at the total and no next episode lies beyond it`() {
        val recap = weekly(1..13, total = 13)
        assertEquals(exact(11, 12, next = 12), at(title, recap, week(10)))
        assertEquals(exact(12, 12, next = null), at(title, recap, week(11)))
        assertEquals(exact(12, 12, next = null), at(title, recap, week(12)))
        assertEquals(exact(12, 12, next = null), at(title, recap, week(40)))
        val ownTotal = title.copy(malEpisodes = null)
        assertEquals(exact(12, 12, next = null), at(ownTotal, weekly(1..13, total = 12), week(12)))
        assertEquals(AiredCount.Estimated(12), airingAt(title, null, week(40)).aired)
    }

    @Test
    fun `A3 an airing status the app does not know is read as airing`() {
        val unknown = title.copy(airingStatus = AiringStatus.Unrecognized("on_hiatus"))
        val schedules = listOf(null, weekly(1..12), weekly(1..6), weekly(13..24), weekly(1..6, total = 30))
        for (schedule in schedules) {
            for (n in listOf(-1, 0, 3, 9, 11, 40)) {
                assertEquals("$schedule at week $n", airingAt(title, schedule, week(n)), airingAt(unknown, schedule, week(n)))
            }
        }
        assertNotEquals(airingAt(title, null, week(3)), airingAt(finished, null, week(3)))
    }

    @Test
    fun `A4 a schedule whose numbers run past MAL's total is not used`() {
        val sequel = weekly(13..24, from = 0, total = 24)
        val result = at(title, sequel, week(2))
        assertEquals(Airing(AiredCount.Estimated(3), 12, null, week(2), Inexact.NumberedDifferently), result)
        assertEquals(result, at(title, AiringSchedule(listOf(episode(1), episode(2), episode(3), episode(14)), 12), week(2)))
        assertEquals(Inexact.NumberedDifferently, airingAt(title, weekly(13..24, from = 0), week(-1)).inexact)
        assertEquals(Inexact.NumberedDifferently, airingAt(title, weekly(13..24, from = 0), week(40)).inexact)
        assertNull(airingAt(title, weekly(1..13), week(2)).inexact)
    }

    @Test
    fun `A4 a schedule that reaches a number MAL's start date cannot have reached is not used`() {
        val long = title.copy(malEpisodes = 24)
        val unknownTotal = title.copy(malEpisodes = null)
        val sequel = weekly(13..24, from = 0, total = null)
        assertEquals(Airing(AiredCount.Estimated(3), 24, null, week(2), Inexact.NumberedDifferently), at(long, sequel, week(2)))
        assertEquals(Airing(AiredCount.Estimated(3), null, null, week(2), Inexact.NumberedDifferently), at(unknownTotal, sequel, week(2)))
        // Its total is not used either.
        assertNull(airingAt(unknownTotal, weekly(13..24, from = 0, total = 24), week(2)).total)
        // What is stored may begin later than episode 1 when a weekly title could have reached it.
        assertEquals(exact(9, 24, next = 10), at(long, weekly(8..12, total = null), week(8)))
        // A premiere of four episodes at once, and one more than that.
        assertNull(airingAt(long, weekly(4..12, from = 0, total = null), week(2)).inexact)
        assertEquals(Inexact.NumberedDifferently, airingAt(long, weekly(5..12, from = 0, total = null), week(2)).inexact)
        // A long run gains one more for every ten weeks.
        assertNull(airingAt(unknownTotal, weekly(1115..1120, from = 1010, total = null), week(1012)).inexact)
        assertEquals(
            Inexact.NumberedDifferently,
            airingAt(unknownTotal, weekly(1116..1120, from = 1010, total = null), week(1012)).inexact,
        )
    }

    @Test
    fun `A4 the start of a partial date is the first day it names, and no date tests nothing`() {
        val sequel = weekly(13..24, from = 0, total = null)

        fun reason(date: PartialDate?) = airingAt(title.copy(malEpisodes = 24, startDate = date), sequel, week(2)).inexact
        assertEquals(Inexact.NumberedDifferently, reason(PartialDate.Month(YearMonth.of(2026, 1))))
        assertEquals(Inexact.NumberedDifferently, reason(PartialDate.OnlyYear(Year.of(2026))))
        assertNull(reason(PartialDate.OnlyYear(Year.of(2025))))
        assertNull(reason(null))
    }

    @Test
    fun `A4 a schedule that does not begin at episode 1 is not used while none of it has aired`() {
        val undated = title.copy(malEpisodes = 24, startDate = null)
        val later = AiringSchedule(listOf(episode(2), episode(3)), 24)
        assertEquals(Airing(AiredCount.Unknown, 24, null, null, Inexact.NumberedDifferently), at(undated, later, week(0)))
        assertEquals(week(1), airingAt(undated, later, week(0)).changesAt)
        assertEquals(exact(2, 24, next = 3), at(undated, later, week(1)))
    }

    @Test
    fun `A5 totals that differ by more than one episode make the schedule's count an estimate`() {
        for (total in listOf(10, 14, 24)) {
            assertEquals(
                Airing(AiredCount.Estimated(4), 12, null, week(3), Inexact.TotalsDisagree),
                at(title, weekly(1..8, total = total), week(3)),
            )
        }
        for (total in listOf(11, 12, 13, null)) {
            assertEquals(exact(4, 12, next = 5), at(title, weekly(1..8, total = total), week(3)))
        }
        assertEquals(
            Airing(AiredCount.Estimated(0), 12, null, null, Inexact.TotalsDisagree),
            at(title, weekly(1..8, total = 24), week(-1)),
        )
    }

    @Test
    fun `a schedule on a break gives an exact count with no next episode`() {
        val onBreak = weekly(1..6)
        assertEquals(exact(6, 12, next = null), at(title, onBreak, week(5)))
        assertEquals(exact(6, 12, next = null), at(title, onBreak, week(9).minusNanos(1)))
        assertEquals(week(9), airingAt(title, onBreak, week(5)).changesAt)
        // A break with the date of its end is exact however long it lasts.
        val returning = AiringSchedule(onBreak.episodes + ScheduledEpisode(7, week(30)), 12)
        assertEquals(Airing(AiredCount.Exact(6), 12, ScheduledEpisode(7, week(30)), week(5)), at(title, returning, week(29)))
    }

    @Test
    fun `A5 a schedule with nothing coming for four weeks has stopped, and its last count is an estimate`() {
        val stopped = weekly(1..6)
        assertEquals(Duration.ofDays(28), SCHEDULE_STOPPED_AFTER)
        val estimate = Airing(AiredCount.Estimated(6), 12, null, week(5), Inexact.ScheduleStopped)
        assertEquals(estimate, at(title, stopped, week(9)))
        // Never the weekly estimate, which would be 12 by then.
        assertEquals(estimate, at(title, stopped, week(40)))
        assertEquals(AiredCount.Estimated(12), airingAt(title, null, week(40)).aired)
        assertEquals(estimate, at(notYetAired, stopped, week(9)))
        // A title whose every episode has aired has not stopped, and neither has one MAL marks finished.
        assertEquals(exact(12, 12, next = null), at(title, weekly(1..12), week(40)))
        assertEquals(exact(6, 6, next = null), at(title.copy(malEpisodes = null), weekly(1..6, total = 6), week(40)))
        assertNull(airingAt(finished.copy(malEpisodes = null), weekly(1..6, total = null), week(40)).inexact)
    }

    @Test
    fun `A6 the weekly estimate counts MAL's slot from its start date`() {
        fun estimated(
            count: Int,
            latest: Instant?,
        ) = Airing(AiredCount.Estimated(count), 12, null, latest, Inexact.NoSchedule)
        assertEquals(estimated(0, null), at(title, null, week(0).minusNanos(1)))
        assertEquals(estimated(1, week(0)), at(title, null, week(0)))
        assertEquals(estimated(3, week(2)), at(title, null, week(3).minusNanos(1)))
        assertEquals(estimated(4, week(3)), at(title, AiringSchedule(emptyList(), 12), week(3)))
        // MAL's status is not asked: its date says what its status may not say yet.
        assertEquals(estimated(4, week(3)), at(notYetAired, null, week(3)))
        // A start date on another weekday counts from the first slot after it.
        val wednesday = title.copy(startDate = PartialDate.Day(start.minusDays(3)))
        assertEquals(estimated(1, week(0)), at(wednesday, null, week(0)))
        assertEquals(estimated(0, null), at(wednesday, null, week(0).minusNanos(1)))
    }

    @Test
    fun `A6 without a full start date, a weekday and a time there is no estimate`() {
        val without =
            listOf(
                noSlot,
                title.copy(broadcast = Broadcast(DayOfWeek.SATURDAY, null)),
                title.copy(startDate = PartialDate.Month(YearMonth.of(2026, 1))),
                title.copy(startDate = PartialDate.OnlyYear(Year.of(2026))),
                title.copy(startDate = null),
            )
        for (anime in without) {
            assertEquals(Airing(AiredCount.Unknown, 12, null, null, Inexact.NoSchedule), at(anime, null, week(3)))
            assertEquals(
                Airing(AiredCount.Estimated(0), 12, null, null, Inexact.NoSchedule),
                at(anime.copy(airingStatus = AiringStatus.NotYetAired), null, week(3)),
            )
        }
    }

    @Test
    fun `A6 the estimate cannot run away, it stops at the total and without one after thirteen weeks`() {
        assertEquals(AiredCount.Estimated(12), airingAt(title, null, week(11)).aired)
        assertEquals(AiredCount.Estimated(12), airingAt(title, null, week(500)).aired)
        assertEquals(week(11), airingAt(title, null, week(500)).latestAiredAt)
        val noTotal = title.copy(malEpisodes = null)
        assertEquals(AiredCount.Estimated(13), airingAt(noTotal, null, week(13).minusNanos(1)).aired)
        assertEquals(Airing(AiredCount.Unknown, null, null, null, Inexact.NoSchedule), at(noTotal, null, week(13)))
        assertEquals(Airing(AiredCount.Unknown, null, null, null, Inexact.NoSchedule), at(noTotal, null, week(500)))
        val notStarted = noTotal.copy(airingStatus = AiringStatus.NotYetAired)
        assertEquals(Airing(AiredCount.Estimated(0), null, null, null, Inexact.NoSchedule), at(notStarted, null, week(13)))
    }

    @Test
    fun `A6 a count of 0 is exact only when a stored schedule puts episode 1 in the future`() {
        val before = week(0).minus(hour)
        assertEquals(exact(0, 12, next = 1), at(notYetAired, weekly(1..12), before))
        assertEquals(exact(0, 12, next = 1), at(title, weekly(1..12), before))
        for (anime in listOf(title, notYetAired, noSlot.copy(airingStatus = AiringStatus.NotYetAired))) {
            for (schedule in listOf(null, AiringSchedule(emptyList(), 12), weekly(13..24, from = 0), weekly(2..12))) {
                val result = airingAt(anime, schedule, before)
                if ((result.aired as? AiredCount.Known)?.value == 0) assertEquals(AiredCount.Estimated(0), result.aired)
            }
        }
        assertEquals(AiredCount.Estimated(0), airingAt(notYetAired, null, before).aired)
    }

    @Test
    fun `each reason for a count that is not exact is told from the others`() {
        val reasons =
            mapOf(
                Inexact.NoSchedule to airingAt(title, null, week(3)),
                Inexact.NumberedDifferently to airingAt(title, weekly(13..24, from = 0), week(3)),
                Inexact.TotalsDisagree to airingAt(title, weekly(1..8, total = 24), week(3)),
                Inexact.ScheduleStopped to airingAt(title, weekly(1..2), week(6)),
            )
        assertEquals(Inexact.entries.toSet(), reasons.keys)
        reasons.forEach { (reason, result) ->
            assertEquals(reason, result.inexact)
            assertTrue(reason.name, result.aired !is AiredCount.Exact)
        }
        val exact = airingAt(title, weekly(1..12), week(3))
        assertNull(exact.inexact)
        assertTrue(exact.aired is AiredCount.Exact)
        assertEquals(Inexact.NoSchedule, airingAt(noSlot, null, week(3)).inexact)
        assertEquals(Inexact.NumberedDifferently, airingAt(noSlot, weekly(13..24, from = 0), week(3)).inexact)
        assertEquals(AiredCount.Unknown, airingAt(noSlot, weekly(13..24, from = 0), week(3)).aired)
    }

    @Test
    fun `a count that is not exact has no next episode`() {
        val coming = AiringSchedule(weekly(1..8).episodes, 24)
        assertNull(airingAt(title, coming, week(3)).nextEpisode)
        assertNull(airingAt(title, null, week(3)).nextEpisode)
        assertNull(airingAt(title, weekly(13..24, from = 0), week(3)).nextEpisode)
    }

    private fun watching(
        watched: Int,
        status: ListStatus = ListStatus.Watching,
    ) = ListValues(status, watched, null, null, null)

    private fun shown(
        anime: Anime,
        values: ListValues?,
        schedule: AiringSchedule?,
        now: Instant,
    ) = titleAiring(anime, values, schedule, now, FixtureTime.zone)

    @Test
    fun `A7 an estimate marks the aired count and to watch alike, and an unknown count shows no number`() {
        val exact = shown(title, watching(2), weekly(1..12), week(3)).phase
        assertEquals(AiringPhase.Aired(AiredCount.Exact(4), AiredCount.Exact(2), episode(5)), exact)
        for (schedule in listOf(null, weekly(13..24, from = 0), weekly(1..8, total = 24))) {
            val phase = shown(title, watching(2), schedule, week(3)).phase
            assertEquals("$schedule", AiringPhase.Aired(AiredCount.Estimated(4), AiredCount.Estimated(2), null), phase)
        }
        val stopped = shown(title, watching(2), weekly(1..4), week(9)).phase
        assertEquals(AiringPhase.Aired(AiredCount.Estimated(4), AiredCount.Estimated(2), null), stopped)
        assertEquals(AiringPhase.CountUnknown, shown(noSlot, watching(2), null, week(3)).phase)
    }

    @Test
    fun `A8 to watch is for a watching title that still airs and has a known count, and is never negative`() {
        fun toWatch(
            anime: Anime,
            values: ListValues?,
            schedule: AiringSchedule? = weekly(1..12),
        ) = (shown(anime, values, schedule, week(3)).phase as? AiringPhase.Aired)?.toWatch
        assertEquals(AiredCount.Exact(2), toWatch(title, watching(2)))
        assertEquals(AiredCount.Estimated(2), toWatch(title, watching(2), schedule = null))
        for (status in ListStatus.entries - ListStatus.Watching) assertNull(toWatch(title, watching(2, status)))
        assertNull(toWatch(title, null))
        assertNull(toWatch(finished, watching(2)))
        assertNull(shown(finished, watching(2), weekly(1..12), week(3)).phase)
        assertNull(toWatch(noSlot, watching(2), schedule = null))
        assertNull(toWatch(title, watching(4)))
        assertNull(toWatch(title, watching(9)))
    }

    @Test
    fun `A1 one result holds the count, the total, what is shown and the keys of Up next`() {
        val schedule = weekly(1..12)
        val now = week(3)
        val today = now.atZone(FixtureTime.zone).toLocalDate()
        val behind = shown(title, watching(2), schedule, now)
        assertEquals(airingAt(title, schedule, now), behind.airing)
        assertEquals(12, behind.total)
        assertEquals(UpNext.ToWatch(week(3)), behind.upNext)
        assertEquals(behind, titleAiring(title, watching(2), airingAt(title, schedule, now), today))
        assertEquals(UpNext.Coming(week(4)), shown(title, watching(4), schedule, now).upNext)
        assertEquals(UpNext.Coming(week(0)), shown(notYetAired, watching(0, ListStatus.Planned), schedule, week(-1)).upNext)
        assertEquals(
            AiringPhase.NotYetAired(Premiere.At(week(0))),
            shown(notYetAired, watching(0, ListStatus.Planned), schedule, week(-1)).phase,
        )
        assertEquals(UpNext.Rest, shown(title, watching(6), weekly(1..6), now.plus(Duration.ofDays(21))).upNext)
        assertEquals(UpNext.Rest, shown(title, watching(4), null, now).upNext)
        assertEquals(UpNext.ToWatch(week(3)), shown(title, watching(2), null, now).upNext)
        assertEquals(UpNext.Rest, shown(finished, watching(2), schedule, now).upNext)
        val untracked = titleAiring(title.copy(malEpisodes = null), watching(2), null, today)
        assertEquals(TitleAiring(null, null, AiringPhase.CountUnknown, UpNext.Rest), untracked)
    }

    // Every fixture title and the cases above, walked from one named instant to the next for a year.
    @Test
    fun `A2 a result holds until the instant it names, and every change is at one`() {
        val set = Fixtures.reference
        val cases =
            set.anime.map { it to set.schedules[it.id] } +
                listOf(title, finished, notYetAired, noSlot, title.copy(malEpisodes = null)).flatMap { anime ->
                    listOf(null, weekly(1..12), weekly(1..6), weekly(1..13, total = 13), weekly(13..24, from = 0), weekly(1..8, total = 24))
                        .map { anime to it }
                }
        val from = week(-2)
        val until = from.plus(Duration.ofDays(365))
        var changed = 0
        for ((anime, schedule) in cases) {
            var now = from
            var steps = 0
            while (now.isBefore(until)) {
                val result = airingAt(anime, schedule, now)
                val name = "${anime.titles.romaji} with $schedule at $now"
                val changesAt = result.changesAt
                val end = changesAt ?: until
                assertTrue(name, end.isAfter(now))
                var sample = now.plus(hour)
                while (sample.isBefore(end) && sample.isBefore(until)) {
                    assertEquals(name, result, airingAt(anime, schedule, sample))
                    sample = sample.plus(Duration.ofHours(7))
                }
                if (changesAt != null) {
                    assertEquals(name, result, airingAt(anime, schedule, changesAt.minusNanos(1)))
                    if (result.timeless() != airingAt(anime, schedule, changesAt).timeless()) changed++
                }
                now = end
                steps++
            }
            assertTrue(anime.titles.romaji, steps < 120)
        }
        assertTrue(changed > 300)
    }
}
