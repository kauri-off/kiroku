package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListNumber
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

        assertEquals(entry.copy(score = null, volumesRead = 0, explicitlyUnrated = true), edit.appliedTo(entry))
        assertEquals(entry.copy(chaptersRead = Int.MAX_VALUE), MangaListEdit(chaptersRead = Int.MAX_VALUE).appliedTo(entry))
        assertTrue(MangaListEdit().isEmpty)
    }

    @Test
    fun `unknown values provide neither confirmation nor authority to retry`() {
        val unknown = MangaListEntry(MangaId(1), Titles("Title"), MangaReadingStatus("reading"))
        val unrated = unknown.copy(explicitlyUnrated = true)
        val clear = MangaListEdit(score = 0, chaptersRead = 0)
        val rate = MangaListEdit(score = 3)

        assertTrue(!clear.isPresentIn(unknown))
        assertTrue(!clear.hasEvidenceIn(unrated))
        assertTrue(clear.isPresentIn(unrated.copy(chaptersRead = 0)))
        assertEquals(MangaListEdit(), rate.remainingAfter(unknown, unrated))
        assertEquals(MangaListEdit(), rate.remainingAfter(unrated, unknown))
        assertEquals(rate, rate.remainingAfter(unrated, unrated))
    }

    @Test
    fun `display values without numerical proof cannot confirm or permit retry`() {
        val proven =
            MangaListEntry(MangaId(1), Titles("Title"), MangaReadingStatus("reading"), chaptersRead = 3, volumesRead = 2, score = 7)
        val repaired = proven.copy(unverifiedNumbers = MangaListNumber.entries.toSet())
        val same = MangaListEdit(chaptersRead = 3, volumesRead = 2, score = 7)
        val next = MangaListEdit(chaptersRead = 4, volumesRead = 3, score = 8)

        assertTrue(same.isPresentIn(proven))
        assertTrue(!same.isPresentIn(repaired))
        assertEquals(MangaListEdit(), next.remainingAfter(proven, repaired))
        assertEquals(MangaListEdit(), next.remainingAfter(repaired, proven))
        assertEquals(next, next.remainingAfter(proven, proven))
    }
}
