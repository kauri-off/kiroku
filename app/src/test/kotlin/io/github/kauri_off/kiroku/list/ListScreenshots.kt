package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class ListScreenshots : ScreenshotRegistry() {
    private fun screen(state: ListState) = @Composable { ListScreen(state, onEvent = {}) }

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
            case("offline-cached", content = screen(ListFixtureStates.offlineCached)),
            case("signed-out", content = screen(ListFixtureStates.signedOut)),
            case("session-expired", content = screen(ListFixtureStates.sessionExpired)),
            case("read-only", content = screen(ListFixtureStates.readOnly)),
        )

    // "+1" completes a title: its row leaves with the list's item animation and the snackbar enters.
    override val transitions =
        listOf(
            Transition("completed-snackbar", millis = 300) { changed ->
                ListScreen(if (changed) ListFixtureStates.completedSnackbar else ListFixtureStates.watching, onEvent = {})
            },
        )
}
