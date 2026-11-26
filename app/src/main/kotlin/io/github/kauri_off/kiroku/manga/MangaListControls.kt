package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.edit.MangaEditStatus
import io.github.kauri_off.kiroku.list.ListState
import io.github.kauri_off.kiroku.notShown

internal data class MangaListControls(
    val owner: SignInId? = null,
    val tab: MangaReadingStatus = MangaEditStatus.Reading.readingStatus,
    val filter: ListState.Typed = ListState.Typed(null),
    val sorts: Map<MangaReadingStatus, TabSort> = emptyMap(),
    val sortTab: MangaReadingStatus? = null,
)

internal fun SavedStateHandle.restoreMangaControls(): MangaListControls {
    val values = get<ArrayList<String?>>(CONTROLS)
    val legacy = get<ArrayList<String>>(LEGACY_TAB)
    val owner = (values?.getOrNull(0) ?: legacy?.firstOrNull())?.takeIf(String::isNotBlank)?.let(::SignInId)
    val tab = (values?.getOrNull(1) ?: legacy?.getOrNull(1))?.status() ?: MangaEditStatus.Reading.readingStatus
    val filter = ListState.Typed(values?.getOrNull(2), values?.getOrNull(3)?.toIntOrNull() ?: 0)
    return MangaListControls(owner, tab, filter)
}

internal fun SavedStateHandle.saveMangaControls(controls: MangaListControls) {
    remove<ArrayList<String>>(LEGACY_TAB)
    this[CONTROLS] =
        controls.owner?.let { owner ->
            arrayListOf(owner.value, controls.tab.code, controls.filter.text, controls.filter.session.toString())
        }
}

private fun String.status(): MangaReadingStatus? = takeIf(String::isNotBlank)?.let(::MangaReadingStatus)

private val CONTROLS = notShown("mangaListControls")
private val LEGACY_TAB = notShown("mangaTab")
