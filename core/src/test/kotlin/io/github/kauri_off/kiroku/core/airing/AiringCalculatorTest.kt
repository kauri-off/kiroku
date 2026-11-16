package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
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

    // The count's part of a result: without the instant it holds until and a premiere's date, beyond what every value must name.
    private fun Airing.timeless() = copy(changesAt = nextEpisode?.airsAt, premiere = premiere as? Premiere.At)

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
        // A schedule that counts a recap MAL does not is an estimate (A5), and it stops at MAL's total.
        val recap = weekly(1..13, total = 13)

        fun doubted(count: Int) = Airing(AiredCount.Estimated(count), 12, null, week(count - 1), Inexact.TotalsDisagree)
        assertEquals(doubted(11), at(title, recap, week(10)))
        assertEquals(doubted(12), at(title, recap, week(11)))
        assertEquals(doubted(12), at(title, recap, week(12)))
        assertEquals(doubted(12), at(title, recap, week(40)))
        // A schedule that runs past its own total, the only one known.
        val ownTotal = title.copy(malEpisodes = null)
        assertEquals(exact(11, 12, next = 12), at(ownTotal, weekly(1..13, total = 12), week(10)))
        assertEquals(exact(12, 12, next = null), at(ownTotal, weekly(1..13, total = 12), week(11)))
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
    fun `A4 a schedule that begins after episode 1 and runs past MAL's total is not used`() {
        val sequel = weekly(13..24, from = 0, total = 24)
        assertEquals(Airing(AiredCount.Estimated(3), 12, null, week(2), Inexact.NumberedDifferently), at(title, sequel, week(2)))
        assertEquals(Inexact.NumberedDifferently, airingAt(title, weekly(13..24, from = 0), week(-1)).inexact)
        assertEquals(Inexact.NumberedDifferently, airingAt(title, weekly(13..24, from = 0), week(40)).inexact)
        // Stored from episode 8, which a weekly title has reached by then: only the number past the total rejects it.
        assertEquals(Inexact.NumberedDifferently, airingAt(title, weekly(8..14, total = null), week(9)).inexact)
        assertNull(airingAt(title, weekly(8..12, total = null), week(9)).inexact)
        // One past the total may be a recap, and a schedule that begins at episode 1 is read: both are A5's doubt.
        assertEquals(Inexact.TotalsDisagree, airingAt(title, weekly(8..13, total = null), week(9)).inexact)
        assertEquals(Inexact.TotalsDisagree, airingAt(title, weekly(1..24, total = 24), week(2)).inexact)
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
    fun `A4 episodes that air before MAL's start date count as its first week`() {
        val long = title.copy(malEpisodes = 24)
        assertNull(airingAt(long, weekly(4..12, from = -2, total = null), week(2)).inexact)
        assertEquals(Inexact.NumberedDifferently, airingAt(long, weekly(5..12, from = -2, total = null), week(2)).inexact)
    }

    @Test
    fun `A4 the weeks are counted from the start date's midnight in Japan`() {
        val long = title.copy(malEpisodes = 24)
        // 08:00 in Japan two weeks after the start date is two weeks on, which allows episode 6.
        val morning =
            start
                .plusDays(14)
                .atTime(LocalTime.of(8, 0))
                .atZone(JAPAN)
                .toInstant()

        fun from(number: Int) = AiringSchedule(listOf(ScheduledEpisode(number, morning), ScheduledEpisode(number + 1, week(3))), null)
        assertNull(airingAt(long, from(6), week(2)).inexact)
        assertEquals(Inexact.NumberedDifferently, airingAt(long, from(7), week(2)).inexact)
    }

    @Test
    fun `A4 the start of a partial date is the last day it names, and no date tests nothing`() {
        val sequel = weekly(13..24, from = 0, total = null)

        fun reason(date: PartialDate?) = airingAt(title.copy(malEpisodes = 24, startDate = date), sequel, week(2)).inexact
        assertEquals(Inexact.NumberedDifferently, reason(PartialDate.Month(YearMonth.of(2026, 1))))
        assertEquals(Inexact.NumberedDifferently, reason(PartialDate.OnlyYear(Year.of(2026))))
        assertEquals(Inexact.NumberedDifferently, reason(PartialDate.OnlyYear(Year.of(2025))))
        assertNull(reason(PartialDate.OnlyYear(Year.of(2024))))
        assertNull(reason(null))
        // A sequel numbered on that begins in the middle of the only year MAL names.
        val undated = title.copy(malEpisodes = null, startDate = PartialDate.OnlyYear(Year.of(2026)))
        assertEquals(
            Airing(AiredCount.Unknown, null, null, null, Inexact.NumberedDifferently),
            at(undated, weekly(13..24, from = 26, total = null), week(28)),
        )
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
    fun `A5 totals that differ at all make the schedule's count an estimate`() {
        val doubted = Airing(AiredCount.Estimated(4), 12, null, week(3), Inexact.TotalsDisagree)
        for (total in listOf(10, 11, 13, 14, 24)) assertEquals(doubted, at(title, weekly(1..8, total = total), week(3)))
        for (total in listOf(12, null)) assertEquals(exact(4, 12, next = 5), at(title, weekly(1..8, total = total), week(3)))
        // A schedule that states no total has at least its highest number.
        assertEquals(doubted, at(title, weekly(1..13, total = null), week(3)))
        // A whole season stored against a smaller total on MAL: the schedule's count, which stops at MAL's total.
        val twoCours = weekly(1..24, total = 24)
        assertEquals(doubted, at(title, twoCours, week(3)))
        assertEquals(doubted, at(noSlot, twoCours, week(3)))
        assertEquals(Airing(AiredCount.Estimated(12), 12, null, week(11), Inexact.TotalsDisagree), at(title, twoCours, week(14)))
        assertEquals(
            Airing(AiredCount.Estimated(0), 12, null, null, Inexact.TotalsDisagree),
            at(title, weekly(1..8, total = 24), week(-1)),
        )
    }

    @Test
    fun `a numbering the source doubts is an estimate from then on, never below the other's highest aired, with no total of its own`() {
        val noTotal = title.copy(malEpisodes = null)
        // A second cour numbered from 1 again at week 20, after a first cour of 12 that the source holds apart.
        val second = weekly(1..13, from = 20, total = 13)
        val doubted = second.copy(doubtedOver = 12)
        assertEquals(AiredCount.Exact(3), airingAt(noTotal, second, week(22)).aired)

        // Until it stops by A5, four weeks after its last episode.
        for (week in -2..35) {
            val airing = airingAt(noTotal, doubted, week(week))
            assertEquals("week $week", AiredCount.Estimated(maxOf(12, (week - 19).coerceIn(0, 13))), airing.aired)
            assertEquals("week $week", Inexact.NumberingDoubted, airing.inexact)
            assertNull("week $week", airing.total)
            assertNull("week $week", airing.nextEpisode)
        }
        assertEquals(week(23), airingAt(noTotal, doubted, week(22)).changesAt)
        // MAL's total bounds it, and MAL's finished total stands.
        assertEquals(AiredCount.Estimated(12), airingAt(title, weekly(1..13, from = 20, total = 13).copy(doubtedOver = 20), week(22)).aired)
        assertEquals(AiredCount.Exact(12), airingAt(finished, doubted, week(22)).aired)
    }

    @Test
    fun `a doubted schedule without episode times is no lower than the other's highest aired either`() {
        val noTotal = title.copy(malEpisodes = null)
        val untimed = AiringSchedule(emptyList(), 13, doubtedOver = 12)
        assertEquals(
            Airing(AiredCount.Estimated(12), null, null, null, Inexact.NoSchedule),
            at(noTotal.copy(broadcast = null), untimed, week(22)),
        )
        assertEquals(AiredCount.Estimated(12), airingAt(noTotal, untimed, week(2)).aired)
        assertEquals(AiredCount.Estimated(13), airingAt(noTotal, untimed, week(12)).aired)
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
    fun `A6 a slot on the weekday before MAL's start date is that night's, not next week's`() {
        fun firstEpisode(
            day: DayOfWeek,
            time: LocalTime,
        ): Instant? {
            val anime = title.copy(broadcast = Broadcast(day, time))
            var changes = first.minus(Duration.ofDays(30))
            var steps = 0
            while (airingAt(anime, null, changes).aired == AiredCount.Estimated(0)) {
                val next = checkNotNull(airingAt(anime, null, changes).changesAt)
                assertTrue("$day $time at $changes", next.isAfter(changes) && ++steps <= 3)
                changes = next
            }
            assertEquals(AiredCount.Estimated(1), airingAt(anime, null, changes).aired)
            return changes
        }

        fun japan(
            date: LocalDate,
            time: LocalTime,
        ) = date.atTime(time).atZone(JAPAN).toInstant()
        // Past midnight it is written under the evening's weekday and falls on the start date.
        assertEquals(japan(start, LocalTime.of(0, 50)), firstEpisode(DayOfWeek.FRIDAY, LocalTime.of(0, 50)))
        // Before midnight it is the eve of the start date.
        assertEquals(japan(start.minusDays(1), LocalTime.of(23, 56)), firstEpisode(DayOfWeek.FRIDAY, LocalTime.of(23, 56)))
        assertEquals(japan(start.minusDays(1), LATE_NIGHT_UNTIL), firstEpisode(DayOfWeek.FRIDAY, LATE_NIGHT_UNTIL))
        // Any other weekday is the first one after the start date.
        assertEquals(japan(start.plusDays(5), LocalTime.of(0, 50)), firstEpisode(DayOfWeek.THURSDAY, LocalTime.of(0, 50)))
        assertEquals(japan(start, LocalTime.of(0, 50)), firstEpisode(DayOfWeek.SATURDAY, LocalTime.of(0, 50)))
    }

    @Test
    fun `A1 the premiere is in the result, a first episode's time or MAL's date, which stands through its last day in Japan`() {
        val before = week(-1)
        assertEquals(Premiere.At(week(0)), airingAt(notYetAired, weekly(1..12), before).premiere)
        assertEquals(week(0), airingAt(notYetAired, weekly(1..12), before).changesAt)
        assertNull(airingAt(notYetAired, weekly(1..12), week(0)).premiere)
        assertNull(airingAt(title, null, week(0)).premiere)
        assertNull(airingAt(noSlot, null, before).premiere)
        assertNull(airingAt(finished, null, before).premiere)

        fun japan(date: LocalDate) = date.atStartOfDay(JAPAN).toInstant()
        val dated =
            listOf(
                PartialDate.Day(start) to (Premiere.OnDay(start) to japan(start.plusDays(1))),
                PartialDate.Month(YearMonth.of(2026, 1)) to (Premiere.InMonth(YearMonth.of(2026, 1)) to japan(LocalDate.of(2026, 2, 1))),
                PartialDate.OnlyYear(Year.of(2026)) to (Premiere.InYear(Year.of(2026)) to japan(LocalDate.of(2027, 1, 1))),
            )
        for ((date, expected) in dated) {
            val (premiere, over) = expected
            val anime = notYetAired.copy(startDate = date, broadcast = null)
            assertEquals("$date", premiere, airingAt(anime, null, before).premiere)
            assertEquals("$date", over, airingAt(anime, null, before).changesAt)
            assertEquals("$date", premiere, airingAt(anime, null, over.minusNanos(1)).premiere)
            val after = airingAt(anime, null, over)
            assertEquals("$date", Premiere.Unannounced, after.premiere)
            assertNull("$date", after.changesAt)
        }
        // The day's end comes before a first slot later in the week, and the result names the earlier of the two.
        val thursday = notYetAired.copy(broadcast = Broadcast(DayOfWeek.THURSDAY, slot))
        assertEquals(japan(start.plusDays(1)), airingAt(thursday, null, before).changesAt)
        assertEquals(Premiere.Unannounced, airingAt(thursday, null, japan(start.plusDays(1))).premiere)
        assertEquals(Premiere.Unannounced, airingAt(notYetAired.copy(startDate = null, broadcast = null), null, before).premiere)
    }

    @Test
    fun `a title MAL marks finished with no total and a first episode to come has an exact 0 and its time, and shows no airing`() {
        val early = finished.copy(malEpisodes = null)
        val schedule = weekly(1..1, total = null)
        val result = airingAt(early, schedule, week(-1))
        assertEquals(Airing(AiredCount.Exact(0), null, episode(1), null), result)
        assertEquals(Premiere.At(week(0)), result.premiere)
        assertEquals(week(0), result.changesAt)
        val shown = titleAiring(early, watching(0), schedule, week(-1))
        assertNull(shown.phase)
        assertEquals(UpNext.Rest, shown.upNext)
        assertEquals(AiredCount.Exact(1), airingAt(early, schedule, week(0)).aired)
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
                Inexact.NumberingDoubted to airingAt(title, weekly(1..8).copy(doubtedOver = 2), week(3)),
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
    ) = titleAiring(anime, values, schedule, now)

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
        val behind = shown(title, watching(2), schedule, now)
        assertEquals(airingAt(title, schedule, now), behind.airing)
        assertEquals(12, behind.total)
        assertEquals(UpNext.ToWatch(week(3)), behind.upNext)
        assertEquals(behind, titleAiring(title, watching(2), airingAt(title, schedule, now), now))
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
        val untracked = titleAiring(title.copy(malEpisodes = null), watching(2), airing = null, now = now)
        assertEquals(TitleAiring(null, null, AiringPhase.CountUnknown, UpNext.Rest), untracked)
    }

    // Every fixture title and the cases above, walked from one named instant to the next for a year.
    @Test
    fun `A2 a result holds until the instant it names, and every change is at one`() {
        val set = Fixtures.reference
        val cases =
            set.anime.map { it to set.schedules[it.id] } +
                listOf(title, finished, notYetAired, noSlot).flatMap { listOf(it, it.copy(malEpisodes = null)) }.flatMap { anime ->
                    listOf(
                        null,
                        weekly(1..12),
                        weekly(1..12, total = null),
                        weekly(1..6),
                        weekly(1..13, total = 13),
                        weekly(13..24, from = 0),
                        weekly(1..8, total = 24),
                    ).map { anime to it }
                } +
                // A rejected schedule that is read once its first episode has aired.
                (title.copy(startDate = null, broadcast = null) to weekly(2..12))
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
