package io.github.kauri_off.kiroku.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {
    private val titles = Titles(romaji = "Kagerou Toshokan", english = "The Heat Haze Library")

    @Test
    fun `a missing title in the chosen language falls back to romaji`() {
        assertEquals("The Heat Haze Library", titles.preferred(TitleLanguage.English))
        assertEquals("Kagerou Toshokan", titles.preferred(TitleLanguage.Japanese))
        assertEquals("Kagerou Toshokan", titles.preferred(TitleLanguage.Romaji))
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
    fun `status counts are read by list status`() {
        val counts = StatusCounts(watching = 1, completed = 2, onHold = 3, dropped = 4, planned = 5)
        assertEquals(listOf(1, 5, 2, 3, 4), ListStatus.entries.map { counts[it] })
        assertEquals(15, counts.total)
    }
}
