package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

@Serializable
data class BrowseKey(
    val list: BrowseList,
) : NavKey

// Search over the stand-in's titles until its data layer exists: no view model and no network. The field's text is
// held here and changed only by the field and inside an event.
@Composable
fun SearchRoute(
    standIn: SearchStandIn,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onBrowse: (BrowseList) -> Unit,
    onSignIn: () -> Unit,
) {
    val query = rememberTextFieldState()
    var recent by rememberSaveable(saver = recentSaver) { mutableStateOf(standIn.recent.take(MAX_RECENT_SEARCHES)) }
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    val text = query.text.toString().trim()
    val hits = remember(standIn, text) { if (text.isEmpty()) emptyList() else standIn.search(text) }
    val content =
        if (text.isEmpty()) {
            SearchContent.Start(recent)
        } else {
            SearchContent.Results(text, Paged.Shown(hits.map { resultRow(it.anime, it.entry?.values, graph.titleLanguage) }))
        }

    fun keep(searched: String) {
        if (searched.isNotEmpty()) recent = (listOf(searched) + recent).distinct().take(MAX_RECENT_SEARCHES)
    }
    SearchScreen(
        SearchState(query, expanded = false, content, sessionExpired = session == Session.Expired),
        onEvent = { event ->
            when (event) {
                SearchEvent.Submit -> keep(text)
                SearchEvent.Clear -> query.clearText()
                is SearchEvent.SearchAgain -> {
                    query.setTextAndPlaceCursorAtEnd(event.query)
                    keep(event.query)
                }
                is SearchEvent.Browse -> onBrowse(event.list)
                is SearchEvent.Open -> {
                    keep(text)
                    val hit = hits.firstOrNull { it.anime.id == event.id }
                    onOpenTitle(event.id, hit?.let { TitleSummary(it.anime, it.entry, airing = null, session = session) })
                }
                SearchEvent.LoadMore, SearchEvent.Retry -> Unit
                SearchEvent.SignIn -> onSignIn()
            }
        },
        snackbars = snackbars,
    )
}

// A browse list over the stand-in's titles, all on one page.
@Composable
fun BrowseRoute(
    standIn: SearchStandIn,
    key: BrowseKey,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onSignIn: () -> Unit,
) {
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    val hits = remember(standIn, key) { standIn.browse(key.list) }
    val rows = Paged.Shown(hits.map { resultRow(it.anime, it.entry?.values, graph.titleLanguage, it.rank) })
    BrowseScreen(
        BrowseState(key.list, rows, sessionExpired = session == Session.Expired),
        onEvent = { event ->
            when (event) {
                BrowseEvent.Back -> onBack()
                is BrowseEvent.Open -> {
                    val hit = hits.firstOrNull { it.anime.id == event.id }
                    onOpenTitle(event.id, hit?.let { TitleSummary(it.anime, it.entry, airing = null, session = session) })
                }
                BrowseEvent.LoadMore, BrowseEvent.Retry -> Unit
                BrowseEvent.SignIn -> onSignIn()
            }
        },
        snackbars = snackbars,
    )
}

private val recentSaver = listSaver<MutableState<List<String>>, String>(save = { it.value }, restore = { mutableStateOf(it) })
