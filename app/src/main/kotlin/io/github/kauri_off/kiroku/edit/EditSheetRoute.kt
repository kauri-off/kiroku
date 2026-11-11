package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.shell.ShellMessages

// The sheet of [editor], while one is open. On the screen on top it also opens for a snackbar's "Change" it [takes].
@Composable
fun EditSheetRoute(
    editor: EditSheetViewModel,
    messages: ShellMessages,
    takes: (AnimeId) -> Boolean = { true },
) {
    val request = messages.editRequest
    val onTop = LocalOnTop.current
    LaunchedEffect(request, onTop) {
        if (request != null && onTop && takes(request)) {
            editor.open(request)
            messages.editTaken(request)
        }
    }
    editor.sheet?.let { EditSheet(it, editor::onEvent) }
}
