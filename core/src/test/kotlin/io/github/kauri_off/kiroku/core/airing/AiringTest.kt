package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class AiringTest {
    private val instant = Instant.parse("2026-01-17T14:30:00Z")

    private val today = LocalDate.of(2026, 1, 14)

    private fun entry(
        anime: Anime,
        status: ListStatus,
        watched: Int,
    ) = ListEntry(anime, ListValues(status, watched, null, null, null), Instant.EPOCH)

    private fun airing(aired: AiredCount) = Airing(aired, 24, null, null)

    private fun fails(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("accepted")
    }

    @Test
    fun `to watch is the aired count minus the watched episodes`() {
        val entry = entry(FixtureAnime.exactToWatch, ListStatus.Watching, 12)
        assertEquals(AiredCount.Exact(2), toWatch(entry, airing(AiredCount.Exact(14))))
        assertEquals(AiredCount.Estimated(2), toWatch(entry, airing(AiredCount.Estimated(14))))
    }

    @Test
    fun `A8 to watch exists only in watching`() {
        ListStatus.entries.filter { it != ListStatus.Watching }.forEach { status ->
            assertNull(toWatch(entry(FixtureAnime.exactToWatch, status, 12), airing(AiredCount.Exact(14))))
        }
    }

    @Test
    fun `A8 to watch does not exist for a finished title`() {
        val finished = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.Finished)
        assertNull(toWatch(entry(finished, ListStatus.Watching, 12), airing(AiredCount.Exact(14))))
    }

    @Test
    fun `A8 to watch does not exist for an unknown count`() {
        val entry = entry(FixtureAnime.exactToWatch, ListStatus.Watching, 12)
        assertNull(toWatch(entry, airing(AiredCount.Unknown)))
        assertNull(toWatch(entry, null))
    }

    @Test
    fun `A8 to watch is never negative`() {
        val entry = entry(FixtureAnime.exactToWatch, ListStatus.Watching, 16)
        assertEquals(AiredCount.Exact(0), toWatch(entry, airing(AiredCount.Exact(14))))
    }

    @Test
    fun `any non-negative count is accepted, whatever the aired count and the total, and leaves nothing to watch`() {
        val watching = ListValues(ListStatus.Watching, 12, null, null, null)
        val aired = airing(AiredCount.Exact(14))
        (0..COUNT_LIMIT).forEach { count ->
            val unknownTotal = watching.withWatched(count, malEpisodes = null, today)
            assertEquals(count, unknownTotal.watchedEpisodes)
            val toWatch = toWatch(ListEntry(FixtureAnime.exactToWatch, unknownTotal, Instant.EPOCH), aired)
            assertEquals(AiredCount.Exact(maxOf(14 - count, 0)), toWatch)
            val knownTotal = watching.withWatched(count, malEpisodes = 24, today)
            assertEquals(count, knownTotal.watchedEpisodes)
            assertEquals(if (count >= 24) ListStatus.Completed else ListStatus.Watching, knownTotal.status)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `A3 the aired count never exceeds a known total`() {
        Airing(AiredCount.Exact(25), 24, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a next episode comes only with an exact count`() {
        Airing(AiredCount.Estimated(3), 24, ScheduledEpisode(4, instant), null)
    }

    @Test
    fun `airing data that contradicts itself is refused`() {
        fails { Airing(AiredCount.Exact(4), 24, ScheduledEpisode(4, instant), instant) }
        fails { Airing(AiredCount.Exact(4), 12, ScheduledEpisode(13, instant), instant) }
        fails { Airing(AiredCount.Unknown, null, null, instant) }
        fails { Airing(AiredCount.Estimated(0), 12, null, instant) }
        fails { ScheduledEpisode(0, instant) }
        fails { Airing(AiredCount.Exact(3), 0, null, instant) }
    }

    @Test
    fun `a count of 0 is exact only when the schedule puts episode 1 in the future`() {
        fails { Airing(AiredCount.Exact(0), 12, null, null) }
        fails { Airing(AiredCount.Exact(0), 12, ScheduledEpisode(2, instant), null) }
        Airing(AiredCount.Exact(0), 12, ScheduledEpisode(1, instant), null)
        Airing(AiredCount.Estimated(0), 12, null, null)
    }

    @Test
    fun `A3 the total is MAL's, otherwise AniList's`() {
        val noMalTotal = FixtureAnime.exactToWatch.copy(malEpisodes = null)
        assertEquals(24, episodeTotal(noMalTotal, Airing(AiredCount.Exact(14), 24, null, instant)))
        assertEquals(24, episodeTotal(FixtureAnime.exactToWatch, null))
        assertNull(episodeTotal(noMalTotal, null))
        val malTwelve = FixtureAnime.exactToWatch.copy(malEpisodes = 12)
        assertEquals(12, episodeTotal(malTwelve, Airing(AiredCount.Exact(12), 13, null, instant)))
    }

    @Test
    fun `a next episode beyond the total is refused, so a recap counted by AniList must be clamped first`() {
        fails { Airing(AiredCount.Exact(12), 12, ScheduledEpisode(13, instant), instant) }
        Airing(AiredCount.Exact(12), 12, null, instant)
    }

    @Test
    fun `a known first episode time replaces the date`() {
        val anime = FixtureAnime.premiereDate
        val airing = Airing(AiredCount.Exact(0), 12, ScheduledEpisode(1, instant), null)
        assertEquals(Premiere.At(instant), premiereOf(anime, airing, today))
    }

    @Test
    fun `the premiere follows the precision of the date`() {
        val anime = FixtureAnime.premiereDate
        assertEquals(
            Premiere.OnDay(LocalDate.of(2026, 4, 4)),
            premiereOf(anime.copy(startDate = PartialDate.Day(LocalDate.of(2026, 4, 4))), null, today),
        )
        assertEquals(
            Premiere.InMonth(YearMonth.of(2026, 7)),
            premiereOf(anime.copy(startDate = PartialDate.Month(YearMonth.of(2026, 7))), null, today),
        )
        assertEquals(
            Premiere.InYear(Year.of(2027)),
            premiereOf(anime.copy(startDate = PartialDate.OnlyYear(Year.of(2027))), null, today),
        )
        assertEquals(Premiere.Unannounced, premiereOf(anime.copy(startDate = null), null, today))
    }

    @Test
    fun `the premiere follows the aired count when MAL already says airing`() {
        val anime = FixtureAnime.premiereTimeSoon.copy(airingStatus = AiringStatus.Airing)
        val airing = Airing(AiredCount.Exact(0), 12, ScheduledEpisode(1, instant), null)
        assertEquals(Premiere.At(instant), premiereOf(anime, airing, today))
        val estimated = Airing(AiredCount.Estimated(0), 12, null, null)
        assertEquals(Premiere.OnDay(LocalDate.of(2026, 1, 18)), premiereOf(anime, estimated, today))
    }

    @Test
    fun `there is no premiere once an episode has aired, even when MAL still says not yet aired`() {
        val anime = FixtureAnime.premiereTimeSoon
        assertEquals(AiringStatus.NotYetAired, anime.airingStatus)
        assertNull(premiereOf(anime, Airing(AiredCount.Exact(1), 12, ScheduledEpisode(2, instant), instant), today))
        assertNull(premiereOf(anime, Airing(AiredCount.Estimated(1), 12, null, instant), today))
    }

    @Test
    fun `MAL's status decides only when the count is unknown`() {
        val notYetAired = FixtureAnime.premiereDate
        assertEquals(Premiere.OnDay(LocalDate.of(2026, 4, 4)), premiereOf(notYetAired, null, today))
        assertEquals(
            Premiere.OnDay(LocalDate.of(2026, 4, 4)),
            premiereOf(notYetAired, Airing(AiredCount.Unknown, null, null, null), today),
        )
        assertNull(premiereOf(FixtureAnime.airedUnknown, Airing(AiredCount.Unknown, null, null, null), today))
    }

    @Test
    fun `only a title that has not aired has a premiere`() {
        assertNull(premiereOf(FixtureAnime.exactToWatch, null, today))
        assertNull(premiereOf(FixtureAnime.film, null, today))
    }

    @Test
    fun `a premiere whose whole period has passed is unannounced`() {
        val anime = FixtureAnime.premiereDate

        fun premiere(date: PartialDate) = premiereOf(anime.copy(startDate = date), null, today)
        assertEquals(Premiere.Unannounced, premiere(PartialDate.Day(LocalDate.of(2026, 1, 13))))
        assertEquals(Premiere.OnDay(today), premiere(PartialDate.Day(today)))
        assertEquals(Premiere.Unannounced, premiere(PartialDate.Month(YearMonth.of(2025, 12))))
        assertEquals(Premiere.InMonth(YearMonth.of(2026, 1)), premiere(PartialDate.Month(YearMonth.of(2026, 1))))
        assertEquals(Premiere.Unannounced, premiere(PartialDate.OnlyYear(Year.of(2025))))
        assertEquals(Premiere.InYear(Year.of(2026)), premiere(PartialDate.OnlyYear(Year.of(2026))))
    }

    private companion object {
        const val COUNT_LIMIT = 100
    }
}
