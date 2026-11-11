package io.github.kauri_off.kiroku.edit

import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class EditSheetStateTest {
    private val today = LocalDate.of(2026, 1, 14)
    private val started = PartialDate.Day(LocalDate.of(2025, 10, 12))
    private val watching = ListValues(ListStatus.Watching, 23, score = 8, startDate = started, finishDate = null)

    private fun EditSheetState.afterAll(vararg events: EditSheetEvent): EditSheetState =
        events.fold(this) { state, event -> state.after(event) }

    @Test
    fun reachingTheTotalShowsCompletedAndTodayBeforeSave() {
        val state = EditSheetState.of(watching, total = 24, today).after(EditSheetEvent.WatchedChanged(24))
        assertEquals(ListStatus.Completed, state.draft.status)
        assertEquals(PartialDate.Day(today), state.draft.finishDate)
        assertEquals(started, state.draft.startDate)
        assertFalse(state.closing)
    }

    @Test
    fun steppingBackTakesTheAutomaticChangesBack() {
        val opened = EditSheetState.of(watching, total = 24, today)
        val state = opened.afterAll(EditSheetEvent.WatchedChanged(24), EditSheetEvent.WatchedChanged(23))
        assertEquals(watching, state.draft)
        assertFalse(state.hasChanges)
    }

    @Test
    fun aPlannedTitleGoesBackToPlannedWhenItsFirstEpisodeIsTakenBack() {
        val opened = EditSheetState.of(null, total = 12, today)
        val first = opened.after(EditSheetEvent.WatchedChanged(1))
        assertEquals(ListStatus.Watching, first.draft.status)
        assertEquals(PartialDate.Day(today), first.draft.startDate)
        val second = first.afterAll(EditSheetEvent.WatchedChanged(2), EditSheetEvent.WatchedChanged(0))
        assertEquals(EditSheetState.newEntry, second.draft)
        assertFalse(second.hasChanges)
    }

    @Test
    fun aChoiceTheUserMadeIsNotTakenBack() {
        val state =
            EditSheetState
                .of(watching, total = 24, today)
                .afterAll(EditSheetEvent.WatchedChanged(24), EditSheetEvent.ScoreChanged(9), EditSheetEvent.WatchedChanged(23))
        assertEquals(ListStatus.Completed, state.draft.status)
        assertEquals(PartialDate.Day(today), state.draft.finishDate)
        assertEquals(9, state.draft.score)
    }

    @Test
    fun aCompletedTitleSteppedBelowItsTotalStaysCompleted() {
        val completed = watching.copy(status = ListStatus.Completed, watchedEpisodes = 24, finishDate = started)
        val state = EditSheetState.of(completed, total = 24, today).after(EditSheetEvent.WatchedChanged(20))
        assertEquals(completed.copy(watchedEpisodes = 20), state.draft)
    }

    @Test
    fun aDateTheUserHasIsNeverOverwritten() {
        val month = PartialDate.Month(YearMonth.of(2025, 9))
        val state = EditSheetState.of(watching.copy(finishDate = month), total = 24, today).after(EditSheetEvent.WatchedChanged(24))
        assertEquals(month, state.draft.finishDate)
    }

    @Test
    fun choosingAStatusChangesNothingElse() {
        val state = EditSheetState.of(watching, total = 24, today).after(EditSheetEvent.StatusChosen(ListStatus.Completed))
        assertEquals(watching.copy(status = ListStatus.Completed), state.draft)
    }

    @Test
    fun scoreZeroIsNotScored() {
        val state = EditSheetState.of(watching, total = 24, today).after(EditSheetEvent.ScoreChanged(0))
        assertNull(state.draft.score)
    }

    @Test
    fun datesArePickedAndCleared() {
        val opened = EditSheetState.of(watching, total = 24, today)
        val picking = opened.after(EditSheetEvent.DateOpened(DateField.Finish))
        assertEquals(EditDialog.PickDate(DateField.Finish), picking.dialog)
        val picked = picking.after(EditSheetEvent.DatePicked(DateField.Finish, today))
        assertEquals(PartialDate.Day(today), picked.draft.finishDate)
        assertNull(picked.dialog)
        assertNull(picked.after(EditSheetEvent.DateCleared(DateField.Start)).draft.startDate)
        assertEquals(opened, picking.after(EditSheetEvent.DialogDismissed))
    }

    @Test
    fun onlyAChangedValueIsAChange() {
        val opened = EditSheetState.of(watching, total = 24, today)
        assertFalse(opened.hasChanges)
        assertFalse(EditSheetState.of(null, total = 24, today).hasChanges)
        assertTrue(opened.after(EditSheetEvent.ScoreChanged(9)).hasChanges)
        assertFalse(opened.afterAll(EditSheetEvent.ScoreChanged(9), EditSheetEvent.ScoreChanged(8)).hasChanges)
    }

    @Test
    fun leavingWithChangesAsksAndKeepsTheValuesUntilDiscarded() {
        val changed = EditSheetState.of(watching, total = 24, today).after(EditSheetEvent.ScoreChanged(9))
        val asked = changed.after(EditSheetEvent.DismissAsked)
        assertEquals(EditDialog.ConfirmDiscard, asked.dialog)
        assertFalse(asked.closing)
        assertEquals(changed, asked.after(EditSheetEvent.DialogDismissed))
        assertTrue(asked.after(EditSheetEvent.DiscardConfirmed).closing)
    }

    @Test
    fun removingAsksFirst() {
        val opened = EditSheetState.of(watching, total = 24, today)
        val asked = opened.after(EditSheetEvent.RemoveClicked)
        assertEquals(EditDialog.ConfirmRemove, asked.dialog)
        assertFalse(asked.closing)
        assertEquals(opened, asked.after(EditSheetEvent.DialogDismissed))
        assertTrue(asked.after(EditSheetEvent.RemoveConfirmed).closing)
    }
}
