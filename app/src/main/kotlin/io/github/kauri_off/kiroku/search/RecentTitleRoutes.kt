package io.github.kauri_off.kiroku.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleRemoval
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitleVisit
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.first

@Composable
internal fun RecordTitleVisit(
    graph: AppGraph,
    writes: ViewedTitleWrites,
    key: RecentTitleId,
    session: Session?,
    title: RecentTitle?,
) {
    val opening = viewModel { RecentTitleVisitViewModel(createSavedStateHandle()) }
    val visit by opening.visit.collectAsScreenState()
    val onTop = LocalOnTop.current
    val active = recentScope(graph)
    LaunchedEffect(graph, onTop, active) {
        if (onTop && active != null) opening.start(graph.recentTitles, writes, active, key)
    }
    val scope = active?.takeIf { session != null && it.owner == session.owner }
    LaunchedEffect(graph, onTop, scope, title, visit) {
        val order = visit ?: return@LaunchedEffect
        if (onTop && scope != null && title != null) {
            opening.record(graph.recentTitles, scope, title, order)
        }
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
): List<RecentTitleVisit> {
    if (scope == null || allowAdult == null) return emptyList()
    return key(graph, scope) {
        val titles by remember { graph.recentTitles.observe(scope) }.collectAsScreenState(initial = emptyList())
        titles.filter { visit ->
            (allowAdult || visit.title.safeForSearch == true) &&
                when (visit.key) {
                    is RecentTitleId.Anime -> !mangaOnly
                    is RecentTitleId.Manga -> graph.manga != null
                }
        }
    }
}

internal fun RecentTitleVisit.row(
    scope: RecentTitleScope,
    language: TitleLanguage,
    pending: Set<RecentTitleRemoval>,
): RecentTitleRow = RecentTitleRow(key, title.titles.preferred(language), order, pending.any { it.scope == scope && it.key == key })

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
