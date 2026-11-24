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
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal class SearchViewModel(
    private val search: SearchRepository,
    connection: Connection,
    saved: SavedStateHandle,
    private val includeAdult: () -> Boolean = { false },
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

    private val results = MutableStateFlow(SearchResults())
    private val request = MutableStateFlow<Load?>(null)
    private val loads = ShownLoads(viewModelScope, connection, whenShown = {}, again = ::retry)

    val shown get() = loads.shown
    val cachedState = results.asStateFlow()

    val state =
        flow {
            coroutineScope {
                launch { snapshotFlow { text }.collect(::changed) }
                launch { request.collectLatest { load -> if (load != null) read(load) } }
                emitAll(results)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), results.value)

    fun loadMore() = ask(retry = false)

    fun retry() = ask(retry = true)

    private fun changed(query: String) {
        if (results.value.query == query) return
        loads.started()
        results.value = SearchResults(query, nextOffset = 0, loading = query.isNotEmpty())
        request.value = if (query.isEmpty()) null else Load(query, offset = 0, waitForTyping = true)
    }

    private fun ask(retry: Boolean) {
        val shown = results.value
        if (shown.query != text || shown.query.isEmpty() || shown.loading || (shown.error != null) != retry) return
        val offset = shown.nextOffset ?: return
        results.value = shown.copy(loading = true, error = null)
        request.value = Load(shown.query, offset)
    }

    private suspend fun read(load: Load) {
        if (load.waitForTyping) delay(TYPING_PAUSE)
        loads.started()
        val answer = search.search(load.query, load.offset, includeAdult())
        currentCoroutineContext().ensureActive()
        if (load !== request.value) return
        val shown = results.value
        results.value =
            when (answer) {
                is Outcome.Success ->
                    shown.copy(
                        titles = (shown.titles + answer.value.items).distinctBy { it.id },
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

internal data class SearchResults(
    val query: String = "",
    val titles: List<Anime> = emptyList(),
    val nextOffset: Int? = null,
    val loading: Boolean = false,
    val error: DataError? = null,
) {
    fun rows(map: (Anime) -> ResultRow): Paged<ResultRow> {
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
