package io.github.kauri_off.kiroku.design.component

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

// False for a screen composed under the top one, as during a predictive back gesture.
val LocalOnTop = compositionLocalOf { true }

// The shell's snackbar host in a screen's scaffold, drawn only by the screen on top so a message shows once.
@Composable
fun ScreenSnackbarHost(snackbars: SnackbarHostState) {
    if (LocalOnTop.current) SnackbarHost(snackbars)
}
