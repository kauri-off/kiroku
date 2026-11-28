package io.github.kauri_off.kiroku.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal class RecentTitleVisitViewModel(
    private val saved: SavedStateHandle,
) : ViewModel() {
    val visit = saved.getStateFlow<Long?>(VISIT, null)

    fun belongsTo(scope: RecentTitleScope): Boolean = saved.get<String>(OWNER) == scope.key

    fun start(
        history: RecentTitles,
        writes: ViewedTitleWrites,
        scope: RecentTitleScope,
        key: RecentTitleId,
    ) {
        if (saved.contains(OWNER)) return
        saved[OWNER] = scope.key
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            saved[VISIT] = writes.reserve(history, scope, key)
        }
    }

    private companion object {
        val OWNER = notShown("visitOwner")
        val VISIT = notShown("visit")
    }
}
