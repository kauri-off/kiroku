package io.github.kauri_off.kiroku.shell

import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.list.ListScreen
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.title.TitleFixtures
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.title.TitleScreen

class ShellScreenshots : ScreenshotRegistry() {
    override val content = contentCase("list-selected") { Shell(ShellState(Destination.List)) }

    override val loading = notShown("The shell loads nothing itself; each destination's screen shows its own loading state")
    override val empty = notShown("The shell is never empty: its placeholders are the destinations' empty states")
    override val error = notShown("The shell has no request that can fail; each destination's screen shows its own errors")

    override val cases =
        listOf(
            case("snackbar") { Shell(ShellState(Destination.List), snackbar = true) },
            case("session-banner") { Shell(ShellState(Destination.List), sessionExpired = true) },
            case("wide", window = WindowSize(widthDp = 840, heightDp = 600)) { Shell(ShellState(Destination.List)) },
            case("list") { Screens(remember { navigatorOf() }) },
            case("title") { Screens(remember { navigatorOf(TITLE) }) },
            case("search") { Screens(remember { navigatorOf(selected = Destination.Search) }) },
        )

    // ARCHITECTURE 8.5: along a stack, across destinations, and Back's gesture held half-way, cancelled or released.
    override val transitions =
        listOf(
            navigatorOf().let { navigator ->
                Transition("title", start = "list", millis = MotionBounds.SCREEN, at = MOMENTS, change = { navigator.open(TITLE) }) {
                    Screens(navigator)
                }
            },
            navigatorOf(TITLE).let { navigator ->
                Transition("list", "title", MotionBounds.SCREEN, at = MOMENTS, name = "list-back", change = { navigator.back() }) {
                    Screens(navigator)
                }
            },
            navigatorOf().let { navigator ->
                Transition("search", "list", MotionBounds.SCREEN, at = MOMENTS, change = { navigator.select(Destination.Search) }) {
                    Screens(navigator)
                }
            },
            Transition("title", start = "title", millis = GESTURE, at = GESTURE_MOMENTS, name = "title-gesture-cancelled") { changed ->
                Screens(remember { navigatorOf(TITLE) })
                Gesture(changed, completes = false)
            },
            Transition("list", start = "title", millis = GESTURE, at = GESTURE_MOMENTS, name = "list-gesture-completed") { changed ->
                Screens(remember { navigatorOf(TITLE) })
                Gesture(changed, completes = true)
            },
        )

    private companion object {
        val TITLE: NavKey = TitleKey(FixtureAnime.exactToWatch.id.value)

        // The slow effects spring does most of its way early: the moments are taken while the screens move.
        val MOMENTS = listOf(32L, 80L, 160L, MotionBounds.SCREEN)

        // The finger reaches half-way in eight frames, holds for eight and lets go; the rest settles.
        const val GESTURE = 700L
        val GESTURE_MOMENTS = listOf(48L, 128L, 288L, GESTURE)
    }
}

// The List with [stack] opened on it, and [selected] chosen.
private fun navigatorOf(
    vararg stack: NavKey,
    selected: Destination = Destination.List,
): ShellNavigator =
    ShellNavigator(Destination.entries.associateWith { mutableStateListOf(it.root) }).apply {
        stack.forEach { open(it) }
        if (selected != Destination.List) select(selected)
    }

// The List, a title page and the Search root in the shell.
@Composable
private fun Screens(navigator: ShellNavigator) = ShellRoute(navigator, entries = remember { screenEntries })

private val screenEntries: ShellEntries = { _, snackbars ->
    entry<ListRoot> { ListScreen(ListFixtureStates.watching, {}, snackbars) }
    entry<TitleKey> { TitleScreen(TitleFixtures.of(FixtureAnime.exactToWatch), {}, snackbars) }
    entry<SearchRoot> { PlaceholderScreen(PlaceholderState(Destination.Search, sessionExpired = false), {}, snackbars) }
    entry<ProfileRoot> { PlaceholderScreen(PlaceholderState(Destination.Profile, sessionExpired = false), {}, snackbars) }
}

// A back gesture from the left edge, moved half-way frame by frame, held, then let go or cancelled.
@Composable
private fun Gesture(
    changed: Boolean,
    completes: Boolean,
) {
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    LaunchedEffect(changed) {
        if (!changed) return@LaunchedEffect
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        for (step in 1..STEPS) {
            withFrameNanos {}
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, HALF * step / STEPS, BackEventCompat.EDGE_LEFT))
        }
        repeat(HOLD) { withFrameNanos {} }
        if (completes) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
    }
}

private const val STEPS = 8
private const val HOLD = 8
private const val HALF = 0.5f

@Composable
private fun Shell(
    state: ShellState,
    sessionExpired: Boolean = false,
    snackbar: Boolean = false,
) {
    val snackbars = remember { SnackbarHostState() }
    if (snackbar) {
        val message = stringResource(R.string.shell_could_not_save)
        val retry = stringResource(R.string.design_retry)
        LaunchedEffect(Unit) { snackbars.showSnackbar(message, actionLabel = retry) }
    }
    ShellScreen(state, onEvent = {}) { PlaceholderScreen(PlaceholderState(state.selected, sessionExpired), onEvent = {}, snackbars) }
}
