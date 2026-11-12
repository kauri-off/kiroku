package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.ShellMessage

// The page and the indicator on it end within a component's entrance.
private const val TAB_SWITCH = MotionBounds.ENTER
private const val FRAME = 16L

class ListScreenshots : ScreenshotRegistry() {
    private fun screen(
        state: ListState,
        message: ShellMessage? = null,
    ) = @Composable { FixtureListScreen(state, message) }

    override val content = contentCase("watching", content = screen(ListFixtureStates.watching))
    override val loading = shown(screen(ListFixtureStates.loading))
    override val empty = shown(screen(ListFixtureStates.empty))
    override val error = shown(screen(ListFixtureStates.error))

    override val cases =
        listOf(
            case("planned", content = screen(ListFixtureStates.planned)),
            case("completed", content = screen(ListFixtureStates.completed)),
            case("on-hold", content = screen(ListFixtureStates.onHold)),
            case("dropped", content = screen(ListFixtureStates.dropped)),
            case("sort-sheet", content = screen(ListFixtureStates.sortSheet)),
            case("filtering", keyboard = true, content = screen(ListFixtureStates.filtering)),
            case("refreshing", content = screen(ListFixtureStates.refreshing)),
            case("undo-snackbar", content = screen(ListFixtureStates.undoSnackbar, ListFixtureStates.undoMessage)),
            case("completed-snackbar", content = screen(ListFixtureStates.completedSnackbar, ListFixtureStates.completedMessage)),
            case("last-updated", content = screen(ListFixtureStates.lastUpdated)),
            case("completed-row", content = screen(ListFixtureStates.completedRow)),
            case("above-total", content = screen(ListFixtureStates.aboveTotal)),
            case("offline-cached", content = screen(ListFixtureStates.offlineCached, ListFixtureStates.offlineMessage)),
            case("signed-out", content = screen(ListFixtureStates.signedOut)),
            case("session-expired", content = screen(ListFixtureStates.sessionExpired)),
            case("read-only", content = screen(ListFixtureStates.readOnly)),
        )

    // "+1" completes the top title: its row fades out and the rows below close up, a change inside the list.
    override val transitions =
        listOf(
            Transition("completed-row", start = "last-updated", millis = MotionBounds.CHANGE) { changed ->
                FixtureListScreen(if (changed) ListFixtureStates.completedRow else ListFixtureStates.lastUpdated)
            },
            // A tapped tab slides its page in with the indicator on it; the curve does most of its way early.
            Transition(
                "planned",
                start = "watching",
                millis = TAB_SWITCH,
                at = listOf(FRAME, TAB_SWITCH / 6, TAB_SWITCH / 3, TAB_SWITCH),
                lead = FRAME,
            ) { changed ->
                FixtureListScreen(if (changed) ListFixtureStates.planned else ListFixtureStates.watching)
            },
            // A far tab: the page and the indicator are put next to it at once, then slide in together.
            Transition(
                "dropped",
                start = "watching",
                millis = TAB_SWITCH,
                at = listOf(FRAME, TAB_SWITCH / 6, TAB_SWITCH / 3, TAB_SWITCH),
                lead = 2 * FRAME,
            ) { changed ->
                FixtureListScreen(if (changed) ListFixtureStates.dropped else ListFixtureStates.watching)
            },
        )
}
