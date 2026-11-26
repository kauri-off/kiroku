package io.github.kauri_off.kiroku.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaEditSheetStateTest {
    private val existing = MangaEditFixtures.existing
    private val unknown = MangaEditFixtures.unknown

    @Test
    fun saveSendsOnlySemanticallyChangedValues() {
        assertNull(existing.saveChanges)
        assertNull(existing.copy(chapters = "012", volumes = "002").saveChanges)
        val changed = existing.copy(status = MangaEditStatus.Completed, chapters = "0", score = 0)
        assertEquals(MangaEditChanges(status = MangaEditStatus.Completed, chaptersRead = 0, score = 0), changed.saveChanges)
        assertEquals(
            MangaEditChanges(chaptersRead = Int.MAX_VALUE, volumesRead = 9000),
            existing.copy(chapters = Int.MAX_VALUE.toString(), volumes = "9000").saveChanges,
        )
    }

    @Test
    fun unknownBaselineValuesAreOmittedUntilExplicitlySet() {
        assertNull(unknown.status)
        assertNull(unknown.saveChanges)
        assertTrue(unknown.mayLeave)
        assertEquals(MangaEditChanges(chaptersRead = 55), unknown.copy(chapters = "55").saveChanges)
        assertEquals(MangaEditChanges(volumesRead = 0), unknown.copy(volumes = "0").saveChanges)
        assertEquals(MangaEditChanges(score = 0), unknown.copy(score = 0).saveChanges)
        assertEquals(MangaEditChanges(status = MangaEditStatus.Planned), unknown.copy(status = MangaEditStatus.Planned).saveChanges)
    }

    @Test
    fun anAlreadyUnratedScoreRemainsUnchanged() {
        val unrated = MangaEditSheetState(existing.title, existing.baseline.copy(score = 0))
        assertNull(unrated.after(MangaEditSheetEvent.ScoreChosen(0)).saveChanges)
        assertEquals(MangaEditChanges(score = 10), unrated.after(MangaEditSheetEvent.ScoreChosen(10)).saveChanges)
    }

    @Test
    fun eachInvalidCountBlocksAnOtherwiseValidSave() {
        for (text in listOf("", "-1", "+1", "1.5", " 2", "x", "2147483648")) {
            val changed = existing.copy(status = MangaEditStatus.Dropped)
            val invalidChapters = changed.copy(chapters = text)
            val invalidVolumes = changed.copy(volumes = text)
            assertTrue(text, invalidChapters.chaptersInvalid)
            assertTrue(text, invalidVolumes.volumesInvalid)
            assertNull(text, invalidChapters.saveChanges)
            assertNull(text, invalidVolumes.saveChanges)
        }
    }

    @Test
    fun anInvalidDraftStillNeedsDiscardConfirmation() {
        val invalid = existing.copy(chapters = "2147483648")
        val confirming = invalid.after(MangaEditSheetEvent.DismissAsked)
        assertTrue(confirming.confirmDiscard)
        assertFalse(confirming.mayLeave)
        assertEquals(invalid, confirming.after(MangaEditSheetEvent.KeepEditing))
        val closing = confirming.after(MangaEditSheetEvent.Discard)
        assertTrue(closing.closing)
        assertTrue(closing.mayLeave)
        assertNull(closing.saveChanges)
    }

    @Test
    fun submissionFreezesTheDraftAndCanOutliveTheSheet() {
        for (state in listOf(MangaEditFixtures.saving, MangaEditFixtures.failed)) {
            assertFalse(state.editable)
            assertNull(state.saveChanges)
            assertTrue(state.mayLeave)
            assertEquals(state, state.after(MangaEditSheetEvent.VolumesTyped("7")))
            assertEquals(state, state.after(MangaEditSheetEvent.StatusChosen(MangaEditStatus.Dropped)))
            assertEquals(state, state.after(MangaEditSheetEvent.ScoreChosen(3)))
        }
    }
}
