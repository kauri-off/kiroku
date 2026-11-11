package io.github.kauri_off.kiroku.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// The List as the app shell shows it: the shell's one snackbar host over the screen.
@Composable
fun HostedListScreen(
    state: ListState,
    onEvent: (ListEvent) -> Unit = {},
) {
    val snackbars = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
        ListScreen(state, onEvent, snackbars)
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
