package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph

// The edit sheet of the screen whose route calls this.
@Composable
fun editSheetViewModel(
    graph: AppGraph,
    messages: ShellMessages,
): EditSheetViewModel =
    viewModel {
        EditSheetViewModel(graph.list, graph.anime, graph.airing, graph.clock, graph.titleLanguage, createSavedStateHandle(), messages)
    }

// The sheet of [editor], while one is open. On the screen on top it also opens for a snackbar's "Change" it [takes].
@Composable
fun EditSheetRoute(
    editor: EditSheetViewModel,
    messages: ShellMessages,
    takes: (AnimeId) -> Boolean = { true },
) {
    LaunchedEffect(editor) { editor.restore() }
    val request by messages.editRequest.collectAsScreenState()
    val onTop = LocalOnTop.current
    LaunchedEffect(request, onTop) {
        val id = request
        if (id != null && onTop && takes(id)) {
            editor.open(id)
            messages.editTaken(id)
        }
    }
    editor.sheet?.let { EditSheet(it, editor::onEvent) }
}
