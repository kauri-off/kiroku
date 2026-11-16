package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Inexact
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Duration

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

    // What the fixture screens are drawn with: the aired count and the number of the next episode.
    @Test
    fun `the calculator gives every fixture title its case's count, at every shift`() {
        fun exact(
            aired: Int,
            next: Int?,
        ) = AiredCount.Exact(aired) to next

        fun estimated(aired: Int) = AiredCount.Estimated(aired) to null
        val expected =
            mapOf(
                FixtureAnime.exactToWatch to exact(14, next = 15),
                FixtureAnime.exactCaughtUp to exact(8, next = 9),
                FixtureAnime.exactCaughtUpLater to exact(3, next = 4),
                FixtureAnime.exactOnBreak to exact(6, next = null),
                FixtureAnime.unknownTotal to exact(1121, next = 1122),
                FixtureAnime.longTitle to exact(3, next = 4),
                FixtureAnime.premiereTimeSoon to exact(0, next = 1),
                FixtureAnime.premiereTimeLater to exact(0, next = 1),
                FixtureAnime.plannedAiring to exact(5, next = 6),
                FixtureAnime.onHoldAiring to exact(20, next = 21),
                FixtureAnime.notOnListAiring to exact(7, next = 8),
                FixtureAnime.estimatedToWatch to estimated(10),
                FixtureAnime.estimatedCaughtUp to estimated(5),
                FixtureAnime.droppedEstimated to estimated(9),
                FixtureAnime.airedUnknown to (AiredCount.Unknown to null),
                FixtureAnime.premiereDate to estimated(0),
                FixtureAnime.premiereMonth to estimated(0),
                FixtureAnime.premiereYear to estimated(0),
                FixtureAnime.premiereUnannounced to estimated(0),
            )
        assertEquals(
            expected.mapKeys { it.key.id },
            set.airing.mapValues { it.value.aired to it.value.nextEpisode?.number },
        )
        set.airing.forEach { (id, airing) -> assertEquals(airing, airingAt(set.anime(id), set.schedules[id], now)) }
    }

    // Each reason a count is not exact has its fixture titles, which the debug menu's airing counts are drawn with.
    @Test
    fun `the calculator gives every fixture title its case's reason, at every shift`() {
        val expected =
            mapOf(
                FixtureAnime.estimatedToWatch to Inexact.NumberedDifferently,
                FixtureAnime.estimatedCaughtUp to Inexact.TotalsDisagree,
                FixtureAnime.droppedEstimated to Inexact.NoSchedule,
                FixtureAnime.airedUnknown to Inexact.NoSchedule,
                FixtureAnime.premiereDate to Inexact.NoSchedule,
                FixtureAnime.premiereMonth to Inexact.NoSchedule,
                FixtureAnime.premiereYear to Inexact.NoSchedule,
                FixtureAnime.premiereUnannounced to Inexact.NoSchedule,
            )
        assertEquals(expected.mapKeys { it.key.id }, set.airing.filterValues { it.inexact != null }.mapValues { it.value.inexact })
    }

    // Their stored schedules change only the reason, and a wake once the total has aired: screens draw the weekly estimate.
    @Test
    fun `the titles whose schedule is not used or is doubted get what MAL's slot alone gives`() {
        for (anime in listOf(FixtureAnime.estimatedToWatch, FixtureAnime.estimatedCaughtUp).map { set.anime(it.id) }) {
            val schedule = set.schedules.getValue(anime.id)
            generateSequence(now.minus(Duration.ofDays(70))) { it.plus(Duration.ofHours(1)) }
                .takeWhile { it.isBefore(now.plus(Duration.ofDays(140))) }
                .forEach { at ->
                    val slot = airingAt(anime, null, at)
                    val withSchedule = airingAt(anime, schedule, at)
                    val running = (slot.aired as AiredCount.Known).value < checkNotNull(slot.total)
                    val changesAt = if (running) withSchedule.changesAt else slot.changesAt
                    assertEquals(
                        "${anime.titles.romaji} at $at",
                        slot,
                        withSchedule.copy(inexact = Inexact.NoSchedule, changesAt = changesAt),
                    )
                }
        }
    }

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
            if (malTotal != null && schedulesTotal != null) {
                assertEquals(id == FixtureAnime.estimatedCaughtUp.id, malTotal != schedulesTotal)
            }
        }
    }

    @Test
    fun `estimated counts follow the weekly slot from the start date`() {
        val estimated = set.airing.filterValues { ((it.aired as? AiredCount.Estimated)?.value ?: 0) > 0 }
        assertTrue(estimated.isNotEmpty())
        estimated.forEach { (id, airing) ->
            val anime = set.anime(id)
            val start = anime.startDate as PartialDate.Day
            val broadcast = checkNotNull(anime.broadcast)
            assertEquals(broadcast.dayOfWeek, start.date.dayOfWeek)
            assertNotNull(anime.malEpisodes)
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
