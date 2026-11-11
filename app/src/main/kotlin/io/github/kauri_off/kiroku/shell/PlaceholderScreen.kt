package io.github.kauri_off.kiroku.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SessionBanner

data class PlaceholderState(
    val destination: Destination,
    val sessionExpired: Boolean = false,
)

sealed interface PlaceholderEvent {
    data object SignIn : PlaceholderEvent
}

// Stands in for a destination's screen until that screen is built.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    state: PlaceholderState,
    onEvent: (PlaceholderEvent) -> Unit,
    snackbars: SnackbarHostState,
) {
    val title =
        when (state.destination) {
            Destination.List -> R.string.shell_my_list
            Destination.Search -> R.string.shell_search
            Destination.Profile -> R.string.shell_profile
        }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(title)) })
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(PlaceholderEvent.SignIn) })
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        ScreenState(ScreenState.Empty(stringResource(R.string.shell_not_in_this_build)), Modifier.padding(padding))
    }
}
