package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRowTest {
    private val set = Fixtures.reference
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()

    private fun row(
        anime: Anime,
        readOnly: Boolean = false,
    ): ListRow {
        val entry = set.entryFor(anime.id)!!
        return listRow(entry, set.airing[anime.id], today, TitleLanguage.Romaji, readOnly)
    }

    @Test
    fun `episodes to watch, exact`() {
        val row = row(FixtureAnime.exactToWatch)
        assertEquals(Line2.Progress(12, 24, AiredCount.Exact(14)), row.line2)
        assertEquals(Line3.ToWatch(AiredCount.Exact(2)), row.line3)
    }

    @Test
    fun `A7 an estimated count and its to watch figure stay estimated`() {
        val row = row(FixtureAnime.estimatedToWatch)
        assertEquals(Line2.Progress(7, 12, AiredCount.Estimated(10)), row.line2)
        assertEquals(Line3.ToWatch(AiredCount.Estimated(3)), row.line3)
    }

    @Test
    fun `nothing to watch shows the next episode, or nothing when it is estimated or unknown`() {
        assertEquals(
            Line3.NextEpisode(set.airing.getValue(FixtureAnime.exactCaughtUp.id).nextEpisode!!),
            row(FixtureAnime.exactCaughtUp).line3,
        )
        assertNull(row(FixtureAnime.exactOnBreak).line3)
        assertEquals(Line2.Progress(5, 12, AiredCount.Estimated(5)), row(FixtureAnime.estimatedCaughtUp).line2)
        assertNull(row(FixtureAnime.estimatedCaughtUp).line3)
    }

    @Test
    fun `a finished title and an unknown aired count show progress alone`() {
        assertEquals(Line2.Progress(10, 24, null), row(FixtureAnime.finishedPartWay).line2)
        assertNull(row(FixtureAnime.finishedPartWay).line3)
        assertEquals(Line2.Progress(212, null, null), row(FixtureAnime.airedUnknown).line2)
        assertNull(row(FixtureAnime.airedUnknown).line3)
    }

    @Test
    fun `an unknown total keeps the aired count`() {
        assertEquals(Line2.Progress(1118, null, AiredCount.Exact(1121)), row(FixtureAnime.unknownTotal).line2)
    }

    @Test
    fun `not yet aired shows the premiere by what is known`() {
        assertEquals(
            Premiere.At(
                set.airing
                    .getValue(FixtureAnime.premiereTimeSoon.id)
                    .nextEpisode!!
                    .airsAt,
            ),
            (row(FixtureAnime.premiereTimeSoon).line2 as Line2.Premieres).premiere,
        )
        assertTrue((row(FixtureAnime.premiereDate).line2 as Line2.Premieres).premiere is Premiere.OnDay)
        assertTrue((row(FixtureAnime.premiereMonth).line2 as Line2.Premieres).premiere is Premiere.InMonth)
        assertTrue((row(FixtureAnime.premiereYear).line2 as Line2.Premieres).premiere is Premiere.InYear)
        assertEquals(Premiere.Unannounced, (row(FixtureAnime.premiereUnannounced).line2 as Line2.Premieres).premiere)
        listOf(
            FixtureAnime.premiereTimeSoon,
            FixtureAnime.premiereDate,
            FixtureAnime.premiereUnannounced,
        ).forEach { assertNull(row(it).line3) }
    }

    @Test
    fun `the completed tab shows the score`() {
        assertEquals(Line2.Score(9), row(FixtureAnime.prequel).line2)
        assertEquals(Line2.Score(null), row(FixtureAnime.completedUnscored).line2)
        assertNull(row(FixtureAnime.prequel).line3)
    }

    @Test
    fun `A8 another tab never has episodes to watch, and shows the next episode instead`() {
        val onHold = row(FixtureAnime.onHoldAiring)
        assertEquals(Line2.Progress(15, 24, AiredCount.Exact(20)), onHold.line2)
        assertTrue(onHold.line3 is Line3.NextEpisode)
        assertTrue(set.entries.filter { it.status != ListStatus.Watching }.none { row(it.anime).line3 is Line3.ToWatch })
    }

    @Test
    fun `A1 every row shows the aired count, to watch and premiere that core computes`() {
        set.entries.forEach { entry ->
            val airing = set.airing[entry.anime.id]
            val row = row(entry.anime)
            val expectedToWatch = toWatch(entry, airing)?.takeIf { it.value > 0 }
            assertEquals(entry.anime.titles.romaji, expectedToWatch, (row.line3 as? Line3.ToWatch)?.count)
            (row.line2 as? Line2.Progress)?.aired?.let { assertEquals(entry.anime.titles.romaji, airing!!.aired, it) }
            (row.line2 as? Line2.Premieres)?.let { assertEquals(premiereOf(entry.anime, airing, today), it.premiere) }
        }
    }

    @Test
    fun `plus one is on every watching row and on no other`() {
        set.entries.forEach { entry -> assertEquals(entry.status == ListStatus.Watching, row(entry.anime).plusOne) }
    }

    @Test
    fun `another user's row has progress or score, no third line and no plus one`() {
        set.otherUserEntries.forEach { entry ->
            val row = listRow(entry, set.airing[entry.anime.id], today, TitleLanguage.Romaji, readOnly = true)
            assertNull(row.line3)
            assertFalse(row.plusOne)
            when (entry.status) {
                ListStatus.Completed -> assertEquals(Line2.Score(entry.values.score), row.line2)
                else -> assertEquals(Line2.Progress(entry.values.watchedEpisodes, entry.anime.malEpisodes, null), row.line2)
            }
        }
    }

    @Test
    fun `the title is in the chosen language`() {
        val entry = set.entryFor(FixtureAnime.exactToWatch.id)!!
        assertEquals(FixtureAnime.exactToWatch.titles.english, listRow(entry, null, today, TitleLanguage.English).title)
    }
}
