package io.github.kauri_off.kiroku.edit

import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

// The edit sheet of the screen that opened it: a draft of the user's input, read once from the store.
class EditSheetViewModel(
    private val list: ListSource,
    private val animeRepository: AnimeRepository,
    private val airing: AiringRepository,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
) : ViewModel() {
    private val shown = mutableStateOf<EditSheetState?>(null)

    // Compose state, changed synchronously by every event, so the sheet's fields read each keystroke in the same frame.
    val sheet: EditSheetState? get() = shown.value

    private var anime: Anime? = null
    private var loading = false
    private var restored = false

    // Reopens the sheet that was open when the process died; its route calls it once shown.
    @MainThread
    fun restore() {
        if (restored) return
        restored = true
        saved.get<Long>(ID)?.let { id ->
            val sheet = SavedSheet.read(saved)
            if (sheet == null || sheet.closing) forget() else load(AnimeId(id), sheet)
        }
    }

    fun open(id: AnimeId) {
        restored = true
        if (shown.value != null || loading) return
        saved[ID] = id.value
        load(id, null)
    }

    fun onEvent(event: EditSheetEvent) {
        val before = shown.value ?: return
        val after = before.after(event)
        shown.value = after
        val anime = anime
        when (event) {
            EditSheetEvent.SaveClicked ->
                if (anime != null && after.closing && !before.closing && (after.hasChanges || after.isNew)) {
                    messages.write(ListWrite.Save(anime, after.draft))
                }
            EditSheetEvent.RemoveConfirmed -> if (anime != null && !before.closing && !after.isNew) messages.write(ListWrite.Remove(anime))
            EditSheetEvent.Dismissed -> {
                shown.value = null
                this.anime = null
                forget()
                return
            }
            else -> Unit
        }
        SavedSheet.save(saved, after)
    }

    private fun load(
        id: AnimeId,
        restored: SavedSheet?,
    ) {
        loading = true
        viewModelScope.launch {
            val entry =
                try {
                    list.observeEntry(id).first()
                } catch (_: DataException) {
                    loading = false
                    forget()
                    return@launch
                }
            val found = entry?.anime ?: animeRepository.observeAnime(id).first()
            loading = false
            if (found == null) {
                forget()
                return@launch
            }
            anime = found
            val today = LocalDate.now(clock)
            val opened = EditSheetState.of(found, airing.observeAiring().first()[id], entry?.values, titleLanguage, today)
            val sheet = restored?.applyTo(opened) ?: opened
            shown.value = sheet
            SavedSheet.save(saved, sheet)
        }
    }

    private fun forget() {
        saved.remove<Long>(ID)
        SavedSheet.clear(saved)
    }

    private companion object {
        val ID = notShown("edit.id")
    }
}
