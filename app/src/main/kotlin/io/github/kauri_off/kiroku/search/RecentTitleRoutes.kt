package io.github.kauri_off.kiroku.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.shell.show
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.first

@Composable
internal fun RecordTitleVisit(
    graph: AppGraph,
    session: Session?,
    title: RecentTitle?,
) {
    var openingOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var visit by rememberSaveable { mutableStateOf<Long?>(null) }
    val onTop = LocalOnTop.current
    val scope = recentScope(graph)?.takeIf { session != null && it.owner == session.owner }
    LaunchedEffect(graph, onTop, scope, title) {
        if (!onTop || scope == null) return@LaunchedEffect
        if (openingOwner == null) openingOwner = scope.key
        if (title == null || openingOwner != scope.key) return@LaunchedEffect
        val order = visit ?: graph.recentTitles.reserveVisit(scope)?.also { visit = it } ?: return@LaunchedEffect
        graph.recentTitles.record(scope, title, order)
    }
}

@Composable
internal fun recentScope(graph: AppGraph): RecentTitleScope? {
    val scope by remember(graph) { graph.recentTitles.observeScope() }.collectAsScreenState(initial = null)
    return scope
}

@Composable
internal fun recentOpeningIsCurrent(
    graph: AppGraph,
    owner: String?,
): Boolean = owner == null || recentScope(graph)?.key == owner

@Composable
internal fun recentTitles(
    graph: AppGraph,
    scope: RecentTitleScope?,
    allowAdult: Boolean?,
    mangaOnly: Boolean = false,
): List<RecentTitle> {
    if (scope == null || allowAdult == null) return emptyList()
    return key(graph, scope) {
        val titles by remember { graph.recentTitles.observe(scope) }.collectAsScreenState(initial = emptyList())
        titles.filter { title ->
            (allowAdult || title.safeForSearch == true) &&
                when (title.key) {
                    is RecentTitleId.Anime -> !mangaOnly
                    is RecentTitleId.Manga -> graph.manga != null
                }
        }
    }
}

internal suspend fun AppGraph.removeViewedTitle(
    scope: RecentTitleScope,
    key: RecentTitleId,
    snackbars: SnackbarHostState,
    message: String,
    retry: String,
) {
    while (!recentTitles.remove(scope, key)) {
        if (recentTitles.observeScope().first() != scope) return
        if (snackbars.show(message, retry) != SnackbarResult.ActionPerformed) return
    }
}

class RecentTitleOpening internal constructor(
    val title: RecentTitle,
    val session: Session,
    val language: TitleLanguage,
    val scope: RecentTitleScope,
)

internal suspend fun AppGraph.openRecent(
    opening: RecentTitleOpening,
    open: (RecentTitleOpening) -> Unit,
) {
    if (opening.title.safeForSearch != true && !showAdultInSearch.first()) return
    if (recentTitles.observeScope().first() != opening.scope || session.observeSession().first().owner != opening.scope.owner) return
    open(opening)
}
