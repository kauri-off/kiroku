package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.core.repository.mangaStale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

    private val page = MutableStateFlow(Page())
    private var load: Job? = null
    private val loads = ShownLoads(viewModelScope, connection, { load(force = false) }, ::refresh)
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
        }.stateIn(viewModelScope, WhileShown, MangaTitleState())

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        if (!loads.isShowing || load?.isActive == true) return
        load =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val source = manga
                if (source == null) {
                    page.value = Page(problem = MangaTitleProblem.Unavailable)
                    return@launch
                }
                loads.started()
                val before = page.value
                page.value = before.copy(problem = null, refreshing = force)
                val cached =
                    when (val stored = source.stored(id)) {
                        is Outcome.Success -> stored.value
                        is Outcome.Failure -> before.cached
                    }
                page.value = page.value.copy(cached = cached)
                if (!force && !mangaStale(cached?.fetchedAt, clock.instant())) return@launch
                val answer = source.refresh(id)
                load = null
                when (answer) {
                    is Outcome.Success -> {
                        page.value = Page(cached = answer.value, coverAttempt = before.coverAttempt + 1)
                        loads.ended(null)
                    }
                    is Outcome.Failure -> {
                        val problem = MangaTitleProblem.ReadFailed(answer.error)
                        page.value = Page(cached, problem, coverAttempt = before.coverAttempt + 1)
                        if (cached != null) onRefreshFailed(answer.error)
                        loads.ended(answer.error)
                    }
                }
            }
        load?.start()
    }
}
