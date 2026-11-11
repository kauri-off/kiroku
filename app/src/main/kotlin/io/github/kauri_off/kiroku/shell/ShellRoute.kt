package io.github.kauri_off.kiroku.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

// The entries of every destination: what each key of a back stack shows.
typealias ShellEntries = EntryProviderScope<NavKey>.(ShellNavigator) -> Unit

val placeholderEntries: ShellEntries = {
    entry<ListRoot> { PlaceholderScreen(Destination.List) }
    entry<SearchRoot> { PlaceholderScreen(Destination.Search) }
    entry<ProfileRoot> { PlaceholderScreen(Destination.Profile) }
}

// Only the destinations' stacks change here; each destination's screens and their state survive a switch.
@Composable
fun ShellRoute(
    sessionExpired: Boolean = false,
    onSignIn: () -> Unit = {},
    navigator: ShellNavigator = rememberShellNavigator(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    entries: ShellEntries = placeholderEntries,
) {
    val screens = rememberSaveableStateHolder()
    BackHandler(enabled = navigator.backLeavesRoot) { navigator.back() }
    ShellScreen(
        state = ShellState(navigator.current, sessionExpired),
        onEvent = { event ->
            when (event) {
                is ShellEvent.Select -> navigator.select(event.destination)
                ShellEvent.SignIn -> onSignIn()
            }
        },
        snackbars = snackbars,
    ) {
        val destination = navigator.current
        screens.SaveableStateProvider(destination.name) {
            NavDisplay(
                backStack = navigator.stackOf(destination),
                onBack = { navigator.back() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                transitionSpec = { instant },
                popTransitionSpec = { instant },
                predictivePopTransitionSpec = { instant },
                entryProvider = entryProvider { entries(navigator) },
            )
        }
    }
}

// Screens change without animation until the transitions of ARCHITECTURE section 8.5 are built.
private val instant = ContentTransform(EnterTransition.None, ExitTransition.None)
