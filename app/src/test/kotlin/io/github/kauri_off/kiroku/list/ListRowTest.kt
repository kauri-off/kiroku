package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.episodesToWatch
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.shownAired
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.Titles
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
        (set.entries + grid()).forEach { entry -> assertFromCore(entry, set.airing[entry.anime.id]) }
        grid().forEach { entry -> airings.forEach { airing -> assertFromCore(entry, airing) } }
    }

    @Test
    fun `A1 a title MAL still calls not yet aired, with episodes aired, shows them and its to watch line`() {
        val anime = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.NotYetAired)
        val entry = ListEntry(anime, ListValues(ListStatus.Watching, 0, null, null, null), set.now)
        val row = listRow(entry, Airing(AiredCount.Estimated(2), 24, null, set.now), today, TitleLanguage.Romaji)
        assertEquals(Line2.Progress(0, 24, AiredCount.Estimated(2)), row.line2)
        assertEquals(Line3.ToWatch(AiredCount.Estimated(2)), row.line3)
    }

    @Test
    fun `A1 up next puts first exactly the rows with a to watch line`() {
        airings.forEach { airing ->
            val entries = grid().filter { it.status == ListStatus.Watching }
            val ordered = order(entries, ListSort.UpNext, { airing }) { it.anime.titles.romaji }
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
        assertEquals(Line2.Progress(12, 12, null), listRow(atTotal, null, today, TitleLanguage.Romaji).line2)
        assertTrue(listRow(atTotal, null, today, TitleLanguage.Romaji).plusOne)
    }

    // Every list status, MAL airing status and watched count around an aired count of 2 out of 24.
    private fun grid(): List<ListEntry> =
        ListStatus.entries.flatMap { status ->
            AiringStatus.entries.flatMap { airingStatus ->
                (0..3).map { watched ->
                    val anime =
                        FixtureAnime.exactToWatch.copy(
                            id = AnimeId(GRID_ID + status.ordinal * 100L + airingStatus.ordinal * 10L + watched),
                            titles = Titles("$watched ${airingStatus.name} ${status.name}"),
                        )
                    ListEntry(anime.copy(airingStatus = airingStatus), ListValues(status, watched, null, null, null), set.now)
                }
            }
        }

    private val airings: List<Airing?> =
        listOf(
            null,
            Airing(AiredCount.Unknown, 24, null, null),
            Airing(AiredCount.Exact(0), 24, ScheduledEpisode(1, set.now.plusSeconds(DAY)), null),
            Airing(AiredCount.Exact(2), 24, ScheduledEpisode(3, set.now.plusSeconds(DAY)), set.now.minusSeconds(DAY)),
            Airing(AiredCount.Exact(2), 24, null, set.now.minusSeconds(DAY)),
            Airing(AiredCount.Estimated(2), 24, null, set.now.minusSeconds(DAY)),
        )

    private fun assertFromCore(
        entry: ListEntry,
        airing: Airing?,
    ) {
        val row = listRow(entry, airing, today, TitleLanguage.Romaji)
        val name = "${entry.anime.titles.romaji} with $airing"
        assertEquals(name, episodesToWatch(entry, airing), (row.line3 as? Line3.ToWatch)?.count)
        (row.line2 as? Line2.Progress)?.let { assertEquals(name, shownAired(entry.anime, airing), it.aired) }
        (row.line2 as? Line2.Premieres)?.let { assertEquals(name, premiereOf(entry.anime, airing, today), it.premiere) }
        if (row.line3 is Line3.ToWatch) assertTrue(name, (row.line2 as? Line2.Progress)?.aired != null)
    }

    private companion object {
        const val GRID_ID = 990_000L
        const val DAY = 86_400L
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
