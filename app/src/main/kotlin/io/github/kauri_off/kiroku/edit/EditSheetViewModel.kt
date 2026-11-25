package io.github.kauri_off.kiroku.edit

import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZonedDateTime

// The edit sheet of the screen that opened it: a draft of the user's input, read once from the store.
class EditSheetViewModel(
    private val list: ListSource,
    private val animeRepository: AnimeRepository,
    private val airing: AiringRepository,
    private val clock: Clock,
    private val titleLanguage: Flow<TitleLanguage>,
    private val saved: SavedStateHandle,
    private val messages: ShellMessages,
    private val owner: SignInId? = null,
) : ViewModel() {
    private val shown = mutableStateOf<EditSheetState?>(null)

    // Compose state, changed synchronously by every event, so the sheet's fields read each keystroke in the same frame.
    val sheet: EditSheetState? get() = shown.value

    private var sheetRead: Job? = null
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
            if (sheet == null || sheet.closing) forget() else load(AnimeId(id), sheet, known = null)
        }
    }

    // [known] stands in for the title when it is neither on the list nor in the store, as a search result's may be.
    fun open(
        id: AnimeId,
        known: Anime? = null,
    ) {
        restored = true
        if (shown.value != null || loading) return
        saved[ID] = id.value
        load(id, null, known)
    }

    fun onEvent(event: EditSheetEvent) {
        val before = shown.value ?: return
        val after = before.after(event)
        shown.value = after
        val anime = anime
        when (event) {
            EditSheetEvent.SaveClicked ->
                if (anime != null && after.closing && !before.closing && (after.hasChanges || after.isNew)) {
                    messages.write(ListWrite.Save(anime, after.draft, after.set, madeIn = owner))
                }
            EditSheetEvent.RemoveConfirmed ->
                if (anime != null &&
                    !before.closing &&
                    !after.isNew
                ) {
                    messages.write(ListWrite.Remove(anime, madeIn = owner))
                }
            EditSheetEvent.Dismissed -> {
                sheetRead?.cancel()
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
        known: Anime?,
    ) {
        loading = true
        sheetRead =
            viewModelScope.launch {
                val entry =
                    try {
                        if (owner == null) {
                            list.observeEntry(id).first()
                        } else {
                            val owned = list.observeOwnedEntriesOf(setOf(id)).first()
                            if (owned.owner != owner) {
                                loading = false
                                forget()
                                return@launch
                            }
                            owned.entries.firstOrNull { it.anime.id == id }
                        }
                    } catch (_: DataException) {
                        loading = false
                        forget()
                        return@launch
                    }
                val found = entry?.anime ?: animeRepository.observeAnime(id).first() ?: known
                if (found == null) {
                    loading = false
                    forget()
                    return@launch
                }
                anime = found
                // One read of what is stored, which asks no source; without it the sheet has MAL's total alone.
                val known =
                    try {
                        airing.observeAiring().first()[id]
                    } catch (_: DataException) {
                        null
                    }
                titleLanguage.collect { language ->
                    val current = shown.value
                    if (current == null) {
                        val opened = EditSheetState.of(found, known, entry?.values, language, ZonedDateTime.now(clock))
                        val sheet = restored?.applyTo(opened) ?: opened
                        shown.value = sheet
                        loading = false
                        SavedSheet.save(saved, sheet)
                    } else {
                        shown.value = current.copy(title = found.titles.preferred(language))
                    }
                }
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
