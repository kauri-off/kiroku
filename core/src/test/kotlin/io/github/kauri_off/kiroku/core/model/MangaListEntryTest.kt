package io.github.kauri_off.kiroku.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class MangaListEntryTest {
    @Test
    fun `membership and reading values must be representable before storage`() {
        val entry = MangaListEntry(MangaId(1), Titles("Narrow Roads"), MangaReadingStatus("reading"))

        assertThrows(IllegalArgumentException::class.java) { entry.copy(id = MangaId(0)) }
        assertThrows(IllegalArgumentException::class.java) { MangaReadingStatus(" ") }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(chaptersRead = -1) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(volumesRead = -1) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(score = 0) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(score = 11) }
    }
}
