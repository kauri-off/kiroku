package io.github.kauri_off.kiroku.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.repository.RecentTitle
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

    private var opening: ViewedTitleWrites.Opening? = null
    private var writer: ViewedTitleWrites? = null
    private var reserving = false

    fun start(
        history: RecentTitles,
        writes: ViewedTitleWrites,
        scope: RecentTitleScope,
        key: RecentTitleId,
    ) {
        val owner = saved.get<String>(OWNER)
        if (owner != null && owner != scope.key) return
        val canReserve = owner == null || saved.get<Boolean>(TRY_AGAIN) == true
        if (opening == null && !canReserve && visit.value == null) return
        val current =
            opening ?: writes
                .open(scope, key) {
                    saved[TRY_AGAIN] = false
                    saved[VISIT] = null
                }.also {
                    opening = it
                    writer = writes
                }
        if (!canReserve || current.dismissed || reserving || visit.value != null) return
        reserving = true
        saved[OWNER] = scope.key
        saved[TRY_AGAIN] = false
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val order = history.reserveVisit(scope)
                current.awaitRemoval()
                if (!current.dismissed) {
                    saved[VISIT] = order
                    saved[TRY_AGAIN] = order == null
                }
            } finally {
                reserving = false
            }
        }
    }

    suspend fun record(
        history: RecentTitles,
        scope: RecentTitleScope,
        title: RecentTitle,
        order: Long,
    ) {
        val current = opening?.takeIf { it.owner == scope } ?: return
        current.record { history.record(scope, title, order) }
    }

    override fun onCleared() {
        opening?.let { writer?.close(it) }
    }

    private companion object {
        val OWNER = notShown("visitOwner")
        val VISIT = notShown("visit")
        val TRY_AGAIN = notShown("retryVisitReservation")
    }
}
