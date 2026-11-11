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

class AiringPhaseTest {
    private val today = LocalDate.of(2026, 1, 14)
    private val aired = Instant.parse("2026-01-10T14:30:00Z")
    private val next = ScheduledEpisode(15, Instant.parse("2026-01-17T14:30:00Z"))
    private val first = ScheduledEpisode(1, Instant.parse("2026-01-17T14:30:00Z"))

    private val airingTitle = FixtureAnime.exactToWatch
    private val notYetAired =
        airingTitle.copy(
            airingStatus = AiringStatus.NotYetAired,
            startDate = PartialDate.Day(LocalDate.of(2026, 1, 17)),
        )

    private fun watching(
        anime: Anime,
        watched: Int,
        status: ListStatus = ListStatus.Watching,
    ) = ListEntry(anime, ListValues(status, watched, null, null, null), Instant.EPOCH)

    @Test
    fun `an exact count with episodes to watch has both and the next episode`() {
        val airing = Airing(AiredCount.Exact(14), 24, next, aired)
        assertEquals(
            AiringPhase.Aired(AiredCount.Exact(14), AiredCount.Exact(2), next),
            airingPhase(airingTitle, watching(airingTitle, 12), airing, today),
        )
    }

    @Test
    fun `nothing to watch, another status or not on the list leaves out to watch`() {
        val airing = Airing(AiredCount.Exact(14), 24, next, aired)
        val expected = AiringPhase.Aired(AiredCount.Exact(14), null, next)
        assertEquals(expected, airingPhase(airingTitle, watching(airingTitle, 14), airing, today))
        assertEquals(expected, airingPhase(airingTitle, watching(airingTitle, 3, ListStatus.OnHold), airing, today))
        assertEquals(expected, airingPhase(airingTitle, null, airing, today))
    }

    @Test
    fun `an estimated count has an estimated to watch and no next episode`() {
        val airing = Airing(AiredCount.Estimated(10), 12, null, aired)
        assertEquals(
            AiringPhase.Aired(AiredCount.Estimated(10), AiredCount.Estimated(3), null),
            airingPhase(airingTitle, watching(airingTitle, 7), airing, today),
        )
    }

    @Test
    fun `an airing title with no known count says only that it airs`() {
        assertEquals(AiringPhase.CountUnknown, airingPhase(airingTitle, watching(airingTitle, 3), null, today))
        assertEquals(AiringPhase.CountUnknown, airingPhase(airingTitle, null, Airing(AiredCount.Unknown, null, null, null), today))
    }

    @Test
    fun `once episode 1 has aired the block shows the count, even while MAL still says not yet aired`() {
        val airing = Airing(AiredCount.Exact(1), 12, ScheduledEpisode(2, next.airsAt), aired)
        assertEquals(
            AiringPhase.Aired(AiredCount.Exact(1), AiredCount.Exact(1), ScheduledEpisode(2, next.airsAt)),
            airingPhase(notYetAired, watching(notYetAired, 0), airing, today),
        )
    }

    @Test
    fun `before episode 1 the block shows the premiere, whatever MAL's status`() {
        val airing = Airing(AiredCount.Exact(0), 12, first, null)
        assertEquals(AiringPhase.NotYetAired(Premiere.At(first.airsAt)), airingPhase(notYetAired, null, airing, today))
        assertEquals(AiringPhase.NotYetAired(Premiere.At(first.airsAt)), airingPhase(airingTitle, null, airing, today))
        assertEquals(
            AiringPhase.NotYetAired(Premiere.OnDay(LocalDate.of(2026, 1, 17))),
            airingPhase(notYetAired, watching(notYetAired, 0, ListStatus.Planned), null, today),
        )
    }

    @Test
    fun `a finished title shows no airing block`() {
        val finished = airingTitle.copy(airingStatus = AiringStatus.Finished)
        assertNull(airingPhase(finished, watching(finished, 12), Airing(AiredCount.Exact(24), 24, null, aired), today))
        assertNull(airingPhase(FixtureAnime.film, null, null, today))
    }
}
