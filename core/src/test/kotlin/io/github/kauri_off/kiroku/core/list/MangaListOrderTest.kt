package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MangaListOrderTest {
    private val status = MangaReadingStatus("reading")
    private val old = Instant.parse("2025-01-01T00:00:00Z")
    private val recent = Instant.parse("2026-01-01T00:00:00Z")
    private val entries =
        listOf(
            MangaListEntry(MangaId(3), Titles("Beta", "Zulu"), status, updatedAt = recent, score = 9),
            MangaListEntry(MangaId(4), Titles("Aoi", "Beta"), status, updatedAt = old, score = 5),
            MangaListEntry(MangaId(2), Titles("Aoi", "Beta"), status, updatedAt = old, score = 5),
            MangaListEntry(MangaId(1), Titles("Aardvark", "Alpha"), status, explicitlyUnrated = true),
        )

    @Test
    fun `missing dates and scores go last in both directions with title and id ties`() {
        for (sort in listOf(ListSort.LastUpdated, ListSort.Score)) {
            assertEquals(listOf(3L, 2L, 4L, 1L), ids(sort, SortDirection.Descending))
            assertEquals(listOf(2L, 4L, 3L, 1L), ids(sort, SortDirection.Ascending))
        }
    }

    @Test
    fun `title uses the selected language and ids stay ascending for equal titles`() {
        val titles =
            entries.map {
                when (it.id.value) {
                    1L -> it.copy(titles = Titles("Z", "Ōkami"))
                    2L -> it.copy(titles = Titles("B", "akari"))
                    4L -> it.copy(titles = Titles("A", "Akari"))
                    else -> it
                }
            }
        val sort = TabSort(ListSort.Title, SortDirection.Descending)
        assertEquals(listOf(3L, 1L, 2L, 4L), orderManga(titles, sort, TitleLanguage.English).map { it.id.value })
        assertEquals(listOf(1L, 3L, 2L, 4L), orderManga(titles, sort, TitleLanguage.Romaji).map { it.id.value })
    }

    private fun ids(
        sort: ListSort,
        direction: SortDirection,
    ) = orderManga(entries, TabSort(sort, direction), TitleLanguage.Romaji).map { it.id.value }
}
