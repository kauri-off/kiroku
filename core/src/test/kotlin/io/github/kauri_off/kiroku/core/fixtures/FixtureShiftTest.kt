package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class FixtureShiftTest {
    private val reference = Fixtures.reference

    @Test
    fun `screenshots use the fixed reference clock in the fixtures' own zone`() {
        assertEquals(FixtureTime.reference, reference.now)
        assertEquals(FixtureTime.zone, reference.clock.zone)
    }

    @Test
    fun `the reference clock gives the reference set`() {
        assertSame(Fixtures.reference.anime, Fixtures.at(FixtureTime.clock).anime)
        assertEquals(FixtureTime.reference, Fixtures.at(FixtureTime.clock).now)
    }

    @Test
    fun `the data depends on the instant, not on the zone of the clock`() {
        val instant = Instant.parse("2026-11-10T09:30:00Z")
        val moscow = Fixtures.at(Clock.fixed(instant, FixtureTime.zone))
        val auckland = Fixtures.at(Clock.fixed(instant, ZoneId.of("Pacific/Auckland")))
        assertEquals(moscow.now, auckland.now)
        assertEquals(moscow.copy(clock = FixtureTime.clock), auckland.copy(clock = FixtureTime.clock))
    }

    @Test
    fun `the set's clock lags the given one by less than a week, before and after the reference`() {
        (FixtureSets.shiftClocks.values + extraClocks.values).forEach { clock ->
            val lag = Duration.between(Fixtures.at(clock).now, clock.instant())
            assertTrue(lag.toString(), !lag.isNegative && lag < Duration.ofDays(7))
        }
    }

    @Test
    fun `the set is shifted by the whole weeks that have passed and its clock lags by the remainder`() {
        val clock =
            Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), ZoneId.of("Asia/Novosibirsk"))
        val set = Fixtures.at(clock)
        val shift = Duration.between(FixtureTime.reference, set.now)
        assertEquals(0L, shift.toDays() % 7)
        assertEquals(0L, shift.toSecondsPart().toLong() + shift.toMinutesPart() + shift.toHoursPart())
        val lag = Duration.between(set.now, clock.instant())
        assertTrue(!lag.isNegative && lag < Duration.ofDays(7))
        assertEquals(clock.zone, set.clock.zone)
        val before = reference.entries.first().updatedAt
        assertEquals(before.plus(shift), set.entries.first().updatedAt)
    }

    @Test
    fun `the set's clock is the given clock less the part of a week`() {
        val base = Clock.offset(FixtureTime.clock, Duration.ofDays(30))
        val set = Fixtures.at(base)
        assertEquals(base.instant().minus(Duration.ofDays(2)), set.now)
    }

    @Test
    fun `weekdays and dates move together`() {
        FixtureSets.shiftClocks.values.forEach { clock ->
            val set = Fixtures.at(clock)
            assertNotEquals(reference.now, set.now)
            assertEquals(
                reference.now.atZone(FixtureTime.zone).dayOfWeek,
                set.now.atZone(FixtureTime.zone).dayOfWeek,
            )
            reference.anime.zip(set.anime).forEach { (before, after) ->
                val from = (before.startDate as? PartialDate.Day)?.date ?: return@forEach
                val to = (after.startDate as PartialDate.Day).date
                assertEquals(from.dayOfWeek, to.dayOfWeek)
            }
            assertEquals(set.entries.size, reference.entries.size)
        }
    }

    private val extraClocks: Map<String, Clock> =
        mapOf(
            "60 weeks earlier" to Clock.offset(FixtureTime.clock, Duration.ofDays(-60 * 7 - 3).minusHours(7)),
            "2026-11-10" to Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), FixtureTime.zone),
        )

    private val setsWithPast: List<Pair<String, FixtureSet>> =
        FixtureSets.all + extraClocks.map { (name, clock) -> name to Fixtures.at(clock) }

    @Test
    fun `every season agrees with its title's start date`() {
        setsWithPast.forEach { (name, set) ->
            set.details.values.forEach { details ->
                val start = (set.anime(details.id).startDate as? PartialDate.Day)?.date
                assertEquals("$name ${details.id}", start?.let(FixtureDetails::seasonOf), details.season)
            }
        }
    }

    @Test
    fun `every end date keeps its distance from the start date`() {
        setsWithPast.forEach { (name, set) ->
            set.details.values.forEach { details ->
                val before = reference.details.getValue(details.id)
                assertEquals("$name ${details.id}", span(reference, before), span(set, details))
            }
        }
    }

    private fun span(
        set: FixtureSet,
        details: AnimeDetails,
    ): Long? {
        val start = (set.anime(details.id).startDate as? PartialDate.Day)?.date ?: return null
        val end = (details.endDate as? PartialDate.Day)?.date ?: return null
        return ChronoUnit.DAYS.between(start, end)
    }

    @Test
    fun `the stored airing holds at the set's moment only, and the schedules give it later`() {
        val id = FixtureAnime.exactCaughtUp.id
        val later = reference.now.plus(Duration.ofHours(6))
        val airedLater =
            reference.schedules
                .getValue(id)
                .episodes
                .filter { !it.airsAt.isAfter(later) }
                .maxOf { it.number }
        assertEquals(AiredCount.Exact(8), reference.airing.getValue(id).aired)
        assertEquals(9, airedLater)
    }
}
