package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.shell.LocalSuggestionsOwner
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph

// The edit sheet of the screen whose route calls this.
@Composable
fun editSheetViewModel(
    graph: AppGraph,
    messages: ShellMessages,
): EditSheetViewModel {
    val owner = LocalSuggestionsOwner.current
    return viewModel {
        EditSheetViewModel(
            graph.list,
            graph.anime,
            graph.airing,
            graph.clock,
            graph.titleLanguage,
            createSavedStateHandle(),
            messages,
            owner,
        )
    }
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
    val owner = LocalSuggestionsOwner.current
    LaunchedEffect(request, onTop, owner) {
        val edit = request
        if (edit != null && onTop && (edit.owner == null || edit.owner == owner) && takes(edit.id)) {
            editor.open(edit.id)
            messages.editTaken(edit)
        }
    }
    editor.sheet?.let { EditSheet(it, editor::onEvent) }
}
