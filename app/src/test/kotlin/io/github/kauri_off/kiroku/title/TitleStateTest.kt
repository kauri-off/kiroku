package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RELATED_STRIP_LIMIT
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.relatedStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class TitleStateTest {
    private val set = Fixtures.reference

    private fun japan(at: String) = FixtureTime.japan(at)

    // Each case's airing block, written out: what the page shows, not how it is computed.
    @Test
    fun everyCaseShowsItsAiringBlock() {
        val expected: Map<String, Pair<TitleState.Shown, AiringPhase?>> =
            mapOf(
                "airing-on-list" to
                    (
                        TitleFixtures.of(FixtureAnime.exactToWatch) to
                            AiringPhase.Aired(AiredCount.Exact(14), AiredCount.Exact(2), ScheduledEpisode(15, japan("2026-01-17T23:30")))
                    ),
                "not-on-list" to
                    (
                        TitleFixtures.of(FixtureAnime.notOnListAiring) to
                            AiringPhase.Aired(AiredCount.Exact(7), null, ScheduledEpisode(8, japan("2026-01-18T22:00")))
                    ),
                "airing-estimated" to
                    (
                        TitleFixtures.of(FixtureAnime.estimatedToWatch) to
                            AiringPhase.Aired(AiredCount.Estimated(10), AiredCount.Estimated(3), null)
                    ),
                "airing-estimated-caught-up" to
                    (TitleFixtures.of(FixtureAnime.estimatedCaughtUp) to AiringPhase.Aired(AiredCount.Estimated(5), null, null)),
                "airing-unknown" to (TitleFixtures.of(FixtureAnime.airedUnknown) to AiringPhase.CountUnknown),
                "finished" to (TitleFixtures.of(FixtureAnime.finishedPartWay) to null),
                "film" to (TitleFixtures.of(FixtureAnime.film) to null),
                "not-yet-aired" to
                    (
                        TitleFixtures.of(FixtureAnime.premiereTimeSoon) to
                            AiringPhase.NotYetAired(Premiere.At(japan("2026-01-18T00:30")))
                    ),
                "not-yet-aired-date" to
                    (TitleFixtures.of(FixtureAnime.premiereDate) to AiringPhase.NotYetAired(Premiere.OnDay(LocalDate.of(2026, 4, 4)))),
                "not-yet-aired-month" to
                    (TitleFixtures.of(FixtureAnime.premiereMonth) to AiringPhase.NotYetAired(Premiere.InMonth(YearMonth.of(2026, 7)))),
                "not-yet-aired-year" to
                    (TitleFixtures.of(FixtureAnime.premiereYear) to AiringPhase.NotYetAired(Premiere.InYear(Year.of(2027)))),
                "not-yet-aired-nodate" to
                    (TitleFixtures.of(FixtureAnime.premiereUnannounced) to AiringPhase.NotYetAired(Premiere.Unannounced)),
                "no-related" to
                    (
                        TitleFixtures.of(FixtureAnime.exactCaughtUp) to
                            AiringPhase.Aired(AiredCount.Exact(8), null, ScheduledEpisode(9, japan("2026-01-15T00:00")))
                    ),
                "long-title" to
                    (
                        TitleFixtures.of(FixtureAnime.longTitle) to
                            AiringPhase.Aired(AiredCount.Exact(3), AiredCount.Exact(2), ScheduledEpisode(4, japan("2026-01-16T01:00")))
                    ),
                "no-score" to
                    (
                        TitleFixtures.of(FixtureAnime.premiereTimeLater) to
                            AiringPhase.NotYetAired(Premiere.At(japan("2026-01-28T21:00")))
                    ),
                "first-episode-aired" to
                    (
                        TitleFixtures.firstEpisodeAired to
                            AiringPhase.Aired(
                                AiredCount.Exact(1),
                                AiredCount.Exact(1),
                                ScheduledEpisode(2, set.now.plus(Duration.ofDays(7)).minus(Duration.ofHours(1))),
                            )
                    ),
            )
        for ((case, pair) in expected) assertEquals(case, pair.second, pair.first.airing)
    }

    @Test
    fun theTotalIsMalsOrElseTheSchedules() {
        assertEquals(24, TitleFixtures.of(FixtureAnime.exactToWatch).total)
        assertEquals(null, TitleFixtures.of(FixtureAnime.airedUnknown).total)
        assertEquals(1, TitleFixtures.of(FixtureAnime.film).total)
    }

    @Test
    fun theStateCarriesTheClocksZone() {
        val state = TitleFixtures.of(FixtureAnime.exactToWatch)
        assertEquals(set.clock.zone, state.now.zone)
        assertEquals(set.now, state.now.toInstant())
    }

    @Test
    fun theStripIsCoresWithoutTheSequelAndSeeAllCoversTheRest() {
        val state = TitleFixtures.of(FixtureAnime.exactToWatch)
        val details = checkNotNull(state.details)
        val sequel = checkNotNull(state.sequel)
        assertEquals(relatedStrip(details.related - sequel.related), state.related.map { it.related })
        assertFalse(state.related.any { it.related.anime.id == sequel.related.anime.id })
        assertTrue(state.related.size <= RELATED_STRIP_LIMIT)
        assertTrue(state.moreRelated)
        state.related.forEach { assertEquals(set.entryFor(it.related.anime.id)?.status, it.status) }
        assertTrue(state.related.any { it.status != null })
        assertFalse(TitleFixtures.of(FixtureAnime.exactCaughtUp).moreRelated)
    }

    @Test
    fun theSequelRowHoldsTheDirectSequelWithItsListStatus() {
        val content = TitleFixtures.of(FixtureAnime.exactToWatch)
        assertEquals(
            FixtureAnime.premiereYear.id,
            content.sequel
                ?.related
                ?.anime
                ?.id,
        )
        val finished = TitleFixtures.of(FixtureAnime.prequel)
        assertEquals(
            FixtureAnime.exactToWatch.id,
            finished.sequel
                ?.related
                ?.anime
                ?.id,
        )
        assertEquals(ListStatus.Watching, finished.sequel?.status)
        assertTrue(finished.related.isEmpty())
        assertFalse(finished.moreRelated)
        assertEquals(null, TitleFixtures.of(FixtureAnime.premiereYear).sequel)
        assertEquals(null, TitleFixtures.of(FixtureAnime.notOnListAiring).sequel)
    }

    // The total is stated once: by the progress when the title is on the list, by the header otherwise.
    @Test
    fun theHeaderLeavesOutWhatThePageStatesElsewhere() {
        assertEquals(HeaderFacts(MediaType.Tv, 2025, null), TitleFixtures.of(FixtureAnime.exactToWatch).facts)
        val notOnList = TitleFixtures.of(FixtureAnime.notOnListAiring)
        assertEquals(null, notOnList.entry)
        assertEquals(notOnList.total, notOnList.facts.episodes)
        assertEquals(null, TitleFixtures.of(FixtureAnime.film).facts.episodes)
        assertEquals(null, TitleFixtures.of(FixtureAnime.premiereDate).facts.year)
        assertEquals(null, TitleFixtures.of(FixtureAnime.premiereMonth).facts.year)
        assertEquals(null, TitleFixtures.of(FixtureAnime.premiereYear).facts.year)
        assertEquals(2026, TitleFixtures.of(FixtureAnime.premiereTimeSoon).facts.year)
    }

    @Test
    fun anExpiredSessionShowsTheBanner() {
        assertTrue(TitleFixtures.of(FixtureAnime.exactToWatch, session = Session.Expired).sessionExpired)
        assertFalse(TitleFixtures.of(FixtureAnime.exactToWatch).sessionExpired)
        assertFalse(TitleFixtures.of(FixtureAnime.exactToWatch, session = Session.SignedOut).sessionExpired)
    }

    @Test
    fun theCompletedCaseIsCompletedByCoresRule() {
        val state = TitleFixtures.completed
        assertEquals(ListStatus.Completed, state.entry?.status)
        assertEquals(state.total, state.entry?.watchedEpisodes)
    }
}
