package io.github.kauri_off.kiroku.signout

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.signin.SignInOwner
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.launch

sealed interface SignOutState {
    // Demo data has no account to sign out of.
    data object Demo : SignOutState

    // The account's session; null until its tokens were read.
    data class Account(
        val session: Session?,
    ) : SignOutState
}

sealed interface SignOutEvent {
    data object SignOut : SignOutEvent
}

// Signs the account out until Settings exist; the sign-out runs in the session's own scope and outlives the menu.
@Composable
fun SignOutRoute() {
    val graph = LocalContext.current.appGraph
    val session by remember(graph) { graph.session.observeSession() }.collectAsScreenState(initial = null)
    val scope = rememberCoroutineScope()
    val state = if (graph is SignInOwner) SignOutState.Account(session) else SignOutState.Demo
    SignOutSection(state) { event ->
        when (event) {
            SignOutEvent.SignOut -> scope.launch { graph.session.signOut() }
        }
    }
}

// Where the account's sign-in stands, and Sign out while there is one to end.
@Composable
fun SignOutSection(
    state: SignOutState,
    onEvent: (SignOutEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.sign_out_title))
    SectionDetail(state.detail)
    if (state is SignOutState.Account && state.session != Session.SignedOut) {
        OutlinedButton(
            onClick = { onEvent(SignOutEvent.SignOut) },
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Text(stringResource(R.string.sign_out_sign_out))
        }
    }
}

private val SignOutState.detail: Int
    get() =
        when (this) {
            SignOutState.Demo -> R.string.sign_out_demo
            is SignOutState.Account ->
                when (session) {
                    null -> R.string.sign_out_not_read
                    Session.SignedOut -> R.string.sign_out_signed_out
                    is Session.SignedIn -> R.string.sign_out_signed_in
                    is Session.Expired -> R.string.sign_out_expired
                }
        }
