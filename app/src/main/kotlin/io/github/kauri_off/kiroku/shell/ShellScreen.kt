package io.github.kauri_off.kiroku.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.design.component.SessionBanner

data class ShellState(
    val selected: Destination,
    val sessionExpired: Boolean = false,
)

sealed interface ShellEvent {
    data class Select(
        val destination: Destination,
    ) : ShellEvent

    data object SignIn : ShellEvent
}

// The app's one snackbar host, above the bar and clear of the gesture bar; messages that outlive a screen go here.
val LocalSnackbars = staticCompositionLocalOf { SnackbarHostState() }

private val LocalSessionBanner = compositionLocalOf<(() -> Unit)?> { null }

// Every screen puts this directly under its top app bar; it shows the banner while the session is expired.
@Composable
fun SessionBannerPlace(modifier: Modifier = Modifier) {
    LocalSessionBanner.current?.let { SessionBanner(onSignIn = it, modifier = modifier) }
}

// The bar on phones and the rail on wide windows around the current destination's content.
@Composable
fun ShellScreen(
    state: ShellState,
    onEvent: (ShellEvent) -> Unit,
    snackbars: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { destination ->
                val selected = destination == state.selected
                item(
                    selected = selected,
                    onClick = { onEvent(ShellEvent.Select(destination)) },
                    icon = {
                        Icon(painterResource(if (selected) destination.selectedIcon else destination.icon), contentDescription = null)
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        val signIn = if (state.sessionExpired) ({ onEvent(ShellEvent.SignIn) }) else null
        CompositionLocalProvider(LocalSnackbars provides snackbars, LocalSessionBanner provides signIn) {
            Box(Modifier.fillMaxSize()) {
                content()
                SnackbarHost(
                    snackbars,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                )
            }
        }
    }
}
