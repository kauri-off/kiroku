package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.Composable

// The sheet of [editor], while one is open.
@Composable
fun EditSheetRoute(editor: EditSheetViewModel) {
    editor.sheet?.let { EditSheet(it, editor::onEvent) }
}
