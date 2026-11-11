package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown

class ShellScreenshots : ScreenshotRegistry() {
    override val content = contentCase("list-selected") { Shell(ShellState(Destination.List)) }

    override val loading = notShown("The shell loads nothing itself; each destination's screen shows its own loading state")
    override val empty = notShown("The shell is never empty: its placeholders are the destinations' empty states")
    override val error = notShown("The shell has no request that can fail; each destination's screen shows its own errors")

    override val cases =
        listOf(
            case("snackbar") { Shell(ShellState(Destination.List), snackbar = true) },
            case("session-banner") { Shell(ShellState(Destination.List, sessionExpired = true)) },
            case("wide", window = WindowSize(widthDp = 840, heightDp = 600)) { Shell(ShellState(Destination.List)) },
        )
}

@Composable
private fun Shell(
    state: ShellState,
    snackbar: Boolean = false,
) {
    val snackbars = remember { SnackbarHostState() }
    if (snackbar) {
        val message = stringResource(R.string.shell_could_not_save)
        val retry = stringResource(R.string.design_retry)
        LaunchedEffect(Unit) { snackbars.showSnackbar(message, actionLabel = retry) }
    }
    ShellScreen(state, onEvent = {}, snackbars = snackbars) { PlaceholderScreen(state.selected) }
}
