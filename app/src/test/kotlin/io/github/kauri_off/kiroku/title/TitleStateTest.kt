package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RELATED_STRIP_LIMIT
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.relatedStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
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
        val expected: Map<String, Pair<TitlePage, AiringPhase?>> =
            mapOf(
                "airing-on-list" to
                    (
                        TitleFixtures.page(FixtureAnime.exactToWatch) to
                            AiringPhase.Aired(AiredCount.Exact(14), AiredCount.Exact(2), ScheduledEpisode(15, japan("2026-01-17T23:30")))
                    ),
                "not-on-list" to
                    (
                        TitleFixtures.page(FixtureAnime.notOnListAiring) to
                            AiringPhase.Aired(AiredCount.Exact(7), null, ScheduledEpisode(8, japan("2026-01-18T22:00")))
                    ),
                "airing-estimated" to
                    (
                        TitleFixtures.page(FixtureAnime.estimatedToWatch) to
                            AiringPhase.Aired(AiredCount.Estimated(10), AiredCount.Estimated(3), null)
                    ),
                "airing-estimated-caught-up" to
                    (TitleFixtures.page(FixtureAnime.estimatedCaughtUp) to AiringPhase.Aired(AiredCount.Estimated(5), null, null)),
                "airing-unknown" to (TitleFixtures.page(FixtureAnime.airedUnknown) to AiringPhase.CountUnknown),
                "finished" to (TitleFixtures.page(FixtureAnime.finishedPartWay) to null),
                "film" to (TitleFixtures.page(FixtureAnime.film) to null),
                "not-yet-aired" to
                    (
                        TitleFixtures.page(FixtureAnime.premiereTimeSoon) to
                            AiringPhase.NotYetAired(Premiere.At(japan("2026-01-18T00:30")))
                    ),
                "not-yet-aired-date" to
                    (TitleFixtures.page(FixtureAnime.premiereDate) to AiringPhase.NotYetAired(Premiere.OnDay(LocalDate.of(2026, 4, 4)))),
                "not-yet-aired-month" to
                    (TitleFixtures.page(FixtureAnime.premiereMonth) to AiringPhase.NotYetAired(Premiere.InMonth(YearMonth.of(2026, 7)))),
                "not-yet-aired-year" to
                    (TitleFixtures.page(FixtureAnime.premiereYear) to AiringPhase.NotYetAired(Premiere.InYear(Year.of(2027)))),
                "not-yet-aired-nodate" to
                    (TitleFixtures.page(FixtureAnime.premiereUnannounced) to AiringPhase.NotYetAired(Premiere.Unannounced)),
                "no-related" to
                    (
                        TitleFixtures.page(FixtureAnime.exactCaughtUp) to
                            AiringPhase.Aired(AiredCount.Exact(8), null, ScheduledEpisode(9, japan("2026-01-15T00:00")))
                    ),
                "long-title" to
                    (
                        TitleFixtures.page(FixtureAnime.longTitle) to
                            AiringPhase.Aired(AiredCount.Exact(3), AiredCount.Exact(2), ScheduledEpisode(4, japan("2026-01-16T01:00")))
                    ),
                "no-score" to
                    (
                        TitleFixtures.page(FixtureAnime.premiereTimeLater) to
                            AiringPhase.NotYetAired(Premiere.At(japan("2026-01-28T21:00")))
                    ),
                "first-episode-aired" to
                    (
                        TitleFixtures.firstEpisodeAired.page to
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
        assertEquals(24, TitleFixtures.page(FixtureAnime.exactToWatch).total)
        assertEquals(null, TitleFixtures.page(FixtureAnime.airedUnknown).total)
        assertEquals(1, TitleFixtures.page(FixtureAnime.film).total)
    }

    @Test
    fun theStateCarriesTheClocksZone() {
        val state = TitleFixtures.page(FixtureAnime.exactToWatch)
        assertEquals(set.clock.zone, state.now.zone)
        assertEquals(set.now, state.now.toInstant())
    }

    @Test
    fun theStripIsCoresWithoutTheSequelAndSeeAllCoversTheRest() {
        val state = TitleFixtures.page(FixtureAnime.exactToWatch)
        val details = checkNotNull(state.details)
        val sequel = checkNotNull(state.sequel)
        assertEquals(relatedStrip(details.related - sequel.related), state.related.map { it.related })
        assertFalse(state.related.any { it.related.anime.id == sequel.related.anime.id })
        assertTrue(state.related.size <= RELATED_STRIP_LIMIT)
        assertTrue(state.moreRelated)
        state.related.forEach { assertEquals(set.entryFor(it.related.anime.id)?.status, it.status) }
        assertTrue(state.related.any { it.status != null })
        assertFalse(TitleFixtures.page(FixtureAnime.exactCaughtUp).moreRelated)
    }

    @Test
    fun theSequelRowHoldsTheDirectSequelWithItsListStatus() {
        val content = TitleFixtures.page(FixtureAnime.exactToWatch)
        assertEquals(
            FixtureAnime.premiereYear.id,
            content.sequel
                ?.related
                ?.anime
                ?.id,
        )
        val finished = TitleFixtures.page(FixtureAnime.prequel)
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
        assertEquals(null, TitleFixtures.page(FixtureAnime.premiereYear).sequel)
        assertEquals(null, TitleFixtures.page(FixtureAnime.notOnListAiring).sequel)
    }

    // The total is stated once: by the progress when the title is on the list, by the header otherwise.
    @Test
    fun theHeaderLeavesOutWhatThePageStatesElsewhere() {
        assertEquals(HeaderFacts(MediaType.Tv, 2025, null), TitleFixtures.page(FixtureAnime.exactToWatch).facts)
        val notOnList = TitleFixtures.page(FixtureAnime.notOnListAiring)
        assertEquals(null, notOnList.entry)
        assertEquals(notOnList.total, notOnList.facts.episodes)
        assertEquals(null, TitleFixtures.page(FixtureAnime.film).facts.episodes)
        assertEquals(null, TitleFixtures.page(FixtureAnime.premiereDate).facts.year)
        assertEquals(null, TitleFixtures.page(FixtureAnime.premiereMonth).facts.year)
        assertEquals(null, TitleFixtures.page(FixtureAnime.premiereYear).facts.year)
        assertEquals(2026, TitleFixtures.page(FixtureAnime.premiereTimeSoon).facts.year)
    }

    @Test
    fun anExpiredSessionShowsTheBannerInEveryState() {
        val page = TitleFixtures.page(FixtureAnime.exactToWatch)
        val states: (Session) -> List<TitleState> =
            { listOf(TitleState.Loading(it), TitleState.Failed(DataError.NoConnection, it), TitleState.Shown(page, it)) }
        states(Session.Expired(Fixtures.reference.session.signIn)).forEach { assertTrue("$it", it.sessionExpired) }
        states(set.session).forEach { assertFalse("$it", it.sessionExpired) }
        states(Session.SignedOut).forEach { assertFalse("$it", it.sessionExpired) }
    }

    @Test
    fun theCompletedCaseIsCompletedByCoresRule() {
        val page = TitleFixtures.completed.page
        assertEquals(ListStatus.Completed, page.entry?.status)
        assertEquals(page.total, page.entry?.watchedEpisodes)
    }

    // W9: the count has no ceiling, and passing MAL's total completes the title as reaching it does (W7).
    @Test
    fun aCountAboveTheTotalIsShownAsItIsAndCompletesTheTitle() {
        val page = TitleFixtures.aboveTotal.page
        assertEquals(12, page.total)
        assertEquals(14, page.entry?.watchedEpisodes)
        assertEquals(ListStatus.Completed, page.entry?.status)
    }

    // Review M1: the only related titles besides the sequel are relations the strip leaves out.
    @Test
    fun seeAllIsOfferedWheneverARelatedTitleIsOutsideTheSequelRowAndTheStrip() {
        val anime = FixtureAnime.exactToWatch
        val details = checkNotNull(set.details[anime.id])
        val sequel = RelatedAnime(FixtureAnime.premiereYear, RelationType.Sequel)
        val character = RelatedAnime(FixtureAnime.relatedCharacter, RelationType.Character)
        val other = RelatedAnime(FixtureAnime.relatedOther, RelationType.Other)
        val prequel = RelatedAnime(FixtureAnime.prequel, RelationType.Prequel)

        fun page(vararg related: RelatedAnime) = TitleFixtures.page(anime, details = details.copy(related = related.toList()))

        val sequelAndCharacter = page(sequel, character)
        assertEquals(emptyList<RelatedCard>(), sequelAndCharacter.related)
        assertTrue(sequelAndCharacter.moreRelated)
        assertTrue(page(character, other).moreRelated)
        assertTrue(page(prequel, other).moreRelated)
        assertFalse(page(sequel).moreRelated)
        assertFalse(page(sequel, prequel).moreRelated)
        assertFalse(page().moreRelated)
    }

    // The rule for the view model: data changes rebuild the page with TitlePage.of, and the moment is carried over.
    @Test
    fun aRebuiltPageDerivesEverythingAgainAndTheMomentOutlivesIt() {
        val anime = FixtureAnime.exactToWatch
        val entry = checkNotNull(set.entryFor(anime.id))
        var moment = TitleMoment().after(TitleEvent.ExpandSynopsis)
        val before = TitleState.Shown(TitleFixtures.page(anime, entry), set.session, moment)
        assertEquals(AiredCount.Exact(2), (before.page.airing as AiringPhase.Aired).toWatch)

        val caughtUp = before.copy(page = TitleFixtures.page(anime, entry.copy(values = entry.values.copy(watchedEpisodes = 14))))
        assertEquals(null, (caughtUp.page.airing as AiringPhase.Aired).toWatch)
        assertEquals(TitleMoment(synopsisExpanded = true), caughtUp.moment)

        val removed = caughtUp.copy(page = TitleFixtures.page(anime, entry = null))
        assertEquals(null, removed.page.entry)
        assertEquals(24, removed.page.facts.episodes)
        assertTrue(removed.moment.synopsisExpanded)

        moment = removed.moment.after(TitleEvent.StepWatched(1)).after(TitleEvent.SetWatched(3))
        assertEquals(TitleMoment(synopsisExpanded = true), moment)
        assertEquals(TitleMoment(), moment.after(TitleEvent.CollapseSynopsis))
    }

    // A page has no public copy, so a derived value cannot be patched apart from its source.
    @Test
    fun aPageCanOnlyBeBuiltFromItsSources() {
        val copies = TitlePage::class.java.methods.filter { it.name == "copy" || it.name.startsWith("copy$") }
        assertEquals(emptyList<Method>(), copies)
        assertTrue(TitlePage::class.java.constructors.all { it.isSynthetic })
    }

    @Test
    fun toWatchFollowsTheTitlesAiringStatusNotTheEntrysCopyOfIt() {
        val anime = FixtureAnime.exactToWatch
        val entry = checkNotNull(set.entryFor(anime.id))
        val stale = entry.copy(anime = anime.copy(airingStatus = AiringStatus.Finished))
        assertEquals(AiredCount.Exact(2), (TitleFixtures.page(anime, stale).airing as AiringPhase.Aired).toWatch)
    }
}
