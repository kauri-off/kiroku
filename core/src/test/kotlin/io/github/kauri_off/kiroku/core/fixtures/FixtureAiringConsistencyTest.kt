package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Duration
import kotlin.math.abs

@RunWith(Parameterized::class)
class FixtureAiringConsistencyTest(
    @Suppress("unused") private val name: String,
    private val set: FixtureSet,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sets(): List<Array<Any>> = FixtureSets.parameters()
    }

    private val now = set.now

    @Test
    fun `exact counts agree with the stored schedule at the fixture time`() {
        val exact = set.airing.filterValues { it.aired is AiredCount.Exact }
        assertTrue(exact.isNotEmpty())
        exact.forEach { (id, airing) ->
            val episodes = set.schedules.getValue(id).episodes
            val aired = episodes.filter { !it.airsAt.isAfter(now) }
            assertEquals(aired.maxOfOrNull { it.number } ?: 0, (airing.aired as AiredCount.Exact).value)
            assertEquals(episodes.filter { it.airsAt.isAfter(now) }.minByOrNull { it.number }, airing.nextEpisode)
            assertEquals(aired.maxByOrNull { it.number }?.airsAt, airing.latestAiredAt)
        }
    }

    @Test
    fun `stored schedules agree with MAL's broadcast slot and total`() {
        set.schedules.forEach { (id, schedule) ->
            val anime = set.anime(id)
            val broadcast = checkNotNull(anime.broadcast)
            schedule.episodes.forEach { episode ->
                val japan = episode.airsAt.atZone(FixtureTime.japan)
                assertEquals(broadcast.dayOfWeek, japan.dayOfWeek)
                assertEquals(broadcast.japanTime, japan.toLocalTime())
            }
            val malTotal = anime.malEpisodes
            val schedulesTotal = schedule.totalEpisodes
            if (malTotal != null && schedulesTotal != null) assertTrue(abs(malTotal - schedulesTotal) <= 1)
        }
    }

    @Test
    fun `estimated counts follow the weekly slot from the start date`() {
        val estimated = set.airing.filterValues { it.aired is AiredCount.Estimated }
        assertTrue(estimated.isNotEmpty())
        estimated.forEach { (id, airing) ->
            val anime = set.anime(id)
            val start = anime.startDate as PartialDate.Day
            val broadcast = checkNotNull(anime.broadcast)
            assertEquals(broadcast.dayOfWeek, start.date.dayOfWeek)
            assertNotNull(anime.malEpisodes)
            assertTrue(id !in set.schedules)
            val first =
                start.date
                    .atTime(checkNotNull(broadcast.japanTime))
                    .atZone(FixtureTime.japan)
                    .toInstant()
            val slots = generateSequence(first) { it.plus(Duration.ofDays(7)) }.takeWhile { !it.isAfter(now) }.toList()
            assertEquals(slots.size, (airing.aired as AiredCount.Estimated).value)
            assertEquals(slots.last(), airing.latestAiredAt)
        }
    }

    @Test
    fun `scheduled titles start on the date of their first episode in Japan`() {
        set.schedules.forEach { (id, schedule) ->
            val anime = set.anime(id)
            val first = schedule.episodes.firstOrNull { it.number == 1 } ?: return@forEach
            val start = anime.startDate as PartialDate.Day
            assertEquals(start.date, first.airsAt.atZone(FixtureTime.japan).toLocalDate())
        }
    }
}
