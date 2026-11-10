package io.github.kauri_off.kiroku.core.model

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ModelTest {
    private val titles = Titles(romaji = "Kagerou Toshokan", english = "The Heat Haze Library")

    private fun fails(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("accepted")
    }

    @Test
    fun `a missing title in the chosen language falls back to romaji`() {
        assertEquals("The Heat Haze Library", titles.preferred(TitleLanguage.English))
        assertEquals("Kagerou Toshokan", titles.preferred(TitleLanguage.Japanese))
        assertEquals("Kagerou Toshokan", titles.preferred(TitleLanguage.Romaji))
    }

    @Test
    fun `a blank title is refused`() {
        fails { Titles(romaji = " ") }
        fails { Titles(romaji = "Kagerou Toshokan", english = "") }
        fails { Titles(romaji = "Kagerou Toshokan", japanese = " ") }
        fails { Titles(romaji = "Kagerou Toshokan", synonyms = listOf("")) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a score is between one and ten`() {
        ListValues(ListStatus.Completed, 12, 11, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `watched episodes are never negative`() {
        ListValues(ListStatus.Watching, -1, null, null, null)
    }

    @Test
    fun `values from MAL outside their range are refused`() {
        fails { FixtureAnime.film.copy(meanScore = 10.5) }
        fails { FixtureAnime.film.copy(malEpisodes = 0) }
        fails { FixtureAnime.film.copy(rank = 0) }
        fails { StatusCounts(-1, 0, 0, 0, 0) }
    }

    @Test
    fun `status counts are read by list status`() {
        val counts = StatusCounts(watching = 1, completed = 2, onHold = 3, dropped = 4, planned = 5)
        assertEquals(listOf(1, 5, 2, 3, 4), ListStatus.entries.map { counts[it] })
        assertEquals(15, counts.total)
    }

    @Test
    fun `an unrecognised value from MAL reads as words`() {
        assertEquals("Light novel", malCodeAsText("light_novel"))
        assertEquals("Mixed media", malCodeAsText("mixed_media"))
    }

    private val today = LocalDate.of(2026, 1, 14)
    private val day = PartialDate.Day(today)
    private val earlier = PartialDate.Day(LocalDate.of(2025, 12, 1))

    @Test
    fun `W7 reaching MAL's total completes the title with today's finish date`() {
        val watching = ListValues(ListStatus.Watching, 11, 8, earlier, null)
        assertEquals(ListValues(ListStatus.Completed, 12, 8, earlier, day), watching.withWatched(12, 12, today))
        val onHold = ListValues(ListStatus.OnHold, 11, null, null, null)
        assertEquals(ListValues(ListStatus.Completed, 12, null, day, day), onHold.withWatched(12, 12, today))
    }

    @Test
    fun `W7 the first episode of a planned title sets watching and the start date`() {
        val planned = ListValues(ListStatus.Planned, 0, null, null, null)
        assertEquals(ListValues(ListStatus.Watching, 1, null, day, null), planned.withWatched(1, 12, today))
        val film = ListValues(ListStatus.Planned, 0, null, null, null)
        assertEquals(ListValues(ListStatus.Completed, 1, null, day, day), film.withWatched(1, 1, today))
    }

    @Test
    fun `W7 other changes keep the status and dates`() {
        val watching = ListValues(ListStatus.Watching, 5, null, earlier, null)
        assertEquals(watching.copy(watchedEpisodes = 6), watching.withWatched(6, null, today))
        assertEquals(watching.copy(watchedEpisodes = 4), watching.withWatched(4, 12, today))
        val completed = ListValues(ListStatus.Completed, 12, 9, earlier, earlier)
        assertEquals(completed, completed.withWatched(12, 12, today))
        fails { watching.withWatched(13, 12, today) }
    }

    @Test
    fun `the strip leaves out other, character and unknown relations, which see all keeps last`() {
        val unknown = RelationType.Unrecognized("adaptation_of_something")
        val related =
            listOf(
                RelatedAnime(FixtureAnime.relatedOther, RelationType.Other),
                RelatedAnime(FixtureAnime.relatedSideStorySpecial, RelationType.SideStory),
                RelatedAnime(FixtureAnime.relatedCharacter, unknown),
                RelatedAnime(FixtureAnime.relatedSideStoryOva, RelationType.SideStory),
                RelatedAnime(FixtureAnime.prequel, RelationType.Prequel),
            )
        assertEquals(
            listOf(FixtureAnime.prequel, FixtureAnime.relatedSideStoryOva, FixtureAnime.relatedSideStorySpecial),
            relatedStrip(related).map { it.anime },
        )
        assertEquals(
            listOf(RelationType.Prequel, RelationType.SideStory, RelationType.Other, unknown),
            relatedGroups(related).map { it.first },
        )
    }
}
