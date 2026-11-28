package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.manga.MangaTitleSummary
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
internal data object MangaSearchKey : NavKey

@Serializable
internal data object MangaRankingKey : NavKey

@Composable
internal fun MangaSearchRoute(
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onRanking: () -> Unit,
    onOpen: (MangaTitleSummary) -> Unit,
    onSignIn: () -> Unit,
    onOpenRecent: (RecentTitleOpening) -> Unit,
) {
    val source = graph.mangaCatalogue ?: return
    val search =
        viewModel {
            SearchViewModel(source::search, MangaDetails::id, graph.connection, createSavedStateHandle(), graph.showAdultInSearch)
        }
    CountAsShown(search.shown)
    val query = search.query
    val text = search.text
    val resultState = if (LocalOnTop.current) search.state else search.cachedState
    val results by resultState.collectAsScreenState()
    val adult by remember(graph) { graph.showAdultInSearch }.collectAsScreenState(initial = null)
    val language by remember(graph) { graph.titleLanguage }.collectAsScreenState(TitleLanguage.Romaji)
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    var recent by rememberSaveable(saver = recentSaver) { mutableStateOf(emptyList()) }
    val historyScope = recentScope(graph)?.takeIf { session != null && it.owner == session?.owner }
    val visits = if (text.isEmpty()) recentTitles(graph, historyScope, adult, mangaOnly = true) else emptyList()
    val scope = rememberCoroutineScope()
    val removeFailed = stringResource(R.string.search_remove_viewed_failed)
    val retry = stringResource(R.string.design_retry)
    val shown = results.takeIf { it.query == text && it.adult == adult }
    val content =
        if (text.isEmpty()) {
            SearchContent.MangaStart(recent, visits.map { RecentTitleRow(it.key, it.titles.preferred(language)) })
        } else {
            val rows = remember(shown, language) { shown?.rows { MangaResultRow(it, it.titles.preferred(language)) } ?: Paged.Loading }
            SearchContent.MangaResults(text, rows)
        }

    fun keep(searched: String) {
        if (searched.isNotEmpty()) recent = (listOf(searched) + recent).distinct().take(MAX_RECENT_SEARCHES)
    }
    SearchScreen(
        SearchState(query, expanded = false, content, sessionExpired = session is Session.Expired),
        onEvent = { event ->
            when (event) {
                SearchEvent.Back -> onBack()
                SearchEvent.Submit -> keep(search.text)
                SearchEvent.Clear -> query.clearText()
                is SearchEvent.RemoveRecent -> recent = recent - event.query
                is SearchEvent.RemoveViewed -> {
                    val viewedScope = historyScope ?: return@SearchScreen
                    if (visits.none { it.key == event.key }) return@SearchScreen
                    scope.launch { graph.removeViewedTitle(viewedScope, event.key, snackbars, removeFailed, retry) }
                }
                is SearchEvent.OpenRecent -> {
                    val current = session ?: return@SearchScreen
                    val openingScope = historyScope ?: return@SearchScreen
                    val title = visits.firstOrNull { it.key == event.key } ?: return@SearchScreen
                    scope.launch { graph.openRecent(RecentTitleOpening(title, current, language, openingScope), onOpenRecent) }
                }
                is SearchEvent.SearchAgain -> {
                    query.setTextAndPlaceCursorAtEnd(event.query)
                    keep(event.query)
                }
                SearchEvent.BrowseManga -> onRanking()
                is SearchEvent.OpenManga -> {
                    shown?.titles?.firstOrNull { it.id == event.id }?.let {
                        keep(text)
                        onOpen(MangaTitleSummary(it, language))
                    }
                }
                SearchEvent.LoadMore -> search.loadMore()
                SearchEvent.Retry -> search.retry()
                SearchEvent.SignIn -> onSignIn()
                SearchEvent.Manga, is SearchEvent.Browse, is SearchEvent.Open, is SearchEvent.Edit -> Unit
            }
        },
        snackbars = snackbars,
    )
}

@Composable
internal fun MangaRankingRoute(
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpen: (MangaTitleSummary) -> Unit,
    onSignIn: () -> Unit,
) {
    val source = graph.mangaCatalogue ?: return
    val browse =
        viewModel {
            BrowseViewModel(source::ranking, MangaDetails::id, graph.connection, graph.showAdultInSearch, pauseWhenHidden = true)
        }
    CountAsShown(browse.shown)
    val results by browse.state.collectAsScreenState()
    val language by remember(graph) { graph.titleLanguage }.collectAsScreenState(TitleLanguage.Romaji)
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    val rows =
        remember(results, language) {
            results.rows { MangaResultRow(it.item, it.item.titles.preferred(language), it.position) }
        }
    MangaRankingScreen(
        MangaRankingState(rows, sessionExpired = session is Session.Expired),
        onEvent = { event ->
            when (event) {
                MangaRankingEvent.Back -> onBack()
                MangaRankingEvent.SignIn -> onSignIn()
                MangaRankingEvent.LoadMore -> browse.loadMore()
                MangaRankingEvent.Retry -> browse.retry()
                is MangaRankingEvent.Open ->
                    results.titles.firstOrNull { it.item.id == event.id }?.let {
                        onOpen(MangaTitleSummary(it.item, language))
                    }
            }
        },
        snackbars = snackbars,
        autoLoadMore = LocalOnTop.current,
    )
}
