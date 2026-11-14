package io.github.kauri_off.kiroku.signin

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.data.signin.CustomTabBrowser
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object SignInKey : NavKey

// The sign-in screen; the only place that knows its view model, the browser's activity and where the screen leads.
@Composable
fun SignInRoute(
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val model = viewModel { SignInViewModel(graph.signIn, graph.session, account = graph is SignInOwner) }
    val state by model.state.collectAsScreenState()
    val signedIn by model.signedIn.collectAsScreenState()
    LaunchedEffect(signedIn) {
        if (signedIn) onSignedIn()
    }
    val activity = LocalActivity.current
    // A start holds the activity until the browser is open, so it ends with this screen's composition, never later.
    val scope = rememberCoroutineScope()
    SignInScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                SignInEvent.SignIn -> activity?.let { scope.launch { model.start(CustomTabBrowser(it)) } }
                SignInEvent.Browse -> onBrowse()
                SignInEvent.Back -> onBack()
            }
        },
        snackbars = snackbars,
    )
}

// The app acts on what the sign-in asks of it, and says so, while it is shown.
@Composable
fun SignInTurns(
    graph: AppGraph,
    navigator: ShellNavigator,
) {
    val handOver = viewModel { SignInHandOver(graph.signIn) }
    val turn by handOver.turn.collectAsScreenState()
    LaunchedEffect(turn) {
        turn?.let {
            navigator.follow(it)
            handOver.taken(it)
        }
    }
}

private fun ShellNavigator.follow(turn: SignInTurn) =
    when (turn) {
        SignInTurn.Redirected -> if (backStack.last() != SignInKey) open(SignInKey) else Unit
        SignInTurn.SignedIn -> signedIn()
    }

// A finished sign-in leaves the List alone on its stack, and no sign-in screen anywhere.
fun ShellNavigator.signedIn() {
    remove(SignInKey)
    home(Destination.List)
}

// "Browse without an account" goes to Search, and leaves no sign-in screen behind.
fun ShellNavigator.browse() {
    remove(SignInKey)
    if (current != Destination.Search) select(Destination.Search)
}
