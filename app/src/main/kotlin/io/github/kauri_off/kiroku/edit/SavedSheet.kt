package io.github.kauri_off.kiroku.edit

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.notShown
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

// What an open edit sheet holds beyond the title, written to the saved state after every event; [saved] is what it opened with.
internal data class SavedSheet(
    val saved: ListValues?,
    val draft: ListValues,
    val beforeAutomatic: ListValues?,
    val typed: String?,
    val typingFrom: Int?,
    val dialog: EditDialog?,
    val closing: Boolean,
) {
    fun applyTo(opened: EditSheetState): EditSheetState =
        opened.copy(
            saved = saved,
            draft = draft,
            beforeAutomatic = beforeAutomatic,
            typed = typed,
            typingFrom = typingFrom,
            dialog = dialog,
        )

    companion object {
        private val SAVED = notShown("edit.saved")
        private val DRAFT = notShown("edit.draft")
        private val BEFORE = notShown("edit.before")
        private val TYPED = notShown("edit.typed")
        private val TYPING_FROM = notShown("edit.typingFrom")
        private val DIALOG = notShown("edit.dialog")
        private val CLOSING = notShown("edit.closing")
        private val SEPARATOR = notShown(";")
        private val REMOVE = notShown("remove")
        private val DISCARD = notShown("discard")
        private val UNRECOGNIZED = notShown("?")
        private val NEW = notShown("new")

        fun save(
            handle: SavedStateHandle,
            state: EditSheetState,
        ) {
            handle[SAVED] = state.saved?.let(::encode) ?: NEW
            handle[DRAFT] = encode(state.draft)
            handle[BEFORE] = state.beforeAutomatic?.let(::encode)
            handle[TYPED] = state.typed
            handle[TYPING_FROM] = state.typingFrom
            handle[DIALOG] = state.dialog?.let(::encode)
            handle[CLOSING] = state.closing
        }

        fun read(handle: SavedStateHandle): SavedSheet? {
            val draft = handle.get<String>(DRAFT)?.let(::values) ?: return null
            val saved = handle.get<String>(SAVED) ?: return null
            return SavedSheet(
                saved = if (saved == NEW) null else values(saved),
                draft = draft,
                beforeAutomatic = handle.get<String>(BEFORE)?.let(::values),
                typed = handle[TYPED],
                typingFrom = handle[TYPING_FROM],
                dialog = handle.get<String>(DIALOG)?.let(::dialog),
                closing = handle[CLOSING] ?: false,
            )
        }

        fun clear(handle: SavedStateHandle) {
            listOf(SAVED, DRAFT, BEFORE, TYPED, TYPING_FROM, DIALOG, CLOSING).forEach { handle.remove<Any>(it) }
        }

        // The status goes last, so that MAL's code of one the app does not know may hold anything.
        fun encode(values: ListValues): String =
            listOf(
                values.watchedEpisodes.toString(),
                values.score?.toString().orEmpty(),
                values.startDate?.let(::encode).orEmpty(),
                values.finishDate?.let(::encode).orEmpty(),
                when (val status = values.status) {
                    is ListStatus -> status.name
                    is EntryStatus.Unrecognized -> UNRECOGNIZED + status.malCode
                },
            ).joinToString(SEPARATOR)

        fun values(text: String): ListValues {
            val (watched, score, start, finish, status) = text.split(SEPARATOR, limit = 5)
            return ListValues(
                if (status.startsWith(
                        UNRECOGNIZED,
                    )
                ) {
                    EntryStatus.Unrecognized(status.removePrefix(UNRECOGNIZED))
                } else {
                    ListStatus.valueOf(status)
                },
                watched.toInt(),
                score.toIntOrNull(),
                start.ifEmpty { null }?.let(::date),
                finish.ifEmpty { null }?.let(::date),
            )
        }

        private fun encode(date: PartialDate): String =
            when (date) {
                is PartialDate.Day -> date.date.toString()
                is PartialDate.Month -> date.yearMonth.toString()
                is PartialDate.OnlyYear -> date.value.toString()
            }

        private fun date(text: String): PartialDate =
            when (text.count { it == '-' }) {
                2 -> PartialDate.Day(LocalDate.parse(text))
                1 -> PartialDate.Month(YearMonth.parse(text))
                else -> PartialDate.OnlyYear(Year.parse(text))
            }

        private fun encode(dialog: EditDialog): String =
            when (dialog) {
                is EditDialog.PickDate -> dialog.field.name
                EditDialog.ConfirmRemove -> REMOVE
                EditDialog.ConfirmDiscard -> DISCARD
            }

        private fun dialog(text: String): EditDialog =
            when (text) {
                REMOVE -> EditDialog.ConfirmRemove
                DISCARD -> EditDialog.ConfirmDiscard
                else -> EditDialog.PickDate(DateField.valueOf(text))
            }
    }
}
