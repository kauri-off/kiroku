package io.github.kauri_off.kiroku.edit

import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import java.time.LocalDate

enum class DateField { Start, Finish }

sealed interface EditDialog {
    data class PickDate(
        val field: DateField,
    ) : EditDialog

    data object ConfirmRemove : EditDialog

    data object ConfirmDiscard : EditDialog
}

// [saved] is null for a title not on the list; [closing] lets the sheet leave with values that differ from it.
data class EditSheetState(
    val total: Int?,
    val saved: ListValues?,
    val draft: ListValues,
    val today: LocalDate,
    val dialog: EditDialog? = null,
    val closing: Boolean = false,
    val beforeAutomatic: ListValues? = null,
) {
    val isNew: Boolean get() = saved == null

    val hasChanges: Boolean get() = draft != (saved ?: newEntry)

    companion object {
        val newEntry = ListValues(ListStatus.Planned, watchedEpisodes = 0, score = null, startDate = null, finishDate = null)

        fun of(
            saved: ListValues?,
            total: Int?,
            today: LocalDate,
        ) = EditSheetState(total = total, saved = saved, draft = saved ?: newEntry, today = today)
    }
}

sealed interface EditSheetEvent {
    data class StatusChosen(
        val status: ListStatus,
    ) : EditSheetEvent

    data class WatchedChanged(
        val watched: Int,
    ) : EditSheetEvent

    // 0 is "Not scored".
    data class ScoreChanged(
        val score: Int,
    ) : EditSheetEvent

    data class DateOpened(
        val field: DateField,
    ) : EditSheetEvent

    data class DatePicked(
        val field: DateField,
        val date: LocalDate,
    ) : EditSheetEvent

    data class DateCleared(
        val field: DateField,
    ) : EditSheetEvent

    data object DialogDismissed : EditSheetEvent

    data object SaveClicked : EditSheetEvent

    data object RemoveClicked : EditSheetEvent

    data object RemoveConfirmed : EditSheetEvent

    // A swipe, Back or a tap outside the sheet while something would be lost; the sheet stays.
    data object DismissAsked : EditSheetEvent

    data object DiscardConfirmed : EditSheetEvent

    // The sheet has left the screen.
    data object Dismissed : EditSheetEvent
}

// The sheet's own state after [event]. Save and Remove also need a write, which is not this function's.
fun EditSheetState.after(event: EditSheetEvent): EditSheetState =
    when (event) {
        is EditSheetEvent.StatusChosen -> edited(draft.copy(status = event.status))
        is EditSheetEvent.WatchedChanged -> withWatched(event.watched)
        is EditSheetEvent.ScoreChanged -> edited(draft.copy(score = event.score.takeIf { it > 0 }))
        is EditSheetEvent.DateOpened -> copy(dialog = EditDialog.PickDate(event.field))
        is EditSheetEvent.DatePicked -> edited(draft.withDate(event.field, PartialDate.Day(event.date))).copy(dialog = null)
        is EditSheetEvent.DateCleared -> edited(draft.withDate(event.field, null))
        EditSheetEvent.DialogDismissed -> copy(dialog = null)
        EditSheetEvent.SaveClicked -> copy(closing = true)
        EditSheetEvent.RemoveClicked -> copy(dialog = EditDialog.ConfirmRemove)
        EditSheetEvent.RemoveConfirmed -> copy(dialog = null, closing = true)
        EditSheetEvent.DismissAsked -> copy(dialog = EditDialog.ConfirmDiscard)
        EditSheetEvent.DiscardConfirmed -> copy(dialog = null, closing = true)
        EditSheetEvent.Dismissed -> this
    }

private fun EditSheetState.edited(values: ListValues) = copy(draft = values, beforeAutomatic = null)

// W7's automatic changes show before Save; a later count that no longer causes them takes them back.
private fun EditSheetState.withWatched(watched: Int): EditSheetState {
    val base = beforeAutomatic ?: draft
    val next = base.withWatched(watched, total, today)
    val automatic = next.copy(watchedEpisodes = base.watchedEpisodes) != base
    return copy(draft = next, beforeAutomatic = if (automatic) base else null)
}

private fun ListValues.withDate(
    field: DateField,
    date: PartialDate?,
) = when (field) {
    DateField.Start -> copy(startDate = date)
    DateField.Finish -> copy(finishDate = date)
}
