package io.github.kauri_off.kiroku.edit

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

// An open sheet survives process death: every value of the sheet is either rebuilt from the entry or saved.
class SavedSheetTest {
    private val today = LocalDate.of(2026, 1, 14)

    private fun fields(type: Class<*>) =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    @Test
    fun `every value of the sheet is rebuilt by EditSheetState of or saved`() {
        val rebuilt = setOf("title", "total", "malEpisodes", "today")
        assertEquals(fields(EditSheetState::class.java), rebuilt + fields(SavedSheet::class.java))
    }

    @Test
    fun `a sheet written and read back is the same, for every date precision and an empty score`() {
        val saved = ListValues(ListStatus.Watching, 12, score = 8, PartialDate.Day(LocalDate.of(2025, 10, 12)), null)
        val opened = EditSheetState.of("Title", saved, total = 24, malEpisodes = 24, today)
        val dialogs =
            listOf(
                EditDialog.PickDate(DateField.Start),
                EditDialog.PickDate(DateField.Finish),
                EditDialog.ConfirmRemove,
                EditDialog.ConfirmDiscard,
                null,
            )
        val dates = listOf(PartialDate.Day(today), PartialDate.Month(YearMonth.of(2025, 3)), PartialDate.OnlyYear(Year.of(2024)), null)
        for (dialog in dialogs) {
            for (date in dates) {
                val state =
                    opened.copy(
                        draft = saved.copy(score = null, startDate = date, finishDate = date, status = ListStatus.Completed),
                        beforeAutomatic = saved.copy(finishDate = date),
                        typed = "17",
                        typingFrom = 12,
                        dialog = dialog,
                        closing = dialog == null,
                    )
                val handle = SavedStateHandle()
                SavedSheet.save(handle, state)
                val read = SavedSheet.read(handle)
                assertEquals(SavedSheet(saved, state.draft, state.beforeAutomatic, "17", 12, dialog, state.closing), read)
                assertEquals(state.copy(closing = false), read!!.applyTo(opened.copy(saved = saved.copy(watchedEpisodes = 15))))
            }
        }
    }

    @Test
    fun `a sheet for a title not on the list is read back as one, and a sheet saved without its opening values is not read`() {
        val opened = EditSheetState.of("Title", null, total = 24, malEpisodes = 24, today)
        val handle = SavedStateHandle()
        SavedSheet.save(handle, opened)
        assertEquals(null, SavedSheet.read(handle)?.saved)
        assertEquals(opened, SavedSheet.read(handle)?.applyTo(opened.copy(saved = opened.draft)))
        handle.remove<String>(handle.keys().single { it.endsWith("edit.saved") })
        assertEquals(null, SavedSheet.read(handle))
    }

    @Test
    fun `a status the app does not know is written and read back as MAL's code, whatever the code holds`() {
        for (code in listOf("rewatching", "a;b", "?Watching", "")) {
            val values = ListValues(EntryStatus.Unrecognized(code), 3, score = null, null, PartialDate.OnlyYear(Year.of(2024)))
            val state = EditSheetState.of("Title", values, total = 24, malEpisodes = 24, today)
            val handle = SavedStateHandle()
            SavedSheet.save(handle, state)
            assertEquals(code, values, SavedSheet.read(handle)?.draft)
        }
        for (status in ListStatus.entries) {
            val values = ListValues(status, 3, score = 7, null, null)
            assertEquals(values, SavedSheet.values(SavedSheet.encode(values)))
        }
    }
}
