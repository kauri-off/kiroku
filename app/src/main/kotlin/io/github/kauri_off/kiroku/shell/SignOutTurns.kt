package io.github.kauri_off.kiroku.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
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
    var legacyHadSession by rememberSaveable(saver = sessionMarkerSaver) { mutableStateOf(false) }
    LaunchedEffect(session, navigator) {
        when (session) {
            null -> Unit
            Session.SignedOut -> {
                val hadSession = navigator.hadSession ?: legacyHadSession
                navigator.hadSession = false
                legacyHadSession = false
                if (hadSession) {
                    navigator.restart()
                    messages.clear()
                }
            }
            is Session.SignedIn, is Session.Expired -> {
                navigator.hadSession = true
                legacyHadSession = true
            }
        }
    }
}

private val sessionMarkerSaver =
    Saver<MutableState<Boolean>, Any>(
        save = { it.value },
        restore = { saved ->
            val value = if (saved is MutableState<*>) saved.value else saved
            (value as? Boolean)?.let { mutableStateOf(it) }
        },
    )
