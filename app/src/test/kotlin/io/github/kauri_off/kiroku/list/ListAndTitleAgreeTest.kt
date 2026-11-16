package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Inexact
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.UpNext
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.plain
import io.github.kauri_off.kiroku.title.TitlePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

// A title's row and its title page say what :core's one result says, and "Up next" groups the row by what it shows.
class ListAndTitleAgreeTest {
    private val set = Fixtures.reference
    private val clock = Clock.fixed(set.now, FixtureTime.zone)
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()

    @Test
    fun `every fixture and every grid case agrees, on the calculator's results`() {
        set.entries.forEach { agree(it, airingAt(it.anime, set.schedules[it.anime.id], set.now)) }
        set.entries.forEach { agree(it, set.airing[it.anime.id]) }
        AiringGrid.entries.forEach { entry ->
            AiringGrid.schedules.forEach { agree(entry, airingAt(entry.anime, it, AiringGrid.now)) }
            AiringGrid.airings.forEach { agree(entry, it) }
        }
    }

    @Test
    fun `the grid holds every kind of count, each reason for one that is not exact, and a title with none`() {
        val airings = AiringGrid.airings
        assertTrue(null in airings)
        val computed = airings.filterNotNull()
        assertEquals(Inexact.entries.toSet(), computed.mapNotNull { it.inexact }.toSet())
        assertEquals(
            setOf(
                AiredCount.Unknown,
                AiredCount.Estimated(0),
                AiredCount.Estimated(2),
                AiredCount.Estimated(24),
                AiredCount.Exact(0),
                AiredCount.Exact(2),
                AiredCount.Exact(24),
            ),
            computed.map { it.aired }.toSet(),
        )
        assertTrue(computed.any { it.aired == AiredCount.Exact(2) && it.nextEpisode != null })
        assertTrue(computed.any { it.aired == AiredCount.Exact(2) && it.nextEpisode == null })
    }

    private fun agree(
        entry: ListEntry,
        airing: Airing?,
    ) {
        val name = "${entry.anime.titles.romaji} with $airing"
        val result = titleAiring(entry.anime, entry.values, airing, today)
        val row = listRow(entry, airing, today, TitleLanguage.Romaji)
        val page = TitlePage.of(entry.anime, entry, airing, null, emptyMap(), TitleLanguage.Romaji, clock)
        assertEquals(name, result.phase, page.airing)
        assertEquals(name, result.total, page.total)
        (row.line2 as? Line2.Progress)?.let { assertEquals(name, result.total, it.total) }
        if (entry.status == ListStatus.Completed) {
            assertEquals(name, null, row.line3)
            return
        }
        val phase = result.phase
        val aired = phase as? AiringPhase.Aired
        assertEquals(name, (phase as? AiringPhase.NotYetAired)?.premiere, (row.line2 as? Line2.Premieres)?.premiere)
        val expected = aired?.toWatch?.let(Line3::ToWatch) ?: aired?.next?.let(Line3::NextEpisode)
        assertEquals(name, expected, row.line3)
        if (entry.status == ListStatus.Watching) {
            val premiere = (row.line2 as? Line2.Premieres)?.premiere as? Premiere.At
            val shown: Any =
                when (val line3 = row.line3) {
                    is Line3.ToWatch -> listOf(airing?.latestAiredAt)
                    is Line3.NextEpisode -> line3.episode.airsAt
                    null -> premiere?.instant ?: UpNext.Rest
                }
            assertEquals("Up next, $name", shown, result.upNext.plain())
        }
    }
}
