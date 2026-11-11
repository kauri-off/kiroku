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

    private fun sheet(
        saved: ListValues?,
        total: Int,
    ) = EditSheetState.of("Kagerou Toshokan 2nd Season", saved, total, malEpisodes = total, today)

    @Test
    fun reachingTheTotalShowsCompletedAndTodayBeforeSave() {
        val state = sheet(watching, 24).after(EditSheetEvent.WatchedChanged(24))
        assertEquals(ListStatus.Completed, state.draft.status)
        assertEquals(PartialDate.Day(today), state.draft.finishDate)
        assertEquals(started, state.draft.startDate)
        assertFalse(state.closing)
    }

    @Test
    fun steppingBackTakesTheAutomaticChangesBack() {
        val opened = sheet(watching, 24)
        val state = opened.afterAll(EditSheetEvent.WatchedChanged(24), EditSheetEvent.WatchedChanged(23))
        assertEquals(watching, state.draft)
        assertFalse(state.hasChanges)
    }

    @Test
    fun aPlannedTitleGoesBackToPlannedWhenItsFirstEpisodeIsTakenBack() {
        val opened = sheet(null, 12)
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
            sheet(watching, 24)
                .afterAll(EditSheetEvent.WatchedChanged(24), EditSheetEvent.ScoreChanged(9), EditSheetEvent.WatchedChanged(23))
        assertEquals(ListStatus.Completed, state.draft.status)
        assertEquals(PartialDate.Day(today), state.draft.finishDate)
        assertEquals(9, state.draft.score)
    }

    @Test
    fun aCompletedTitleSteppedBelowItsTotalStaysCompleted() {
        val completed = watching.copy(status = ListStatus.Completed, watchedEpisodes = 24, finishDate = started)
        val state = sheet(completed, 24).after(EditSheetEvent.WatchedChanged(20))
        assertEquals(completed.copy(watchedEpisodes = 20), state.draft)
    }

    @Test
    fun aDateTheUserHasIsNeverOverwritten() {
        val month = PartialDate.Month(YearMonth.of(2025, 9))
        val state = sheet(watching.copy(finishDate = month), 24).after(EditSheetEvent.WatchedChanged(24))
        assertEquals(month, state.draft.finishDate)
    }

    @Test
    fun choosingAStatusChangesNothingElse() {
        val state = sheet(watching, 24).after(EditSheetEvent.StatusChosen(ListStatus.Completed))
        assertEquals(watching.copy(status = ListStatus.Completed), state.draft)
    }

    @Test
    fun scoreZeroIsNotScored() {
        val state = sheet(watching, 24).after(EditSheetEvent.ScoreChanged(0))
        assertNull(state.draft.score)
    }

    @Test
    fun datesArePickedAndCleared() {
        val opened = sheet(watching, 24)
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
        val opened = sheet(watching, 24)
        assertFalse(opened.hasChanges)
        assertFalse(sheet(null, 24).hasChanges)
        assertTrue(opened.after(EditSheetEvent.ScoreChanged(9)).hasChanges)
        assertFalse(opened.afterAll(EditSheetEvent.ScoreChanged(9), EditSheetEvent.ScoreChanged(8)).hasChanges)
    }

    @Test
    fun leavingWithChangesAsksAndKeepsTheValuesUntilDiscarded() {
        val changed = sheet(watching, 24).after(EditSheetEvent.ScoreChanged(9))
        val asked = changed.after(EditSheetEvent.DismissAsked)
        assertEquals(EditDialog.ConfirmDiscard, asked.dialog)
        assertFalse(asked.closing)
        assertEquals(changed, asked.after(EditSheetEvent.DialogDismissed))
        assertTrue(asked.after(EditSheetEvent.DiscardConfirmed).closing)
    }

    @Test
    fun removingAsksFirst() {
        val opened = sheet(watching, 24)
        val asked = opened.after(EditSheetEvent.RemoveClicked)
        assertEquals(EditDialog.ConfirmRemove, asked.dialog)
        assertFalse(asked.closing)
        assertEquals(opened, asked.after(EditSheetEvent.DialogDismissed))
        assertTrue(asked.after(EditSheetEvent.RemoveConfirmed).closing)
    }

    @Test
    fun aTypedCountTakesEffectWithEachKeystroke() {
        val opened = sheet(watching, 24)
        val two = opened.after(EditSheetEvent.WatchedTyped("2"))
        assertEquals(watching.copy(watchedEpisodes = 2), two.draft)
        val full = two.after(EditSheetEvent.WatchedTyped("24"))
        assertEquals(watching.copy(status = ListStatus.Completed, watchedEpisodes = 24, finishDate = PartialDate.Day(today)), full.draft)
        assertTrue(full.canSave)
        assertEquals(watching.copy(watchedEpisodes = 2), full.after(EditSheetEvent.WatchedTyped("2")).draft)
    }

    @Test
    fun anEmptyCountCannotBeSavedAndLosesNothing() {
        val opened = sheet(watching, 24)
        val empty = opened.after(EditSheetEvent.WatchedTyped(""))
        assertEquals(watching, empty.draft)
        assertTrue(empty.countEmpty)
        assertFalse(empty.canSave)
        assertFalse(empty.after(EditSheetEvent.SaveClicked).closing)
        assertTrue(empty.mayLeave)
        val ended = empty.after(EditSheetEvent.TypingEnded)
        assertNull(ended.typed)
        assertTrue(ended.canSave)
    }

    @Test
    fun aCountAboveTheTotalIsAcceptedAndCompletesAsTheTotalDoes() {
        val state = sheet(watching, 24).afterAll(EditSheetEvent.WatchedTyped("2"), EditSheetEvent.WatchedTyped("26"))
        assertEquals(watching.copy(status = ListStatus.Completed, watchedEpisodes = 26, finishDate = PartialDate.Day(today)), state.draft)
        assertTrue(state.canSave)
        assertTrue(state.after(EditSheetEvent.SaveClicked).closing)
        val stepped = sheet(watching.copy(watchedEpisodes = 24), 24).afterAll(EditSheetEvent.WatchedChanged(25))
        assertEquals(ListStatus.Completed, stepped.draft.status)
    }

    // Each keystroke is its own event, as a real Backspace makes them.
    @Test
    fun clearingTheFieldReturnsToTheCountFromBeforeTyping() {
        val opened = sheet(watching.copy(watchedEpisodes = 12), 24)
        val cleared = opened.afterAll(EditSheetEvent.WatchedTyped("1"), EditSheetEvent.WatchedTyped(""))
        assertEquals(opened.draft, cleared.draft)
        assertFalse(cleared.hasChanges)
        val left = cleared.after(EditSheetEvent.TypingEnded)
        assertEquals(opened, left)
    }

    @Test
    fun clearingTheFieldTakesBackWhatEarlierKeystrokesCaused() {
        val planned = sheet(null, 24)
        val typing = planned.afterAll(EditSheetEvent.WatchedTyped(""), EditSheetEvent.WatchedTyped("2"))
        assertEquals(ListValues(ListStatus.Watching, 2, null, PartialDate.Day(today), null), typing.draft)
        val above = typing.after(EditSheetEvent.WatchedTyped("26"))
        assertEquals(ListValues(ListStatus.Completed, 26, null, PartialDate.Day(today), PartialDate.Day(today)), above.draft)
        val cleared = above.afterAll(EditSheetEvent.WatchedTyped("2"), EditSheetEvent.WatchedTyped(""))
        assertEquals(EditSheetState.newEntry, cleared.draft)
        assertEquals(planned, cleared.after(EditSheetEvent.TypingEnded))

        val twoEpisodes = sheet(watching.copy(watchedEpisodes = 0, startDate = null), 2)
        val back =
            twoEpisodes.afterAll(
                EditSheetEvent.WatchedTyped(""),
                EditSheetEvent.WatchedTyped("2"),
                EditSheetEvent.WatchedTyped("20"),
                EditSheetEvent.WatchedTyped("2"),
                EditSheetEvent.WatchedTyped(""),
                EditSheetEvent.TypingEnded,
            )
        assertEquals(twoEpisodes, back)
    }

    @Test
    fun anotherEditEndsTyping() {
        val state =
            sheet(watching, 24).afterAll(EditSheetEvent.WatchedTyped("2"), EditSheetEvent.StatusChosen(ListStatus.OnHold))
        assertNull(state.typed)
        assertNull(state.typingFrom)
        assertEquals(watching.copy(status = ListStatus.OnHold, watchedEpisodes = 2), state.draft)
    }

    @Test
    fun aStepReplacesTheTypedText() {
        val state = sheet(watching, 24).afterAll(EditSheetEvent.WatchedTyped(""), EditSheetEvent.WatchedChanged(24))
        assertNull(state.typed)
        assertEquals(ListStatus.Completed, state.draft.status)
    }

    @Test
    fun onlyMalsTotalCompletesTheTitle() {
        val onlyAniList = EditSheetState.of("Title", watching.copy(watchedEpisodes = 11), total = 12, malEpisodes = null, today)
        val stepped = onlyAniList.after(EditSheetEvent.WatchedChanged(12))
        assertEquals(watching.copy(watchedEpisodes = 12), stepped.draft)
        assertEquals(watching.copy(watchedEpisodes = 13), onlyAniList.after(EditSheetEvent.WatchedTyped("13")).draft)
    }

    @Test
    fun cancellingThePickerKeepsAPartialDate() {
        val month = PartialDate.Month(YearMonth.of(2025, 9))
        val opened = sheet(watching.copy(startDate = month), 24)
        val state = opened.afterAll(EditSheetEvent.DateOpened(DateField.Start), EditSheetEvent.DialogDismissed)
        assertEquals(opened, state)
        assertEquals(month, state.draft.startDate)
    }
}
