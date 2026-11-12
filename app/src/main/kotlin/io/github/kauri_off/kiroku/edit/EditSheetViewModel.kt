package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

// The edit sheet of the screen that opened it. Every event is reduced synchronously in onEvent, so the count field
// never loses or reorders a keystroke; the open sheet survives recreation and process death.
class EditSheetViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
) : ViewModel() {
    var sheet by mutableStateOf<EditSheetState?>(null)
        private set

    private var anime: Anime? = null
    private var loading = false

    init {
        saved.get<Long>(ID)?.let { id ->
            val restored = SavedSheet.read(saved)
            if (restored == null || restored.closing) forget() else load(AnimeId(id), restored)
        }
    }

    fun open(id: AnimeId) {
        if (sheet != null || loading) return
        saved[ID] = id.value
        load(id, null)
    }

    fun onEvent(event: EditSheetEvent) {
        val before = sheet ?: return
        val after = before.after(event)
        sheet = after
        val anime = anime
        when (event) {
            EditSheetEvent.SaveClicked ->
                if (anime != null && after.closing && !before.closing && (after.hasChanges || after.isNew)) {
                    messages.write(ListWrite.Save(anime, after.draft))
                }
            EditSheetEvent.RemoveConfirmed -> if (anime != null && !before.closing && !after.isNew) messages.write(ListWrite.Remove(anime))
            EditSheetEvent.Dismissed -> {
                sheet = null
                this.anime = null
                forget()
                return
            }
            else -> Unit
        }
        SavedSheet.write(saved, after)
    }

    private fun load(
        id: AnimeId,
        restored: SavedSheet?,
    ) {
        loading = true
        viewModelScope.launch {
            val entry =
                try {
                    graph.list.observeEntry(id).first()
                } catch (_: DataException) {
                    loading = false
                    forget()
                    return@launch
                }
            val found = entry?.anime ?: graph.anime.observeAnime(id).first()
            loading = false
            if (found == null) {
                forget()
                return@launch
            }
            anime = found
            val today = LocalDate.now(graph.clock)
            val opened = EditSheetState.of(found, graph.airing.observeAiring().first()[id], entry?.values, graph.titleLanguage, today)
            sheet = restored?.applyTo(opened) ?: opened
            sheet?.let { SavedSheet.write(saved, it) }
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
