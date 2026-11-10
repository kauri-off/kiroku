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

    private fun entry(
        anime: Anime,
        status: ListStatus,
        watched: Int,
    ) = ListEntry(anime, ListValues(status, watched, null, null, null), Instant.EPOCH)

    private fun airing(aired: AiredCount) = Airing(aired, 24, null, null)

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

    @Test(expected = IllegalArgumentException::class)
    fun `A3 the aired count never exceeds a known total`() {
        Airing(AiredCount.Exact(25), 24, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a next episode comes only with an exact count`() {
        Airing(AiredCount.Estimated(3), 24, ScheduledEpisode(4, instant), null)
    }

    @Test
    fun `a known first episode time replaces the date`() {
        val anime = FixtureAnime.premiereDate
        val airing = Airing(AiredCount.Exact(0), 12, ScheduledEpisode(1, instant), null)
        assertEquals(Premiere.At(instant), premiereOf(anime, airing))
    }

    @Test
    fun `the premiere follows the precision of the date`() {
        val anime = FixtureAnime.premiereDate
        assertEquals(
            Premiere.OnDay(LocalDate.of(2026, 4, 4)),
            premiereOf(anime.copy(startDate = PartialDate.Day(LocalDate.of(2026, 4, 4))), null),
        )
        assertEquals(
            Premiere.InMonth(YearMonth.of(2026, 7)),
            premiereOf(anime.copy(startDate = PartialDate.Month(YearMonth.of(2026, 7))), null),
        )
        assertEquals(
            Premiere.InYear(Year.of(2027)),
            premiereOf(anime.copy(startDate = PartialDate.OnlyYear(Year.of(2027))), null),
        )
        assertEquals(Premiere.Unannounced, premiereOf(anime.copy(startDate = null), null))
    }

    @Test
    fun `only a title that has not aired has a premiere`() {
        assertNull(premiereOf(FixtureAnime.exactToWatch, null))
        assertNull(premiereOf(FixtureAnime.film, null))
    }
}
