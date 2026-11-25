package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState

class UserListScreenshots : ScreenshotRegistry() {
    private val states = UserListFixtureStates

    private fun screen(
        state: ListState,
        message: ShellMessage? = null,
    ) = @Composable { FixtureListScreen(state, message) }

    private val filterUndo: @Composable () -> Unit = {
        ShellScreen(ShellState(Destination.Profile), {}) {
            FixtureListScreen(states.filtering, ListFixtureStates.completedMessage)
        }
    }

    override val content = contentCase("watching", content = screen(states.watching))
    override val loading = shown(screen(states.loading))
    override val empty = shown(screen(states.empty))
    override val error = shown(screen(states.noConnection))

    override val cases =
        listOf(
            case("completed", content = screen(states.completed)),
            case("longest-tab", content = screen(states.longestTab)),
            case("by-score", content = screen(states.byScore)),
            case("sort-sheet", content = screen(states.sortSheet)),
            case("filtering", keyboard = true, content = screen(states.filtering)),
            case("filtering-wide", window = WindowSize(840, 600), keyboard = true) {
                ShellScreen(ShellState(Destination.Profile), {}) { FixtureListScreen(states.filtering) }
            },
            case("filtering-wide-portrait", window = WindowSize(600, 960), keyboard = true) {
                ShellScreen(ShellState(Destination.Profile), {}) { FixtureListScreen(states.filtering) }
            },
            case("filtering-undo-compact", window = WindowSize(411, 550), keyboard = true, content = filterUndo),
            case("filtering-undo-wide", window = WindowSize(840, 600), keyboard = true, content = filterUndo),
            case("filter-no-matches-wide", window = WindowSize(840, 600), keyboard = true, content = screen(userListNoMatches)),
            case("filter-no-matches-compact", window = WindowSize(411, 550), keyboard = true) {
                ShellScreen(ShellState(Destination.Profile), {}) { FixtureListScreen(userListNoMatches) }
            },
            case("unknown-status", content = screen(states.unknownStatus)),
            case("reading", content = screen(states.reading)),
            case("reading-with-total", content = screen(states.readingWithTotal)),
            case("read-again", content = screen(states.readAgain)),
            case("read-again-part", content = screen(states.readAgainPart)),
            case(
                "read-again-failed",
                content = screen(states.watching, ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection))),
            ),
            case("private", content = screen(states.privateList)),
            case("unknown-name", content = screen(states.unknownUser)),
            case("refused", content = screen(states.refused)),
            case("failed", content = screen(states.other)),
            case("session-expired", content = screen(states.sessionExpired)),
        )
}
