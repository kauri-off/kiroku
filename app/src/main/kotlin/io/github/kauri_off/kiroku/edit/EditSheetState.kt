package io.github.kauri_off.kiroku.edit

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import java.time.LocalDate

enum class DateField { Start, Finish }

sealed interface EditDialog {
    data class PickDate(
        val field: DateField,
    ) : EditDialog

    data object ConfirmRemove : EditDialog

    data object ConfirmDiscard : EditDialog
}

// [total] is shown and bounds the count (A3); [malEpisodes] is what W7 completes on. [saved] is null for a title not on
// the list. [typed] is the count field's text while it differs from the draft's count's own text; [closing] lets the
// sheet leave with values that differ from [saved].
data class EditSheetState(
    val title: String,
    val total: Int?,
    val malEpisodes: Int?,
    val saved: ListValues?,
    val draft: ListValues,
    val today: LocalDate,
    val typed: String? = null,
    val dialog: EditDialog? = null,
    val closing: Boolean = false,
    val beforeAutomatic: ListValues? = null,
) {
    init {
        require(malEpisodes == null || total == malEpisodes)
    }

    val isNew: Boolean get() = saved == null

    val hasChanges: Boolean get() = draft != (saved ?: newEntry)

    // The field holds a count above the total, refused and not in the draft.
    val countRefused: Boolean get() = typed?.toIntOrNull()?.let { total != null && it > total } == true

    // The field shows something other than the draft's count: an empty field or a refused count.
    val countPending: Boolean get() = typed != null && typed.toIntOrNull() != draft.watchedEpisodes

    // Save writes the draft, so it is allowed only while the sheet shows exactly the draft.
    val canSave: Boolean get() = !countPending

    val mayLeave: Boolean get() = closing || (!hasChanges && !countPending)

    companion object {
        val newEntry = ListValues(ListStatus.Planned, watchedEpisodes = 0, score = null, startDate = null, finishDate = null)

        fun of(
            anime: Anime,
            airing: Airing?,
            saved: ListValues?,
            titleLanguage: TitleLanguage,
            today: LocalDate,
        ) = of(anime.titles.preferred(titleLanguage), saved, episodeTotal(anime, airing), anime.malEpisodes, today)

        fun of(
            title: String,
            saved: ListValues?,
            total: Int?,
            malEpisodes: Int?,
            today: LocalDate,
        ) = EditSheetState(title, total, malEpisodes, saved, draft = saved ?: newEntry, today = today)
    }
}

sealed interface EditSheetEvent {
    data class StatusChosen(
        val status: ListStatus,
    ) : EditSheetEvent

    // A step of the minus or plus button.
    data class WatchedChanged(
        val watched: Int,
    ) : EditSheetEvent

    // The count field's text after each keystroke; a count it holds takes effect at once.
    data class WatchedTyped(
        val text: String,
    ) : EditSheetEvent

    // The count field lost focus or was confirmed.
    data object TypingEnded : EditSheetEvent

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

    // A swipe, Back or a tap outside the sheet while something would be lost; the sheet stays. It may come more than
    // once for one gesture.
    data object DismissAsked : EditSheetEvent

    data object DiscardConfirmed : EditSheetEvent

    // The sheet has left the screen.
    data object Dismissed : EditSheetEvent
}

// The sheet's own state after [event]. Save and Remove also need a write, which is not this function's.
fun EditSheetState.after(event: EditSheetEvent): EditSheetState =
    when (event) {
        is EditSheetEvent.StatusChosen -> edited(draft.copy(status = event.status))
        is EditSheetEvent.WatchedChanged -> withWatched(event.watched).copy(typed = null)
        is EditSheetEvent.WatchedTyped -> typed(event.text)
        EditSheetEvent.TypingEnded -> if (countRefused) this else copy(typed = null)
        is EditSheetEvent.ScoreChanged -> edited(draft.copy(score = event.score.takeIf { it > 0 }))
        is EditSheetEvent.DateOpened -> copy(dialog = EditDialog.PickDate(event.field))
        is EditSheetEvent.DatePicked -> edited(draft.withDate(event.field, PartialDate.Day(event.date))).copy(dialog = null)
        is EditSheetEvent.DateCleared -> edited(draft.withDate(event.field, null))
        EditSheetEvent.DialogDismissed -> copy(dialog = null)
        EditSheetEvent.SaveClicked -> if (canSave) copy(closing = true) else this
        EditSheetEvent.RemoveClicked -> copy(dialog = EditDialog.ConfirmRemove)
        EditSheetEvent.RemoveConfirmed -> copy(dialog = null, closing = true)
        EditSheetEvent.DismissAsked -> copy(dialog = EditDialog.ConfirmDiscard)
        EditSheetEvent.DiscardConfirmed -> copy(dialog = null, closing = true)
        EditSheetEvent.Dismissed -> this
    }

private fun EditSheetState.edited(values: ListValues) = copy(draft = values, beforeAutomatic = null)

private fun EditSheetState.typed(text: String): EditSheetState {
    val count = text.toIntOrNull()?.takeIf { total == null || it <= total } ?: return copy(typed = text)
    return withWatched(count).copy(typed = text)
}

// W7's automatic changes show before Save; a later count that no longer causes them takes them back.
private fun EditSheetState.withWatched(watched: Int): EditSheetState {
    val base = beforeAutomatic ?: draft
    val next = base.withWatched(watched, malEpisodes, today)
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
