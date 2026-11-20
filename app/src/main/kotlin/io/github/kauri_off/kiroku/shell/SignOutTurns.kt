package io.github.kauri_off.kiroku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.wiring.AppGraph

// A sign-out, wherever it was made, closes every screen and leaves the app as a start without a session does.
@Composable
fun SignOutTurns(
    graph: AppGraph,
    navigator: ShellNavigator,
    messages: ShellMessages,
) {
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    // Saved, so a sign-out made while the window was gone is still one when it comes back.
    var hadSession by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(session) {
        when (session) {
            null -> Unit
            Session.SignedOut ->
                if (hadSession) {
                    hadSession = false
                    navigator.restart()
                    messages.clear()
                }
            is Session.SignedIn, is Session.Expired -> hadSession = true
        }
    }
}
