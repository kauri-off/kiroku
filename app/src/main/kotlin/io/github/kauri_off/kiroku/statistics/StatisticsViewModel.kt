package io.github.kauri_off.kiroku.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserLists
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.readAgainOnRetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// One statistics page: the signed-in user's over the stored list, another user's over the list their profile read.
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val page: StatisticsPage,
    // Another user's name, or null for the signed-in user.
    private val name: String?,
    session: SessionRepository,
    private val list: ListSource,
    private val users: UserLists,
    connection: Connection,
    background: CoroutineDispatcher,
) : ViewModel() {
    private val sessions = session.observeSession()
    private val retries = MutableStateFlow(0)
    private val shownOnce = MutableStateFlow(false)
    private var load: Job? = null

    // The list the statistics shown were computed over: a list held is the same object in every state that holds it.
    private var counted: Pair<List<ListEntry>, UserStatistics>? = null

    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = { shownOnce.value = true },
            again = { refresh() },
        )

    // Collected while the page is shown; nothing of another user is read before then.
    val shown: StateFlow<Unit> get() = loads.shown

    val state: StateFlow<StatisticsState> =
        combine(name?.let(::otherContent) ?: ownContent(), sessions) { content, session ->
            StatisticsState(page, name, content, sessionExpired = session is Session.Expired)
        }.flowOn(background)
            .stateIn(viewModelScope, WhileShown, StatisticsState(page, name, StatisticsContent.Loading))

    fun onEvent(event: StatisticsEvent) {
        if (event != StatisticsEvent.Retry) return
        retries.update { it + 1 }
        refresh()
    }

    // The stored list, so an edit changes the statistics at once and they show offline.
    private fun ownContent(): Flow<StatisticsContent> =
        sessions
            .map { it == Session.SignedOut }
            .distinctUntilChanged()
            .flatMapLatest { signedOut ->
                if (signedOut) {
                    flowOf(StatisticsContent.SignedOut)
                } else {
                    list
                        .observeEntries()
                        .map<List<ListEntry>, StatisticsContent> { StatisticsContent.Shown(statistics(it)) }
                        .readAgainOnRetry(retries) { StatisticsContent.Failed(it) }
                }
            }

    // The keeper's one read of the user, which the profile made; a list not whole yet shows how far it is.
    private fun otherContent(name: String): Flow<StatisticsContent> =
        flow {
            emit(StatisticsContent.Loading)
            shownOnce.first { it }
            users.observeList(name).collect { held ->
                when (held) {
                    is UserListState.None -> loads.ended(held.error)
                    is UserListState.Whole -> if (held.again == null) loads.ended(null)
                    is UserListState.Reading -> Unit
                }
                emit(content(held))
            }
        }

    private fun content(held: UserListState): StatisticsContent =
        when (held) {
            is UserListState.Reading -> StatisticsContent.Reading(UserListProgress(held.entries.size, held.total))
            is UserListState.Whole -> StatisticsContent.Shown(statistics(held.entries))
            is UserListState.None -> StatisticsContent.Failed(held.error)
        }

    private fun statistics(entries: List<ListEntry>): UserStatistics =
        counted?.takeIf { it.first === entries }?.second ?: statisticsOf(entries).also { counted = entries to it }

    // Retry, or the connection back after a read failed for want of it: the list is read again, or the read joined.
    private fun refresh() {
        val name = name ?: return
        if (load?.isActive == true) return
        load =
            viewModelScope.launch {
                loads.started()
                loads.ended((users.refreshList(name) as? Outcome.Failure)?.error)
            }
    }
}
