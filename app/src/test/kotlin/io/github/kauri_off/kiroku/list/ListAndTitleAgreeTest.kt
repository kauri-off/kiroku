package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.upNextGroup
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.title.TitlePage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock

// A title's row on the List and its title page say the same about what has aired, what is to watch and what airs next.
class ListAndTitleAgreeTest {
    private val set = Fixtures.reference
    private val clock = Clock.fixed(set.now, FixtureTime.zone)
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()

    @Test
    fun `every fixture and every grid case agrees`() {
        (set.entries + AiringGrid.entries).forEach { agree(it, set.airing[it.anime.id]) }
        AiringGrid.entries.forEach { entry -> AiringGrid.airings.forEach { agree(entry, it) } }
    }

    private fun agree(
        entry: ListEntry,
        airing: Airing?,
    ) {
        val name = "${entry.anime.titles.romaji} with $airing"
        val row = listRow(entry, airing, today, TitleLanguage.Romaji)
        val phase = TitlePage.of(entry.anime, entry, airing, null, emptyMap(), TitleLanguage.Romaji, clock).airing
        if (entry.status == ListStatus.Completed) {
            assertEquals(name, null, row.line3)
            return
        }
        val aired = phase as? AiringPhase.Aired
        assertEquals(name, (phase as? AiringPhase.NotYetAired)?.premiere, (row.line2 as? Line2.Premieres)?.premiere)
        val expected = aired?.toWatch?.let(Line3::ToWatch) ?: aired?.next?.let(Line3::NextEpisode)
        assertEquals(name, expected, row.line3)
        if (entry.status == ListStatus.Watching) {
            val shown =
                when {
                    row.line3 is Line3.ToWatch -> 0
                    row.line3 is Line3.NextEpisode || (row.line2 as? Line2.Premieres)?.premiere is Premiere.At -> 1
                    else -> 2
                }
            assertEquals("Up next, $name", shown, upNextGroup(phase))
        }
    }
}
