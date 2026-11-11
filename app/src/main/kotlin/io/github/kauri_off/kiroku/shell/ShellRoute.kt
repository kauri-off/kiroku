package io.github.kauri_off.kiroku.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.kauri_off.kiroku.notShown

// What each key shows. Every screen gets the shell's one snackbar host and draws it in its own scaffold.
typealias ShellEntries = EntryProviderScope<NavKey>.(navigator: ShellNavigator, snackbars: SnackbarHostState) -> Unit

fun placeholderEntries(
    sessionExpired: Boolean = false,
    onSignIn: () -> Unit = {},
): ShellEntries =
    { _, snackbars ->
        entry<ListRoot> { Placeholder(Destination.List, sessionExpired, onSignIn, snackbars) }
        entry<SearchRoot> { Placeholder(Destination.Search, sessionExpired, onSignIn, snackbars) }
        entry<ProfileRoot> { Placeholder(Destination.Profile, sessionExpired, onSignIn, snackbars) }
    }

@Composable
private fun Placeholder(
    destination: Destination,
    sessionExpired: Boolean,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
) = PlaceholderScreen(PlaceholderState(destination, sessionExpired), { onSignIn() }, snackbars)

// One NavDisplay over the List's stack and the current destination's, so Back from another root shows the List.
@Composable
fun ShellRoute(
    navigator: ShellNavigator = rememberShellNavigator(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    entries: ShellEntries = remember { placeholderEntries() },
) {
    val decorators =
        listOf(
            rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
            rememberViewModelStoreNavEntryDecorator<NavKey>(),
        )
    val provider = entryProvider { entries(navigator, snackbars) }
    val decorated =
        Destination.entries.associateWith { destination ->
            val stack = navigator.stackOf(destination).mapIndexed { depth, key -> provider(key).scopedTo(key, destination, depth) }
            rememberDecoratedNavEntries(stack, decorators)
        }
    ShellScreen(
        state = ShellState(navigator.current),
        onEvent = { event ->
            when (event) {
                is ShellEvent.Select -> navigator.select(event.destination)
            }
        },
    ) {
        NavDisplay(
            entries = navigator.visible.flatMap { decorated.getValue(it) },
            transitionSpec = { instant },
            popTransitionSpec = { instant },
            predictivePopTransitionSpec = { instant },
            onBack = { navigator.back() },
        )
    }
}

// Each entry's saved state and view models belong to its place in one destination's stack, never to its key alone.
private fun NavEntry<NavKey>.scopedTo(
    key: NavKey,
    destination: Destination,
    depth: Int,
): NavEntry<NavKey> {
    val entry = this
    return NavEntry(key, notShown("${destination.name}/$depth/$contentKey"), metadata) { entry.Content() }
}

// Screens change without animation until the transitions of ARCHITECTURE section 8.5 are built.
private val instant = ContentTransform(EnterTransition.None, ExitTransition.None)
