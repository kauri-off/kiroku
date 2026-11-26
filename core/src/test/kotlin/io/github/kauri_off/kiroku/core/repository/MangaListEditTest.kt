package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaListEditTest {
    @Test
    fun `a pending edit applies only supplied values and keeps an unrecognized status`() {
        val entry =
            MangaListEntry(
                MangaId(1),
                Titles("Title"),
                MangaReadingStatus("new_status"),
                chaptersRead = 2,
                volumesRead = 3,
                score = 4,
            )
        val edit = MangaListEdit(score = 0, volumesRead = 0)

        assertEquals(entry.copy(score = null, volumesRead = 0), edit.appliedTo(entry))
        assertEquals(entry.copy(chaptersRead = Int.MAX_VALUE), MangaListEdit(chaptersRead = Int.MAX_VALUE).appliedTo(entry))
        assertTrue(MangaListEdit().isEmpty)
    }
}
