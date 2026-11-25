package io.github.kauri_off.kiroku.search

import android.os.Bundle
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal fun SearchViewModel(
    search: SearchRepository,
    connection: Connection,
    saved: SavedStateHandle,
    includeAdult: Flow<Boolean> = flowOf(false),
): SearchViewModel<Anime> = SearchViewModel(search::search, Anime::id, connection, saved, includeAdult, openAnimeLinks = true)

internal class SearchViewModel<T>(
    private val search: suspend (String, Int, Boolean) -> Outcome<Page<T>>,
    private val idOf: (T) -> Any,
    connection: Connection,
    saved: SavedStateHandle,
    includeAdult: Flow<Boolean> = flowOf(false),
    private val openAnimeLinks: Boolean = false,
) : ViewModel() {
    val query =
        saved
            .get<Bundle>(QUERY)
            .let { state ->
                val text = state?.getString(TEXT).orEmpty()
                TextFieldState(text, TextRange(state?.getInt(START) ?: text.length, state?.getInt(END) ?: text.length))
            }.also { field ->
                saved.setSavedStateProvider(QUERY) {
                    Bundle().apply {
                        putString(TEXT, field.text.toString())
                        putInt(START, field.selection.start)
                        putInt(END, field.selection.end)
                    }
                }
            }

    val text: String get() = query.text.toString().trim()

    val linkId get() = linkId(text)

    private val results = MutableStateFlow(SearchResults<T>())
    private val request = MutableStateFlow<Load?>(null)
    private val loads = ShownLoads(viewModelScope, connection, whenShown = {}, again = ::retry)

    private var adult: Boolean? = null

    val shown get() = loads.shown
    val cachedState = results.asStateFlow()

    val state =
        flow {
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                launch {
                    combine(snapshotFlow { text }, includeAdult, ::Pair).collect { (query, enabled) ->
                        changed(query, enabled)
                        ready.complete(Unit)
                    }
                }
                ready.await()
                launch {
                    var pending: Job? = null
                    request.collect { load ->
                        pending?.cancel()
                        pending = load?.let { launch { read(it) } }
                    }
                }
                emitAll(results)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), results.value)

    fun loadMore() = ask(retry = false)

    fun retry() = ask(retry = true)

    private fun changed(
        query: String,
        enabled: Boolean,
    ) {
        val queryChanged = results.value.query != query
        if (!queryChanged && adult == enabled) return
        adult = enabled
        loads.started()
        val searchesText = query.isNotEmpty() && linkId(query) == null
        results.value = SearchResults(query, nextOffset = 0.takeIf { searchesText }, loading = searchesText, adult = enabled)
        request.value = if (searchesText) Load(query, offset = 0, enabled, waitForTyping = queryChanged) else null
    }

    private fun linkId(query: String) = if (openAnimeLinks) animeSearchLink(query) else null

    private fun ask(retry: Boolean) {
        val shown = results.value
        if (shown.query != text || shown.loading || (shown.error != null) != retry) return
        val enabled = adult ?: return
        val offset = shown.nextOffset ?: return
        results.value = shown.copy(loading = true, error = null)
        request.value = Load(shown.query, offset, enabled)
    }

    private suspend fun read(load: Load) {
        if (load.waitForTyping) delay(TYPING_PAUSE)
        loads.started()
        val answer = search(load.query, load.offset, load.adult)
        currentCoroutineContext().ensureActive()
        if (load !== request.value) return
        val shown = results.value
        results.value =
            when (answer) {
                is Outcome.Success ->
                    shown.copy(
                        titles = (shown.titles + answer.value.items).distinctBy(idOf),
                        nextOffset = answer.value.nextOffset,
                        loading = false,
                    )
                is Outcome.Failure -> shown.copy(error = answer.error, loading = false)
            }
        request.value = null
        loads.ended(results.value.error)
    }

    private class Load(
        val query: String,
        val offset: Int,
        val adult: Boolean,
        val waitForTyping: Boolean = false,
    )

    private companion object {
        val QUERY = notShown("query")
        val TEXT = notShown("text")
        val START = notShown("start")
        val END = notShown("end")
        val TYPING_PAUSE = 300.milliseconds
    }
}

internal data class SearchResults<T>(
    val query: String = "",
    val titles: List<T> = emptyList(),
    val nextOffset: Int? = null,
    val loading: Boolean = false,
    val error: DataError? = null,
    val adult: Boolean? = null,
) {
    fun <R> rows(map: (T) -> R): Paged<R> {
        if (titles.isEmpty() && loading && nextOffset == 0) return Paged.Loading
        if (titles.isEmpty() && error != null) return Paged.Failed(error)
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
