package io.github.kauri_off.kiroku.edit

import androidx.annotation.StringRes
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.notShown

internal enum class MangaEditStatus(
    val readingStatus: MangaReadingStatus,
    @param:StringRes val label: Int,
) {
    Reading(MangaReadingStatus(notShown("reading")), R.string.manga_list_reading),
    Planned(MangaReadingStatus(notShown("plan_to_read")), R.string.manga_list_planned),
    Completed(MangaReadingStatus(notShown("completed")), R.string.manga_list_completed),
    OnHold(MangaReadingStatus(notShown("on_hold")), R.string.manga_list_on_hold),
    Dropped(MangaReadingStatus(notShown("dropped")), R.string.manga_list_dropped),
}

internal data class MangaEditBaseline(
    val status: MangaReadingStatus,
    val chaptersRead: Int?,
    val volumesRead: Int?,
    val score: Int?,
)

internal data class MangaEditTotals(
    val chapters: Int? = null,
    val volumes: Int? = null,
)

internal data class MangaEditChanges(
    val status: MangaEditStatus? = null,
    val chaptersRead: Int? = null,
    val volumesRead: Int? = null,
    val score: Int? = null,
) {
    val isEmpty: Boolean get() = status == null && chaptersRead == null && volumesRead == null && score == null
}

internal sealed interface MangaEditSubmission {
    data object Editing : MangaEditSubmission

    data object Saving : MangaEditSubmission

    data object Reconciling : MangaEditSubmission

    data object Removing : MangaEditSubmission

    data object CheckingRemoval : MangaEditSubmission

    data class Failed(
        val canRetry: Boolean,
        @param:StringRes val message: Int = R.string.shell_could_not_save,
        val canReconcile: Boolean = false,
        val removal: Boolean = false,
        val canStartOver: Boolean = canRetry || canReconcile,
    ) : MangaEditSubmission
}

internal data class MangaEditSheetState(
    val title: String,
    val baseline: MangaEditBaseline,
    val status: MangaEditStatus? = MangaEditStatus.entries.find { it.readingStatus == baseline.status },
    val chapters: String = baseline.chaptersRead?.toString().orEmpty(),
    val volumes: String = baseline.volumesRead?.toString().orEmpty(),
    val score: Int? = baseline.score,
    val submission: MangaEditSubmission = MangaEditSubmission.Editing,
    val confirmDiscard: Boolean = false,
    val closing: Boolean = false,
    val canRemove: Boolean = false,
    val confirmRemove: Boolean = false,
    val totals: MangaEditTotals = MangaEditTotals(),
) {
    val canRestoreStatus: Boolean get() = status != null && MangaEditStatus.entries.none { it.readingStatus == baseline.status }
    val editable: Boolean get() = submission == MangaEditSubmission.Editing && !closing && !confirmDiscard && !confirmRemove
    val chaptersInvalid: Boolean get() = !chapters.isValidCount(baseline.chaptersRead)
    val volumesInvalid: Boolean get() = !volumes.isValidCount(baseline.volumesRead)

    private val changes: MangaEditChanges
        get() =
            MangaEditChanges(
                status = status?.takeUnless { it.readingStatus == baseline.status },
                chaptersRead = chapters.toIntOrNull()?.takeUnless { it == baseline.chaptersRead },
                volumesRead = volumes.toIntOrNull()?.takeUnless { it == baseline.volumesRead },
                score = score?.takeUnless { it == baseline.score },
            )

    val saveChanges: MangaEditChanges? get() = changes.takeUnless { it.isEmpty || chaptersInvalid || volumesInvalid || !editable }

    val mayLeave: Boolean
        get() = closing || submission != MangaEditSubmission.Editing || (changes.isEmpty && !chaptersInvalid && !volumesInvalid)

    fun after(event: MangaEditSheetEvent): MangaEditSheetState =
        when (event) {
            is MangaEditSheetEvent.ChaptersTyped -> if (editable) copy(chapters = event.text) else this
            is MangaEditSheetEvent.VolumesTyped -> if (editable) copy(volumes = event.text) else this
            is MangaEditSheetEvent.StatusChosen -> if (editable) copy(status = event.status) else this
            MangaEditSheetEvent.RestoreStatus -> if (editable && canRestoreStatus) copy(status = null) else this
            is MangaEditSheetEvent.ScoreChosen ->
                if (editable && (event.score?.let { it in 0..10 } ?: (baseline.score == null))) copy(score = event.score) else this
            MangaEditSheetEvent.DismissAsked -> if (mayLeave) this else copy(confirmDiscard = true)
            MangaEditSheetEvent.RemoveAsked -> if (canRemove && editable) copy(confirmRemove = true) else this
            MangaEditSheetEvent.CancelRemove -> copy(confirmRemove = false)
            MangaEditSheetEvent.KeepEditing -> copy(confirmDiscard = false)
            MangaEditSheetEvent.Discard -> if (confirmDiscard) copy(confirmDiscard = false, closing = true) else this
            is MangaEditSheetEvent.Save, MangaEditSheetEvent.Retry, MangaEditSheetEvent.Reconcile, MangaEditSheetEvent.Dismissed -> this
            MangaEditSheetEvent.RemoveConfirmed, MangaEditSheetEvent.StartOver -> this
        }
}

private fun String.isValidCount(baseline: Int?): Boolean = if (isEmpty()) baseline == null else all(Char::isDigit) && toIntOrNull() != null

internal sealed interface MangaEditSheetEvent {
    data class ChaptersTyped(
        val text: String,
    ) : MangaEditSheetEvent

    data class VolumesTyped(
        val text: String,
    ) : MangaEditSheetEvent

    data class StatusChosen(
        val status: MangaEditStatus,
    ) : MangaEditSheetEvent

    data object RestoreStatus : MangaEditSheetEvent

    data class ScoreChosen(
        val score: Int?,
    ) : MangaEditSheetEvent

    data class Save(
        val changes: MangaEditChanges,
    ) : MangaEditSheetEvent

    data object Retry : MangaEditSheetEvent

    data object Reconcile : MangaEditSheetEvent

    data object StartOver : MangaEditSheetEvent

    data object RemoveAsked : MangaEditSheetEvent

    data object CancelRemove : MangaEditSheetEvent

    data object RemoveConfirmed : MangaEditSheetEvent

    data object DismissAsked : MangaEditSheetEvent

    data object KeepEditing : MangaEditSheetEvent

    data object Discard : MangaEditSheetEvent

    data object Dismissed : MangaEditSheetEvent
}
