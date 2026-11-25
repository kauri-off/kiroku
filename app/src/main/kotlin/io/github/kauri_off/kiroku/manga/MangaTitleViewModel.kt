package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.core.repository.mangaStale
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

internal class MangaTitleViewModel(
    private val id: MangaId,
    private val manga: MangaRepository?,
    private val clock: Clock,
    connection: Connection,
    language: Flow<TitleLanguage>,
    session: Flow<Session>,
    private val onRefreshFailed: (DataError) -> Unit,
) : ViewModel() {
    private data class Page(
        val cached: StoredManga? = null,
        val problem: MangaTitleProblem? = null,
        val refreshing: Boolean = false,
        val coverAttempt: Int = 0,
    )

    private val online = connection.observeOnline().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val page = MutableStateFlow(Page())
    private var load: Job? = null
    private var owner: SessionOwner? = null
    private var watchingOwner = false
    private var reconnectPending = false
    private var reconnectWait: Job? = null
    private var resumeForce: Boolean? = null
    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = {
                watchOwner()
                load(force = false)
            },
            again = {
                reconnectPending = true
                load(force = false)
            },
            hidden = { reconnectWait?.cancel() },
            shownAgain = { if (resumeForce != null || reconnectPending) load(force = false) },
            trackOutagesWhileShown = manga != null,
        )
    val shown = loads.shown

    val state =
        combine(
            page,
            language.onStart { emit(TitleLanguage.Romaji) },
            session,
        ) { current, titleLanguage, session ->
            val cached = current.cached
            MangaTitleState(
                content =
                    when {
                        cached != null ->
                            MangaTitleContent.Shown(
                                cached.details,
                                cached.details.titles.preferred(titleLanguage),
                                current.refreshing,
                                current.coverAttempt,
                            )
                        current.problem != null -> MangaTitleContent.Failed(current.problem)
                        else -> MangaTitleContent.Loading
                    },
                sessionExpired = session is Session.Expired,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), MangaTitleState())

    fun refresh() = load(force = true)

    private fun watchOwner() {
        val source = manga ?: return
        if (watchingOwner) return
        watchingOwner = true
        viewModelScope.launch {
            source.observeOwner().collect { current ->
                owner = current
                load?.cancel()
                load = null
                reconnectPending = false
                reconnectWait?.cancel()
                resumeForce = false
                loads.started()
                page.value = Page()
                load(force = false)
            }
        }
    }

    private fun waitForReconnect() {
        if (reconnectWait?.isActive == true) return
        reconnectWait =
            viewModelScope.launch {
                online.first { it }
                load(force = false, reconnectReady = true)
            }
    }

    private fun load(
        force: Boolean,
        reconnectReady: Boolean = false,
    ) {
        if (!loads.isShowing || load?.isActive == true) return
        val source = manga
        if (source == null) {
            page.value = Page(problem = MangaTitleProblem.Unavailable)
            return
        }
        val owner = owner ?: return
        val manual = force || resumeForce == true
        if (reconnectPending && !manual && !reconnectReady) {
            waitForReconnect()
            return
        }
        if (manual) reconnectWait?.cancel()
        resumeForce = null
        load =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val before = page.value
                val stored = source.stored(id, owner)
                ensureActive()
                if (stored is Outcome.Failure && stored.error == DataError.ListEnded) {
                    page.value = Page(problem = MangaTitleProblem.ReadFailed(stored.error))
                    return@launch
                }
                val cached =
                    when (stored) {
                        is Outcome.Success -> stored.value
                        is Outcome.Failure -> before.cached
                    }
                page.value = before.copy(cached = cached)
                if (!loads.isShowing) {
                    resumeForce = manual
                    return@launch
                }
                if (reconnectPending && !manual && !reconnectReady) {
                    load = null
                    waitForReconnect()
                    return@launch
                }
                if (!manual && !reconnectPending && !mangaStale(cached?.fetchedAt, clock.instant())) return@launch
                val reconnect = reconnectPending
                reconnectPending = false
                val automaticRetry = reconnect && !manual
                loads.started(reconnect = automaticRetry)
                page.value = page.value.copy(problem = null, refreshing = manual)
                val answer = source.refresh(id, owner) { loads.isShowing && (!automaticRetry || online.value) }
                ensureActive()
                load = null
                if (answer == null) {
                    page.value = before.copy(cached = cached)
                    resumeForce = manual
                    reconnectPending = reconnectPending || reconnect
                    load(force = false)
                    return@launch
                }
                when (answer) {
                    is Outcome.Success -> {
                        page.value = Page(cached = answer.value, coverAttempt = before.coverAttempt + 1)
                        loads.ended(null)
                    }
                    is Outcome.Failure -> {
                        val problem = MangaTitleProblem.ReadFailed(answer.error)
                        val stillCached = cached.takeUnless { answer.error == DataError.ListEnded }
                        page.value = Page(stillCached, problem, coverAttempt = before.coverAttempt + 1)
                        if (stillCached != null) onRefreshFailed(answer.error)
                        loads.ended(answer.error)
                    }
                }
            }
        load?.start()
    }
}
