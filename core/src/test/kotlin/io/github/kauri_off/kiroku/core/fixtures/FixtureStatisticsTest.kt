package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FixtureStatisticsTest {
    @Test
    fun `the demo user's hand-written statistics are the calculator's over the demo list`() {
        FixtureSets.all.forEach { (name, set) ->
            assertEquals(name, set.userStatistics, statisticsOf(set.entries))
        }
    }

    @Test
    fun `three weeks earlier, a title that started in a year's first three weeks counts in the year before`() {
        val set = FixtureSets.all.toMap().getValue("3 weeks earlier")

        // The reference's years, with 2026-01-18, 2025-01-01, 2024-01-10, 2020-01-11 and the year 2027 each a year earlier.
        assertEquals(
            mapOf(
                2004 to 1,
                2017 to 1,
                2018 to 1,
                2019 to 3,
                2021 to 1,
                2022 to 2,
                2023 to 3,
                2024 to 2,
                2025 to 12,
                2026 to 4,
            ),
            statisticsOf(set.entries).years,
        )
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
