package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.Counted
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.UserStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class FixtureStatisticsTest {
    private fun rounded(
        value: Double,
        decimals: Int,
    ): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (value * factor).roundToLong() / factor
    }

    private fun computed(entries: List<ListEntry>): UserStatistics {
        val scores = entries.mapNotNull { it.values.score }
        return UserStatistics(
            statuses =
                StatusCounts(
                    watching = entries.count { it.status == ListStatus.Watching },
                    completed = entries.count { it.status == ListStatus.Completed },
                    onHold = entries.count { it.status == ListStatus.OnHold },
                    dropped = entries.count { it.status == ListStatus.Dropped },
                    planned = entries.count { it.status == ListStatus.Planned },
                ),
            daysWatched =
                rounded(
                    entries.sumOf { entry ->
                        entry.values.watchedEpisodes * (entry.anime.averageEpisodeDuration?.seconds ?: 0L)
                    } / 86_400.0,
                    1,
                ),
            meanScore = if (scores.isEmpty()) null else rounded(scores.average(), 2),
            scores = scores.groupingBy { it }.eachCount().toSortedMap(),
            genres =
                entries
                    .flatMap { it.anime.genres.orEmpty() }
                    .groupingBy { it }
                    .eachCount()
                    .map { (genre, count) -> Counted(genre, count) }
                    .sortedWith(compareByDescending<Counted<*>> { it.count }.thenBy { it.value.toString() }),
            mediaTypes =
                entries
                    .mapNotNull { it.anime.mediaType }
                    .groupingBy { it }
                    .eachCount()
                    .map { (type, count) -> Counted(type, count) }
                    .sortedWith(compareByDescending<Counted<*>> { it.count }.thenBy { it.value.toString() }),
            years = FixtureSet.startYears(entries),
        )
    }

    @Test
    fun `the demo user's statistics agree with the demo list`() {
        FixtureSets.all.forEach { (name, set) ->
            assertEquals(name, computed(set.entries), set.userStatistics)
        }
    }

    @Test
    fun `the large statistics agree with themselves`() {
        val statistics = Fixtures.reference.largeStatistics
        val total = statistics.statuses.total
        assertTrue(total > 10_000)
        assertEquals(total, statistics.mediaTypes.sumOf { it.count })
        assertEquals(total, statistics.years.values.sum())
        val scored = statistics.scores.values.sum()
        val mean =
            statistics.scores.entries
                .sumOf { it.key * it.value }
                .toDouble() / scored
        assertTrue(abs(mean - checkNotNull(statistics.meanScore)) < 0.005)
        assertTrue(statistics.genres.size > 20)
        assertEquals(statistics.genres.sortedByDescending { it.count }, statistics.genres)
    }

    @Test
    fun `the large statistics end in the year of the set`() {
        FixtureSets.all.forEach { (name, set) ->
            val newest =
                set.largeStatistics.years.keys
                    .max()
            assertEquals(name, set.now.atZone(FixtureTime.zone).year, newest)
        }
    }
}
