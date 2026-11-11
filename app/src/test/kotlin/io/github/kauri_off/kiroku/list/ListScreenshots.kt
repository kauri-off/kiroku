package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.design.theme.Motion
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class ListScreenshots : ScreenshotRegistry() {
    private fun screen(state: ListState) = @Composable { HostedListScreen(state) }

    override val content = contentCase("watching", content = screen(ListFixtureStates.watching))
    override val loading = shown(screen(ListFixtureStates.loading))
    override val empty = shown(screen(ListFixtureStates.empty))
    override val error = shown(screen(ListFixtureStates.error))

    override val cases =
        listOf(
            case("planned", content = screen(ListFixtureStates.planned)),
            case("completed", content = screen(ListFixtureStates.completed)),
            case("sort-sheet", content = screen(ListFixtureStates.sortSheet)),
            case("filtering", keyboard = true, content = screen(ListFixtureStates.filtering)),
            case("refreshing", content = screen(ListFixtureStates.refreshing)),
            case("undo-snackbar", content = screen(ListFixtureStates.undoSnackbar)),
            case("completed-snackbar", content = screen(ListFixtureStates.completedSnackbar)),
            case("last-updated", content = screen(ListFixtureStates.lastUpdated)),
            case("completed-row", content = screen(ListFixtureStates.completedRow)),
            case("above-total", content = screen(ListFixtureStates.aboveTotal)),
            case("offline-cached", content = screen(ListFixtureStates.offlineCached)),
            case("signed-out", content = screen(ListFixtureStates.signedOut)),
            case("session-expired", content = screen(ListFixtureStates.sessionExpired)),
            case("read-only", content = screen(ListFixtureStates.readOnly)),
        )

    // "+1" completes the top title: its row fades out and the rows below close up, a change inside the list.
    override val transitions =
        listOf(
            Transition("completed-row", start = "last-updated", millis = Motion.Duration.SHORT4.toLong()) { changed ->
                HostedListScreen(if (changed) ListFixtureStates.completedRow else ListFixtureStates.lastUpdated)
            },
            // A tapped tab slides its page in.
            Transition("planned", start = "watching", millis = Motion.Duration.MEDIUM2.toLong()) { changed ->
                HostedListScreen(if (changed) ListFixtureStates.planned else ListFixtureStates.watching)
            },
        )
}
