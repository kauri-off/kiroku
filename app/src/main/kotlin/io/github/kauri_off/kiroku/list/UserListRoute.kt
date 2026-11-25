package io.github.kauri_off.kiroku.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.AppGraph

// Another user's list, which no event of it can write; a row opens the title's page, where the entry is the person's own.
@Composable
fun UserListRoute(
    name: String,
    graph: AppGraph,
    // Says that a refresh of a list shown failed; the only thing the page tells the shell.
    onRefreshFailed: (DataError) -> Unit,
    snackbars: SnackbarHostState,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val list =
        viewModel {
            UserListViewModel(
                name,
                graph.user,
                graph.list.observeEntries(),
                graph.session,
                graph.connection,
                graph.clock,
                graph.titleLanguage,
                createSavedStateHandle(),
                onRefreshFailed,
                graph.background,
            )
        }
    val state by list.state.collectAsScreenState()
    CountAsShown(list.shown)
    ListScreen(
        state = state.copy(typed = list.filterInput),
        onEvent = { event ->
            when (event) {
                is ListEvent.Open -> onOpenTitle(event.id, list.summaryOf(event.id))
                ListEvent.Back -> onBack()
                ListEvent.SignIn -> onSignIn()
                else -> list.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
}
