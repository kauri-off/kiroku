package io.github.kauri_off.kiroku.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowseViewModel(
    list: BrowseList,
    search: SearchRepository,
    connection: Connection,
    includeAdult: Flow<Boolean>,
) : ViewModel() {
    private val browse by lazy { search.openBrowse(list) }
    private val pages = MutableStateFlow(BrowseResults())
    private var adult: Boolean? = null
    private var pending: Job? = null

    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = ::loadFirst,
            shownAgain = ::loadFirst,
            again = ::retry,
        )

    val shown get() = loads.shown
    val state =
        includeAdult
            .flatMapLatest { enabled ->
                if (adult != enabled) {
                    adult = enabled
                    pending?.cancel()
                    pages.value = BrowseResults()
                    loads.started()
                    if (loads.isShowing) loadFirst()
                }
                pages
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), BrowseResults())

    private fun loadFirst() {
        if (pages.value.nextOffset == 0) loadMore()
    }

    fun loadMore() = load(retry = false)

    fun retry() = load(retry = true)

    private fun load(retry: Boolean) {
        val enabled = adult ?: return
        val page = pages.value
        val offset = page.nextOffset ?: return
        if (page.loading || (page.error != null) != retry) return
        pages.value = page.copy(loading = true, error = null)
        loads.started()
        pending =
            viewModelScope.launch {
                val answer = browse.load(offset, enabled)
                currentCoroutineContext().ensureActive()
                pages.value =
                    when (answer) {
                        is Outcome.Success ->
                            BrowseResults(
                                titles = (page.titles + answer.value.items).distinctBy { it.item.id },
                                nextOffset = answer.value.nextOffset,
                            )
                        is Outcome.Failure -> page.copy(error = answer.error, loading = false)
                    }
                loads.ended(pages.value.error)
            }
    }
}

internal data class BrowseResults(
    val titles: List<Ranked<Anime>> = emptyList(),
    val nextOffset: Int? = 0,
    val loading: Boolean = false,
    val error: DataError? = null,
) {
    fun rows(map: (Ranked<Anime>) -> ResultRow): Paged<ResultRow> {
        if (nextOffset == 0) return error?.let { Paged.Failed(it) } ?: Paged.Loading
        val more =
            when {
                loading -> More.Loading
                error != null -> More.Failed(error)
                nextOffset != null -> More.Ready(nextOffset)
                else -> More.None
            }
        return Paged.Shown(titles.map(map), more)
    }
}
