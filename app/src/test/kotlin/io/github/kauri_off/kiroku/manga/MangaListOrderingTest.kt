package io.github.kauri_off.kiroku.manga

import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaListOrderingTest {
    @Test
    fun `a row joins at the end and the next pull orders the destination`() {
        val planned = MangaReadingStatus("plan_to_read")
        val joining = mangaEntry.copy(id = MangaId(10), titles = Titles("First alphabetically"))
        val existing = mangaEntry.copy(id = MangaId(20), titles = Titles("Second alphabetically"), status = planned)
        val ordering = MangaListOrdering()
        val after = listOf(joining.copy(status = planned), existing)
        val language = TitleLanguage.Romaji
        val choices = mapOf(planned to TabSort(ListSort.Title))
        ordering.entries(listOf(joining, existing), language, choices, 0, emptyList(), null)
        assertEquals(listOf(existing, after.first()), ordering.entries(after, language, choices, 0, emptyList(), null))
        assertEquals(after, ordering.entries(after, language, choices, 1, after, null))
    }

    @Test
    fun `a score edit updates the row without moving it until an ordering pass`() {
        val first = mangaEntry.copy(id = MangaId(10), score = 8)
        val second = mangaEntry.copy(id = MangaId(20), score = 6)
        val ordering = MangaListOrdering()
        val choices = mapOf(first.status to TabSort(ListSort.Score))
        val language = TitleLanguage.Romaji
        assertEquals(listOf(first, second), ordering.entries(listOf(second, first), language, choices, 0, emptyList(), null))
        val updated = first.copy(score = 2)
        assertEquals(listOf(updated, second), ordering.entries(listOf(second, updated), language, choices, 0, emptyList(), null))
        assertEquals(
            listOf(second, updated),
            ordering.entries(listOf(updated, second), language, choices, 1, listOf(updated, second), null),
        )
    }
}
