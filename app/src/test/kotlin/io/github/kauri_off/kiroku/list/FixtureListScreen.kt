package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.rememberHostShowing

internal val userListNoMatches =
    UserListFixtureStates.watching.let { state ->
        val query = "zzzznotfound"
        state.copy(filter = query, content = ListFixtureStates.tabs(Fixtures.reference.otherUserEntries, readOnly = true, filter = query))
    }

// The real screen, given a snackbar state of its own as the shell gives it one, showing [message] if there is one.
@Composable
fun FixtureListScreen(
    state: ListState,
    message: ShellMessage? = null,
    onEvent: (ListEvent) -> Unit = {},
) {
    ListScreen(state, onEvent, rememberHostShowing(message))
}
