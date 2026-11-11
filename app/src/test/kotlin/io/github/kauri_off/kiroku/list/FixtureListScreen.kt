package io.github.kauri_off.kiroku.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// The real screen, given a snackbar state of its own as the shell gives it one.
@Composable
fun FixtureListScreen(
    state: ListState,
    onEvent: (ListEvent) -> Unit = {},
) {
    ListScreen(state, onEvent, remember { SnackbarHostState() })
}
