package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
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
        sort: ListSort? = null,
    ): ListRow {
        val entry = set.entryFor(anime.id)!!
        return listRow(entry, set.airing[anime.id], today, TitleLanguage.Romaji, readOnly, sort)
    }

    @Test
    fun `sorted by score or start date, line 2 is followed by that value, and an unscored row adds nothing`() {
        assertEquals(SortedBy.Score(8), row(FixtureAnime.exactToWatch, sort = ListSort.Score).sortedBy)
        assertNull(row(FixtureAnime.exactCaughtUp, sort = ListSort.Score).sortedBy)
        assertNull(row(FixtureAnime.premiereDate, sort = ListSort.Score).sortedBy)
        assertEquals(
            SortedBy.StartYear(2025),
            row(FixtureAnime.exactToWatch, sort = ListSort.StartDate).sortedBy,
        )
        assertEquals(
            SortedBy.StartYear(2024),
            row(FixtureAnime.film, sort = ListSort.StartDate).sortedBy,
        )
        assertNull(row(FixtureAnime.exactCaughtUp, readOnly = true, sort = ListSort.Score).sortedBy)
    }

    @Test
    fun `the sheet offers a sort's own direction first, as core defines it, and both directions`() {
        ListSort.entries.forEach { sort ->
            assertEquals(sort.name, sort.defaultDirection, sort.directions().firstOrNull()?.first)
            if (sort.defaultDirection !=
                null
            ) {
                assertEquals(sort.name, SortDirection.entries.toSet(), sort.directions().map { it.first }.toSet())
            }
        }
    }

    @Test
    fun `a row says what it is sorted by only once, and nothing when sorted by another key`() {
        assertNull(row(FixtureAnime.film, sort = ListSort.Score).sortedBy)
        assertEquals(Line2.Score(8), row(FixtureAnime.film, sort = ListSort.Score).line2)
        assertNull(row(FixtureAnime.premiereDate, sort = ListSort.StartDate).sortedBy)
        assertNull(row(FixtureAnime.premiereUnannounced, sort = ListSort.StartDate).sortedBy)
        listOf(ListSort.UpNext, ListSort.Title, ListSort.LastUpdated, null).forEach { sort ->
            assertNull(row(FixtureAnime.exactToWatch, sort = sort).sortedBy)
        }
    }

    @Test
    fun `episodes to watch, exact`() {
        val row = row(FixtureAnime.exactToWatch)
        assertEquals(Line2.Progress(12, 24), row.line2)
        assertEquals(Line3.ToWatch(AiredCount.Exact(2)), row.line3)
    }

    @Test
    fun `A7 an estimated count and its to watch figure stay estimated`() {
        val row = row(FixtureAnime.estimatedToWatch)
        assertEquals(Line2.Progress(7, 12), row.line2)
        assertEquals(Line3.ToWatch(AiredCount.Estimated(3)), row.line3)
    }

    @Test
    fun `nothing to watch shows the next episode, or nothing when it is estimated or unknown`() {
        assertEquals(
            Line3.NextEpisode(set.airing.getValue(FixtureAnime.exactCaughtUp.id).nextEpisode!!),
            row(FixtureAnime.exactCaughtUp).line3,
        )
        assertNull(row(FixtureAnime.exactOnBreak).line3)
        assertEquals(Line2.Progress(5, 12), row(FixtureAnime.estimatedCaughtUp).line2)
        assertNull(row(FixtureAnime.estimatedCaughtUp).line3)
    }

    @Test
    fun `a finished title and an unknown aired count show progress alone`() {
        assertEquals(Line2.Progress(10, 24), row(FixtureAnime.finishedPartWay).line2)
        assertNull(row(FixtureAnime.finishedPartWay).line3)
        assertEquals(Line2.Progress(212, null), row(FixtureAnime.airedUnknown).line2)
        assertNull(row(FixtureAnime.airedUnknown).line3)
    }

    @Test
    fun `an unknown total leaves the total out and keeps the to watch line`() {
        assertEquals(Line2.Progress(1118, null), row(FixtureAnime.unknownTotal).line2)
        assertEquals(Line3.ToWatch(AiredCount.Exact(3)), row(FixtureAnime.unknownTotal).line3)
    }

    @Test
    fun `W9 a count above the total and the aired count is shown as it is, with plus one and no next episode`() {
        val entry = set.entryFor(FixtureAnime.exactCaughtUp.id)!!.let { it.copy(values = it.values.copy(watchedEpisodes = 25)) }
        val airing = set.airing.getValue(entry.anime.id)
        val row = listRow(entry, airing, today, TitleLanguage.Romaji)
        assertEquals(Line2.Progress(25, episodeTotal(entry.anime, airing)), row.line2)
        assertTrue(25 > episodeTotal(entry.anime, airing)!!)
        assertTrue(25 >= airing.nextEpisode!!.number)
        assertEquals(null, row.line3)
        assertTrue(row.plusOne)
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
        assertEquals(Line2.Progress(15, 24), onHold.line2)
        assertTrue(onHold.line3 is Line3.NextEpisode)
        assertTrue(set.entries.filter { it.status != ListStatus.Watching }.none { row(it.anime).line3 is Line3.ToWatch })
    }

    @Test
    fun `A1 every row shows the total that core computes`() {
        (set.entries + grid()).forEach { entry -> assertTotalFromCore(entry, set.airing[entry.anime.id]) }
        grid().forEach { entry -> airings.forEach { airing -> assertTotalFromCore(entry, airing) } }
    }

    @Test
    fun `A1 a title MAL still calls not yet aired, with episodes aired, shows its to watch line`() {
        val anime = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.NotYetAired)
        val entry = ListEntry(anime, ListValues(ListStatus.Watching, 0, null, null, null), set.now)
        val row = listRow(entry, Airing(AiredCount.Estimated(2), 24, null, set.now), today, TitleLanguage.Romaji)
        assertEquals(Line2.Progress(0, 24), row.line2)
        assertEquals(Line3.ToWatch(AiredCount.Estimated(2)), row.line3)
    }

    @Test
    fun `A1 up next puts first exactly the rows with a to watch line`() {
        airings.forEach { airing ->
            val entries = grid().filter { it.status == ListStatus.Watching }
            val ordered = order(entries, ListSort.UpNext, { airing }, today) { it.anime.titles.romaji }
            val highlighted = ordered.map { listRow(it, airing, today, TitleLanguage.Romaji).line3 is Line3.ToWatch }
            assertEquals(airing.toString(), highlighted.sortedDescending(), highlighted)
        }
    }

    @Test
    fun `plus one is on every watching row and on no other, also at the total`() {
        set.entries.forEach { entry -> assertEquals(entry.status == ListStatus.Watching, row(entry.anime).plusOne) }
        grid().forEach { entry ->
            assertEquals(entry.status == ListStatus.Watching, listRow(entry, null, today, TitleLanguage.Romaji).plusOne)
        }
        val atTotal = set.entryFor(FixtureAnime.oneShortOfTotal.id)!!.let { it.copy(values = it.values.copy(watchedEpisodes = 12)) }
        assertEquals(Line2.Progress(12, 12), listRow(atTotal, null, today, TitleLanguage.Romaji).line2)
        assertTrue(listRow(atTotal, null, today, TitleLanguage.Romaji).plusOne)
    }

    private fun grid() = AiringGrid.entries

    private val airings = AiringGrid.airings

    private fun assertTotalFromCore(
        entry: ListEntry,
        airing: Airing?,
    ) {
        val row = listRow(entry, airing, today, TitleLanguage.Romaji)
        (row.line2 as? Line2.Progress)?.let {
            assertEquals("${entry.anime.titles.romaji} with $airing", episodeTotal(entry.anime, airing), it.total)
        }
    }

    @Test
    fun `another user's row has progress or score, no third line and no plus one`() {
        set.otherUserEntries.forEach { entry ->
            val row = listRow(entry, set.airing[entry.anime.id], today, TitleLanguage.Romaji, readOnly = true)
            assertNull(row.line3)
            assertFalse(row.plusOne)
            when (entry.status) {
                ListStatus.Completed -> assertEquals(Line2.Score(entry.values.score), row.line2)
                else -> assertEquals(Line2.Progress(entry.values.watchedEpisodes, entry.anime.malEpisodes), row.line2)
            }
        }
    }

    @Test
    fun `the title is in the chosen language`() {
        val entry = set.entryFor(FixtureAnime.exactToWatch.id)!!
        assertEquals(FixtureAnime.exactToWatch.titles.english, listRow(entry, null, today, TitleLanguage.English).title)
    }
}
