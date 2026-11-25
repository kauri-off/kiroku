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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

internal class MangaTitleViewModel(
    private val id: MangaId,
    private val manga: MangaRepository?,
    private val clock: Clock,
    private val connection: Connection,
    language: Flow<TitleLanguage>,
    session: Flow<Session>,
    private val onRefreshFailed: (DataError) -> Unit,
    private val opened: MangaTitleSummary? = null,
) : ViewModel() {
    private data class Page(
        val cached: StoredManga? = null,
        val problem: MangaTitleProblem? = null,
        val refreshing: Boolean = false,
        val coverAttempt: Int = 0,
    )

    private data class ConnectionObservation(
        val lastOnline: Boolean? = null,
        val recovery: Long = 0,
        val observing: Boolean = false,
    ) {
        val online: Boolean get() = observing && lastOnline == true

        fun next(online: Boolean): ConnectionObservation =
            ConnectionObservation(online, recovery + if (online && lastOnline == false) 1 else 0, observing = true)
    }

    private val observed = MutableStateFlow(ConnectionObservation())
    private val page = MutableStateFlow(Page())
    private var load: Job? = null
    private var owner: SessionOwner? = null
    private var watchingOwner = false
    private var connectionWatch: Job? = null
    private var retryOwed = false
    private var spentRecovery: Long? = null
    private var resumeForce: Boolean? = null
    private var resumeFullShow = false
    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = {
                watchConnection()
                watchOwner()
                load(force = false, fullShow = true)
            },
            again = {},
            hidden = {
                connectionWatch?.cancel()
                observed.value = observed.value.copy(observing = false)
            },
            shownAgain = {
                watchConnection()
                if (resumeForce != null || retryOwed) load(force = false)
            },
        )
    val shown = loads.shown

    val state =
        combine(
            page,
            language.onStart { emit(opened?.language ?: TitleLanguage.Romaji) },
            session,
        ) { current, titleLanguage, session ->
            val details =
                current.cached?.details ?: opened?.details?.takeUnless {
                    current.problem == MangaTitleProblem.ReadFailed(DataError.ListEnded) || current.problem == MangaTitleProblem.Unavailable
                }
            MangaTitleState(
                content =
                    when {
                        details != null ->
                            MangaTitleContent.Shown(
                                details,
                                details.titles.preferred(titleLanguage),
                                current.refreshing,
                                current.coverAttempt,
                            )
                        current.problem != null -> MangaTitleContent.Failed(current.problem)
                        else -> MangaTitleContent.Loading
                    },
                sessionExpired = session is Session.Expired,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
            MangaTitleState(
                content =
                    opened?.let { MangaTitleContent.Shown(it.details, it.details.titles.preferred(it.language)) }
                        ?: MangaTitleContent.Loading,
            ),
        )

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
                retryOwed = false
                spentRecovery = null
                resumeForce = false
                resumeFullShow = false
                page.value = Page()
                load(force = false)
            }
        }
    }

    private fun watchConnection() {
        if (manga == null || !loads.isShowing || connectionWatch?.isActive == true) return
        connectionWatch =
            viewModelScope.launch {
                connection.observeOnline().collect { online ->
                    observed.value = observed.value.next(online)
                    if (retryOwed) load(force = false)
                }
            }
    }

    private fun availableRecovery(): Long? = observed.value.takeIf { it.online && it.recovery != spentRecovery }?.recovery

    private fun load(
        force: Boolean,
        fullShow: Boolean = false,
    ) {
        if (!loads.isShowing) return
        resumeFullShow = resumeFullShow || fullShow
        if (load?.isActive == true) return
        val source = manga
        if (source == null) {
            page.value = Page(problem = MangaTitleProblem.Unavailable)
            return
        }
        val owner = owner ?: return
        val manual = force || resumeForce == true
        if (!manual && !resumeFullShow && retryOwed && availableRecovery() == null) return
        resumeForce = null
        load =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val before = page.value
                val stored = source.stored(id, owner)
                ensureActive()
                if (stored is Outcome.Failure && stored.error == DataError.ListEnded) {
                    retryOwed = false
                    resumeFullShow = false
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
                val checkOnShow = resumeFullShow
                resumeFullShow = false
                val owed = retryOwed
                val recovery = if (owed && !manual) availableRecovery() else null
                val stale = mangaStale(cached?.fetchedAt, clock.instant())
                if (!manual && recovery == null && (!stale || (owed && !checkOnShow))) return@launch
                val previouslySpent = spentRecovery
                if (recovery != null) spentRecovery = recovery
                retryOwed = false
                page.value = page.value.copy(problem = null, refreshing = manual)
                val answer =
                    source.refresh(id, owner) {
                        loads.isShowing && (recovery == null || observed.value.let { it.online && it.recovery == recovery })
                    }
                ensureActive()
                load = null
                if (answer == null) {
                    page.value = before.copy(cached = cached)
                    resumeForce = manual
                    resumeFullShow = resumeFullShow || checkOnShow
                    retryOwed = owed
                    spentRecovery = previouslySpent
                    load(force = false)
                    return@launch
                }
                resumeFullShow = false
                when (answer) {
                    is Outcome.Success -> {
                        page.value = Page(cached = answer.value, coverAttempt = before.coverAttempt + 1)
                    }
                    is Outcome.Failure -> {
                        val problem = MangaTitleProblem.ReadFailed(answer.error)
                        val stillCached = cached.takeUnless { answer.error == DataError.ListEnded }
                        page.value = Page(stillCached, problem, coverAttempt = before.coverAttempt + 1)
                        if (stillCached != null || (opened != null && answer.error != DataError.ListEnded)) onRefreshFailed(answer.error)
                        retryOwed = answer.error == DataError.NoConnection
                        if (retryOwed) load(force = false)
                    }
                }
            }
        load?.start()
    }
}
