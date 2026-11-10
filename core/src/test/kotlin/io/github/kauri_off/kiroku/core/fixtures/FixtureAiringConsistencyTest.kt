package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class FixtureAiringConsistencyTest {
    private val now = FixtureTime.now

    @Test
    fun `exact counts agree with the stored schedule at the fixture time`() {
        val exact = Fixtures.airing.filterValues { it.aired is AiredCount.Exact }
        assertTrue(exact.isNotEmpty())
        exact.forEach { (id, airing) ->
            val episodes = Fixtures.schedules.getValue(id).episodes
            val aired = episodes.filter { !it.airsAt.isAfter(now) }
            assertEquals(aired.maxOfOrNull { it.number } ?: 0, (airing.aired as AiredCount.Exact).value)
            assertEquals(episodes.filter { it.airsAt.isAfter(now) }.minByOrNull { it.number }, airing.nextEpisode)
            assertEquals(aired.maxByOrNull { it.number }?.airsAt, airing.latestAiredAt)
        }
    }

    @Test
    fun `estimated counts follow the weekly slot from the start date`() {
        val estimated = Fixtures.airing.filterValues { it.aired is AiredCount.Estimated }
        assertTrue(estimated.isNotEmpty())
        estimated.forEach { (id, airing) ->
            val anime = Fixtures.anime.single { it.id == id }
            val start = anime.startDate as PartialDate.Day
            val broadcast = checkNotNull(anime.broadcast)
            assertEquals(broadcast.dayOfWeek, start.date.dayOfWeek)
            assertNotNull(anime.episodes)
            assertTrue(id !in Fixtures.schedules)
            val first =
                start.date
                    .atTime(broadcast.japanTime)
                    .atZone(FixtureTime.japan)
                    .toInstant()
            val slots = generateSequence(first) { it.plus(Duration.ofDays(7)) }.takeWhile { !it.isAfter(now) }.toList()
            assertEquals(slots.size, (airing.aired as AiredCount.Estimated).value)
            assertEquals(slots.last(), airing.latestAiredAt)
        }
    }

    @Test
    fun `scheduled titles start on the date of their first episode in Japan`() {
        Fixtures.schedules.forEach { (id, schedule) ->
            val anime = Fixtures.anime.single { it.id == id }
            val first = schedule.episodes.firstOrNull { it.number == 1 } ?: return@forEach
            val start = anime.startDate as PartialDate.Day
            assertEquals(start.date, first.airsAt.atZone(FixtureTime.japan).toLocalDate())
        }
    }

    @Test
    fun `the fixture clock is fixed`() {
        assertEquals(FixtureTime.now, FixtureTime.clock.instant())
        assertEquals(FixtureTime.zone, FixtureTime.clock.zone)
    }
}
