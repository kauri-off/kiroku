package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.listStale
import io.github.kauri_off.kiroku.edit.MangaEditStatus
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

internal class MangaListViewModel(
    private val lists: MangaListReader,
    private val clock: Clock,
    connection: Connection,
    private val sessions: Flow<Session>,
    language: Flow<TitleLanguage>,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val onRefreshed: (MangaListSnapshot) -> Unit = {},
    private val onRefreshFailed: (DataError) -> Unit,
) : ViewModel() {
    private data class Page(
        val snapshot: MangaListSnapshot? = null,
        val error: DataError? = null,
        val refreshing: Boolean = false,
    )

    private val selection = saved.getStateFlow<ArrayList<String>?>(TAB, null)
    private val page = MutableStateFlow(Page())
    private val session = MutableStateFlow<Session?>(null)
    private var load: Job? = null
    private var watch: Job? = null
    private var refreshPending = false
    private var recoveryPending = false
    private var coveredDuringRefresh = false
    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = {
                watchSession()
                read()
            },
            again = {
                recoveryPending = true
                read()
            },
            shownAgain = { read() },
            hidden = { coveredDuringRefresh = true },
            trackOutagesWhileShown = true,
        )

    val shown = loads.shown
    val state =
        combine(page, session, language.onStart { emit(TitleLanguage.Romaji) }, selection) { current, account, titles, selected ->
            val tab =
                selected
                    ?.takeIf { it.firstOrNull() == account?.owner?.value }
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::MangaReadingStatus)
                    ?: MangaEditStatus.Reading.readingStatus
            val snapshot = current.snapshot?.takeIf { it.owner == account?.owner }
            val error = current.error
            val content =
                when {
                    account == Session.SignedOut -> MangaListContent.SignedOut
                    snapshot != null -> MangaListContent.Ready(snapshot.entries, titles, current.refreshing)
                    error != null -> MangaListContent.Failed(error)
                    else -> MangaListContent.Loading
                }
            MangaListState(account?.owner, content, account is Session.Expired, tab)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), MangaListState())

    fun refresh() = read(force = true)

    fun selectTab(
        owner: SignInId,
        tab: MangaReadingStatus,
    ) {
        if (session.value?.owner == owner) saved[TAB] = arrayListOf(owner.value, tab.code)
    }

    private fun watchSession() {
        if (watch != null) return
        watch =
            viewModelScope.launch {
                sessions.collect { next ->
                    val previous = session.value
                    if (previous?.owner != next.owner) {
                        load?.cancel()
                        load = null
                        refreshPending = false
                        recoveryPending = false
                        loads.started()
                        page.value = Page()
                    }
                    if (selection.value?.firstOrNull() != next.owner?.value) saved[TAB] = null
                    session.value = next
                    if (previous != next) read()
                }
            }
    }

    private fun read(force: Boolean = false) {
        refreshPending = refreshPending || force
        if (!loads.isShowing || load?.isActive == true) return
        val owner = session.value?.owner ?: return
        load =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val stored = lists.stored(owner)
                ensureActive()
                val snapshot =
                    when (stored) {
                        is Outcome.Success -> stored.value?.takeIf { it.owner == owner }
                        is Outcome.Failure -> page.value.snapshot?.takeIf { it.owner == owner && stored.error != DataError.ListEnded }
                    }
                if (stored is Outcome.Failure && stored.error == DataError.ListEnded) {
                    page.value = Page(error = stored.error)
                    return@launch
                }
                page.value = Page(snapshot)
                if (!loads.isShowing) return@launch
                if (session.value is Session.Expired) {
                    if (snapshot == null) page.value = Page(error = DataError.SessionExpired)
                    return@launch
                }
                val forced = refreshPending
                val recovering = recoveryPending
                if (!forced && !recovering && !listStale(snapshot?.syncedAt, clock.instant())) return@launch
                refreshPending = false
                recoveryPending = false
                page.value = Page(snapshot, refreshing = true)
                coveredDuringRefresh = false
                val result = lists.refresh(owner) { loads.isShowing }
                ensureActive()
                if (sessions.first().owner != owner || session.value?.owner != owner) return@launch
                load = null
                if (result == null) {
                    refreshPending = refreshPending || forced
                    recoveryPending = recoveryPending || recovering
                    page.value = Page(snapshot)
                    read()
                    return@launch
                }
                refreshPending = false
                when (result) {
                    is Outcome.Success -> {
                        recoveryPending = false
                        val updated = result.value.takeIf { it.owner == owner }
                        page.value = if (updated != null) Page(updated) else Page(error = DataError.ListEnded)
                        if (updated != null) onRefreshed(updated)
                        loads.ended(null)
                    }
                    is Outcome.Failure -> {
                        if (result.error != DataError.NoConnection) recoveryPending = false
                        val kept = snapshot.takeUnless { result.error == DataError.ListEnded }
                        page.value = Page(kept, result.error)
                        if (kept != null) onRefreshFailed(result.error)
                        loads.ended(
                            result.error.takeUnless { recoveryPending },
                            retryAfterHiddenFailure = !recovering && (coveredDuringRefresh || !loads.isShowing),
                        )
                        if (recoveryPending) read()
                    }
                }
            }
        load?.start()
    }

    private companion object {
        val TAB = notShown("mangaTab")
    }
}
