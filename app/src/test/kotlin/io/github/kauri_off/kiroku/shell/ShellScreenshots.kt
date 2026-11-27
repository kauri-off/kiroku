package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.list.ListScreen
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.Variant
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.title.TitleFixtures
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.title.TitleScreen

class ShellScreenshots : ScreenshotRegistry() {
    override val content = contentCase("list-selected") { Shell(ShellState(Destination.List)) }

    override val loading = shown { ScreenState(ScreenState.Loading, delayLoading = false) }
    override val empty = notShown("The shell is never empty: its placeholders are the destinations' empty states")
    override val error = notShown("The shell has no request that can fail; each destination's screen shows its own errors")

    override val cases =
        listOf(
            case("snackbar") { Shell(ShellState(Destination.List), snackbar = true) },
            case("session-banner") { Shell(ShellState(Destination.List), sessionExpired = true) },
            case("wide", window = WindowSize(widthDp = 840, heightDp = 600)) { Shell(ShellState(Destination.List)) },
            case("list", additionalVariants = listOf(Variant.EnBlack)) { Screens(remember { navigatorOf() }) },
            case("title") { Screens(remember { navigatorOf(TITLE) }) },
            case("search") { Screens(remember { navigatorOf(selected = Destination.Search) }) },
        )

    // Along a stack and across destinations; Back's gesture moves nothing, which ShellTransitionTest compares.
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
                Transition(
                    "search",
                    "list",
                    MotionBounds.SCREEN,
                    at = ACROSS,
                    change = { navigator.select(Destination.Search) },
                ) {
                    Screens(navigator)
                }
            },
        )

    private companion object {
        val TITLE: NavKey = TitleKey(FixtureAnime.exactToWatch.id.value)

        // The slow effects spring does most of its way early: the moments are taken while the screens move.
        val MOMENTS = listOf(32L, 80L, 160L, MotionBounds.SCREEN)

        // The bar's indicator moves first and the screens' fade is over within a few frames: every frame of it is shown.
        val ACROSS = listOf(16L, 32L, 48L, 64L, 80L, MotionBounds.SCREEN)
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
