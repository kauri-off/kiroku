package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

// A snackbar host with [message] on it, as the shell shows a screen's message.
@Composable
fun rememberHostShowing(message: ShellMessage?): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    val text = message?.text?.let { text(it) }
    val action = message?.action?.let { actionLabel(it) }
    LaunchedEffect(message) { if (text != null) host.show(text, action) }
    return host
}
