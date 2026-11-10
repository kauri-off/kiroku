package io.github.kauri_off.kiroku.core.fixtures

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
    fun `the shift is deterministic for a given clock`() {
        FixtureSets.shiftClocks.values.forEach { clock ->
            assertEquals(Fixtures.at(clock), Fixtures.at(clock))
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
}
