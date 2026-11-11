package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.RELATED_STRIP_LIMIT
import io.github.kauri_off.kiroku.core.model.relatedStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A1: the page shows :core's aired count, total, "to watch" and premiere, never values of its own.
class TitleStateTest {
    private val set = Fixtures.reference
    private val today = set.now.atZone(set.clock.zone).toLocalDate()

    @Test
    fun theAiringBlockHoldsCoresValuesForEveryFixture() {
        for (anime in set.anime) {
            val entry = set.entryFor(anime.id)
            val airing = set.airing[anime.id]
            val state = TitleFixtures.of(anime)
            assertEquals(anime.titles.romaji, episodeTotal(anime, airing), state.total)
            val premiere = premiereOf(anime, airing, today)
            when (val block = state.airing) {
                null -> assertTrue(anime.titles.romaji, premiere == null && anime.airingStatus != AiringStatus.Airing)
                is AiringBlock.Premieres -> assertEquals(anime.titles.romaji, premiere, block.premiere)
                AiringBlock.CountUnknown -> assertFalse(anime.titles.romaji, airing?.aired is AiredCount.Known)
                is AiringBlock.Aired -> {
                    assertNull(premiere)
                    assertEquals(anime.titles.romaji, airing?.aired, block.aired)
                    assertEquals(anime.titles.romaji, episodeTotal(anime, airing), block.total)
                    assertEquals(anime.titles.romaji, entry?.let { toWatch(it, airing) }?.takeIf { it.value > 0 }, block.toWatch)
                    assertEquals(anime.titles.romaji, airing?.nextEpisode, block.next)
                }
            }
        }
    }

    @Test
    fun theCasesShowTheirAiringForms() {
        val toWatch = TitleFixtures.of(FixtureAnime.exactToWatch).airing as AiringBlock.Aired
        assertEquals(AiredCount.Exact(2), toWatch.toWatch)
        val estimated = TitleFixtures.of(FixtureAnime.estimatedToWatch).airing as AiringBlock.Aired
        assertEquals(AiredCount.Estimated(3), estimated.toWatch)
        assertNull((TitleFixtures.of(FixtureAnime.estimatedCaughtUp).airing as AiringBlock.Aired).toWatch)
        assertNull((TitleFixtures.of(FixtureAnime.notOnListAiring).airing as AiringBlock.Aired).toWatch)
        assertEquals(AiringBlock.CountUnknown, TitleFixtures.of(FixtureAnime.airedUnknown).airing)
        assertNull(TitleFixtures.of(FixtureAnime.finishedPartWay).airing)
        assertNull(TitleFixtures.of(FixtureAnime.film).airing)
    }

    @Test
    fun theStripIsCoresAndSeeAllCoversTheRest() {
        val state = TitleFixtures.of(FixtureAnime.exactToWatch)
        val details = checkNotNull(state.details)
        assertEquals(relatedStrip(details.related), state.related.map { it.related })
        assertTrue(state.related.size <= RELATED_STRIP_LIMIT)
        assertTrue(state.moreRelated)
        state.related.forEach { assertEquals(set.entryFor(it.related.anime.id)?.status, it.status) }
        assertTrue(state.related.count { it.status != null } >= 2)
        assertFalse(TitleFixtures.of(FixtureAnime.exactCaughtUp).moreRelated)
    }

    @Test
    fun theCompletedCaseIsCompletedByCoresRule() {
        val state = TitleFixtures.completed
        assertEquals(ListStatus.Completed, state.entry?.status)
        assertEquals(state.total, state.entry?.watchedEpisodes)
    }
}
