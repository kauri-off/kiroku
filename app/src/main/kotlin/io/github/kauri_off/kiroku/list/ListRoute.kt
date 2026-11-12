package io.github.kauri_off.kiroku.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.edit.EditSheetRoute
import io.github.kauri_off.kiroku.edit.EditSheetViewModel
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph

// The List with its edit sheet; the only place that knows the List's view model and where its rows lead.
@Composable
fun ListRoute(
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onOpenTitle: (AnimeId) -> Unit,
    onSearch: () -> Unit,
    onSignIn: () -> Unit,
) {
    val list = viewModel { ListViewModel(graph, createSavedStateHandle(), messages) }
    val editor = viewModel { EditSheetViewModel(graph, createSavedStateHandle()) }
    ListScreen(
        state = list.state.value,
        onEvent = { event ->
            when (event) {
                is ListEvent.Open -> onOpenTitle(event.id)
                is ListEvent.Edit -> editor.open(event.id)
                ListEvent.Search -> onSearch()
                ListEvent.SignIn -> onSignIn()
                else -> list.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
    EditSheetRoute(editor, messages)
}
