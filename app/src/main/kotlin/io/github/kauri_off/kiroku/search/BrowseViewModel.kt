package io.github.kauri_off.kiroku.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal fun BrowseViewModel(
    list: BrowseList,
    search: SearchRepository,
    connection: Connection,
    includeAdult: Flow<Boolean?>,
    owner: SignInId? = null,
): BrowseViewModel<Anime> {
    val browse by lazy { owner?.let(search::openSuggestions) ?: search.openBrowse(list) }
    return BrowseViewModel({ offset, adult, _ -> browse.load(offset, adult) }, Anime::id, connection, includeAdult)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class BrowseViewModel<T>(
    private val read: suspend (Int, Boolean, () -> Boolean) -> Outcome<Page<Ranked<T>>>?,
    private val idOf: (T) -> Any,
    connection: Connection,
    includeAdult: Flow<Boolean?>,
    private val pauseWhenHidden: Boolean = false,
) : ViewModel() {
    private val pages = MutableStateFlow(BrowseResults<T>())
    private var adult: Boolean? = null
    private var pending: Job? = null
    private var beforeLoad: BrowseResults<T>? = null
    private var pausedRetry: Boolean? = null
    private var validated = false
    private var reconnect = false

    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            whenShown = ::resume,
            shownAgain = ::resume,
            hidden = { if (pauseWhenHidden) pause() },
            again = {
                if (validated) retry() else reconnect = true
            },
        )

    val shown get() = loads.shown
    val state =
        includeAdult
            .flatMapLatest { enabled ->
                validated = true
                if (adult != enabled) {
                    adult = enabled
                    pending?.cancel()
                    beforeLoad = null
                    pausedRetry = null
                    pages.value = BrowseResults()
                    loads.started()
                    reconnect = false
                }
                if (loads.isShowing) {
                    if (reconnect) retry() else resume()
                    reconnect = false
                }
                pages
            }.onCompletion { validated = false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), BrowseResults())

    private fun resume() {
        val retry = pausedRetry
        if (retry != null) {
            load(retry)
        } else if (pages.value.nextOffset == 0) {
            loadMore()
        }
    }

    private fun pause() {
        val before = beforeLoad ?: return
        pending?.cancel()
        rememberPaused(before)
    }

    private fun rememberPaused(before: BrowseResults<T>) {
        beforeLoad = null
        pausedRetry = before.error != null
        pages.value = before
    }

    fun loadMore() = load(retry = false)

    fun retry() = load(retry = true)

    private fun load(retry: Boolean) {
        if (!validated || (pauseWhenHidden && !loads.isShowing)) return
        val enabled = adult ?: return
        val page = pages.value
        val offset = page.nextOffset ?: return
        if (page.loading || (page.error != null) != retry) return
        pausedRetry = null
        beforeLoad = page
        pages.value = page.copy(loading = true, error = null)
        loads.started()
        pending =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val job = coroutineContext.job
                val answer = read(offset, enabled) { job.isActive && loads.isShowing }
                job.ensureActive()
                if (answer == null || (pauseWhenHidden && !loads.isShowing)) {
                    rememberPaused(page)
                    if (loads.isShowing) resume()
                    return@launch
                }
                beforeLoad = null
                pages.value =
                    when (answer) {
                        is Outcome.Success ->
                            BrowseResults(
                                titles = (page.titles + answer.value.items).distinctBy { idOf(it.item) },
                                nextOffset = answer.value.nextOffset,
                            )
                        is Outcome.Failure -> page.copy(error = answer.error, loading = false)
                    }
                loads.ended(pages.value.error)
            }
        pending?.start()
    }
}

internal data class BrowseResults<T>(
    val titles: List<Ranked<T>> = emptyList(),
    val nextOffset: Int? = 0,
    val loading: Boolean = false,
    val error: DataError? = null,
) {
    fun <R> rows(map: (Ranked<T>) -> R): Paged<R> {
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
