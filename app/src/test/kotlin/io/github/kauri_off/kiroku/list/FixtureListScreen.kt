package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.rememberHostShowing

internal val userListNoMatches = UserListFixtureStates.watching.withFilter("zzzznotfound")

// The real screen, given a snackbar state of its own as the shell gives it one, showing [message] if there is one.
@Composable
fun FixtureListScreen(
    state: ListState,
    message: ShellMessage? = null,
    onEvent: (ListEvent) -> Unit = {},
) {
    ListScreen(state, onEvent, rememberHostShowing(message))
}
