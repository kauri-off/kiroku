package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.edit.EditSheetRoute
import io.github.kauri_off.kiroku.edit.editSheetViewModel
import io.github.kauri_off.kiroku.shell.LocalSuggestionsOwner
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RecommendationsKey(
    val id: Long,
) : NavKey {
    @Transient
    var opened: TitleSummary? = null
        private set

    constructor(id: Long, opened: TitleSummary?) : this(id) {
        this.opened = opened
    }
}

@Composable
fun RecommendationsRoute(
    key: RecommendationsKey,
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onSignIn: () -> Unit,
) {
    val owner = LocalSuggestionsOwner.current
    val recommendations =
        viewModel {
            RecommendationsViewModel(
                AnimeId(key.id),
                graph.anime,
                graph.list,
                graph.session,
                graph.connection,
                graph.clock,
                graph.titleLanguage,
                key.opened,
                adultSearchChoice = graph.adultSearchChoice,
                owner = owner,
                onRefreshFailed = { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it))) },
            )
        }
    val editor = editSheetViewModel(graph, messages)
    val state by recommendations.state.collectAsScreenState()
    CountAsShown(recommendations.shown)
    RecommendationsScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                RecommendationsEvent.Back -> onBack()
                RecommendationsEvent.SignIn -> onSignIn()
                is RecommendationsEvent.Open -> recommendations.summaryOf(event.id)?.let { onOpenTitle(event.id, it) }
                is RecommendationsEvent.Edit -> recommendations.summaryOf(event.id)?.let { editor.open(event.id, it.anime) }
                RecommendationsEvent.Reload -> recommendations.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
    EditSheetRoute(editor, messages, takes = { false })
}
