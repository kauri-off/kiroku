package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.edit.EditSheetRoute
import io.github.kauri_off.kiroku.edit.editSheetViewModel
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class BrowseKey(
    val list: BrowseList,
) : NavKey

@Composable
fun SearchRoute(
    standIn: SearchStandIn?,
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onBrowse: (BrowseList) -> Unit,
    onSignIn: () -> Unit,
) {
    val search = viewModel { SearchViewModel(graph.search, graph.connection, createSavedStateHandle()) { graph.showAdultInSearch } }
    CountAsShown(search.shown)
    val query = search.query
    var recent by rememberSaveable(saver = recentSaver) { mutableStateOf(standIn?.recent.orEmpty().take(MAX_RECENT_SEARCHES)) }
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    val listed by listedEntries(graph)
    val editor = editSheetViewModel(graph, messages)
    val text = search.text
    val resultState = if (LocalOnTop.current) search.state else search.cachedState
    val results by resultState.collectAsScreenState()
    val shown = results.takeIf { it.query == text }
    val content =
        when {
            text.isEmpty() -> SearchContent.Start(recent)
            shown == null -> SearchContent.Results(text, Paged.Loading)
            else -> {
                val rows =
                    remember(shown, listed, graph.titleLanguage) {
                        shown.rows { resultRow(it, listed?.get(it.id)?.values, graph.titleLanguage) }
                    }
                SearchContent.Results(text, rows)
            }
        }

    fun keep(searched: String) {
        if (searched.isNotEmpty()) recent = (listOf(searched) + recent).distinct().take(MAX_RECENT_SEARCHES)
    }
    SearchScreen(
        SearchState(query, expanded = false, content, sessionExpired = session is Session.Expired, editable = session.hasList()),
        onEvent = { event ->
            when (event) {
                SearchEvent.Submit -> keep(search.text)
                SearchEvent.Clear -> query.clearText()
                is SearchEvent.SearchAgain -> {
                    query.setTextAndPlaceCursorAtEnd(event.query)
                    keep(event.query)
                }
                is SearchEvent.Browse -> onBrowse(event.list)
                is SearchEvent.Open -> {
                    keep(text)
                    val anime = shown?.titles?.firstOrNull { it.id == event.id }
                    onOpenTitle(event.id, anime?.let { TitleSummary(it, listed?.get(it.id), airing = null, session = session) })
                }
                is SearchEvent.Edit -> editor.open(event.id, shown?.titles?.firstOrNull { it.id == event.id })
                SearchEvent.LoadMore -> search.loadMore()
                SearchEvent.Retry -> search.retry()
                SearchEvent.SignIn -> onSignIn()
            }
        },
        snackbars = snackbars,
    )
    EditSheetRoute(editor, messages, takes = { false })
}

@Composable
fun BrowseRoute(
    key: BrowseKey,
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onSignIn: () -> Unit,
) {
    val browse = viewModel { BrowseViewModel(key.list, graph.search, graph.connection) { graph.showAdultInSearch } }
    CountAsShown(browse.shown)
    val results by browse.state.collectAsScreenState()
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    val listed by listedEntries(graph)
    val editor = editSheetViewModel(graph, messages)
    val language = graph.titleLanguage
    val rows =
        remember(results, listed, language) {
            results.rows { resultRow(it.item, listed?.get(it.item.id)?.values, language, it.position) }
        }
    BrowseScreen(
        BrowseState(key.list, rows, sessionExpired = session is Session.Expired, editable = session.hasList()),
        onEvent = { event ->
            when (event) {
                BrowseEvent.Back -> onBack()
                is BrowseEvent.Open -> {
                    val anime = results.titles.firstOrNull { it.item.id == event.id }?.item
                    onOpenTitle(event.id, anime?.let { TitleSummary(it, listed?.get(event.id), airing = null, session = session) })
                }
                is BrowseEvent.Edit -> editor.open(event.id, results.titles.firstOrNull { it.item.id == event.id }?.item)
                BrowseEvent.LoadMore -> browse.loadMore()
                BrowseEvent.Retry -> browse.retry()
                BrowseEvent.SignIn -> onSignIn()
            }
        },
        snackbars = snackbars,
        autoLoadMore = LocalOnTop.current,
    )
    EditSheetRoute(editor, messages, takes = { false })
}

// A signed-in or expired session has a list to edit; signed out there is none, and nothing a save could reach.
private fun Session?.hasList(): Boolean = this is Session.SignedIn || this is Session.Expired

// The user's list as it is now, so a status changed elsewhere in the app shows here; null until it is read.
@Composable
private fun listedEntries(graph: AppGraph): State<Map<AnimeId, ListEntry>?> =
    remember(graph) {
        graph.list
            .observeEntries()
            .map<List<ListEntry>, Map<AnimeId, ListEntry>?> { entries -> entries.associateBy { it.anime.id } }
            .catch { emit(null) }
    }.collectAsScreenState(initial = null)

private val recentSaver = listSaver<MutableState<List<String>>, String>(save = { it.value }, restore = { mutableStateOf(it) })
