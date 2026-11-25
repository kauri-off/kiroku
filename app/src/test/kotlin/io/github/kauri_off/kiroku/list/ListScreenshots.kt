package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellScreen
import io.github.kauri_off.kiroku.shell.ShellState

// The page and the indicator on it end within a component's entrance.
private const val TAB_SWITCH = MotionBounds.ENTER
private const val FRAME = 16L
private const val PHONE_LONG_SIDE = 891
private const val PHONE_SHORT_SIDE = 411

class ListScreenshots : ScreenshotRegistry() {
    private fun screen(
        state: ListState,
        message: ShellMessage? = null,
    ) = @Composable { FixtureListScreen(state, message) }

    private val filterUndo: @Composable () -> Unit = {
        ShellScreen(ShellState(Destination.List), {}) {
            FixtureListScreen(ListFixtureStates.filtering, ListFixtureStates.completedMessage)
        }
    }

    override val content = contentCase("watching", content = screen(ListFixtureStates.watching))
    override val loading = shown(screen(ListFixtureStates.loading))
    override val empty = shown(screen(ListFixtureStates.empty))
    override val error = shown(screen(ListFixtureStates.error))

    override val cases =
        listOf(
            case("manga", largeText = true, content = screen(ListFixtureStates.watching.copy(mangaAvailable = true))),
            case("planned", content = screen(ListFixtureStates.planned)),
            case("completed", content = screen(ListFixtureStates.completed)),
            case("on-hold", content = screen(ListFixtureStates.onHold)),
            case("dropped", content = screen(ListFixtureStates.dropped)),
            case("unknown-status", content = screen(ListFixtureStates.unknown)),
            case("unknown-status-long-code", content = screen(ListFixtureStates.unknownLongCode)),
            case("watching-tabs-scrolled", content = { ScrolledTabsListScreen(ListFixtureStates.watching) }),
            case("sort-sheet", content = screen(ListFixtureStates.sortSheet)),
            case("sort-sheet-title", content = screen(ListFixtureStates.sortSheetTitle)),
            case("sort-sheet-last-updated", content = screen(ListFixtureStates.sortSheetLastUpdated)),
            case("sort-sheet-score-lowest", content = screen(ListFixtureStates.sortSheetScoreLowest)),
            case("sort-sheet-start-date", content = screen(ListFixtureStates.sortSheetStartDate)),
            case(
                "sort-sheet-landscape",
                window = WindowSize(PHONE_LONG_SIDE, PHONE_SHORT_SIDE),
                content = screen(ListFixtureStates.sortSheetScoreLowest),
            ),
            case("watching-by-score", content = screen(ListFixtureStates.watchingByScore)),
            case("watching-by-start-date", content = screen(ListFixtureStates.watchingByStartDate)),
            case("planned-by-start-date", content = screen(ListFixtureStates.plannedByStartDate)),
            case("completed-by-score", content = screen(ListFixtureStates.completedByScore)),
            case("completed-by-start-date", content = screen(ListFixtureStates.completedByStartDate)),
            case("filtering", keyboard = true, content = screen(ListFixtureStates.filtering)),
            case("filtering-wide", window = WindowSize(840, 600), keyboard = true) {
                ShellScreen(ShellState(Destination.List), {}) { FixtureListScreen(ListFixtureStates.filtering) }
            },
            case("filtering-wide-portrait", window = WindowSize(600, 960), keyboard = true) {
                ShellScreen(ShellState(Destination.List), {}) { FixtureListScreen(ListFixtureStates.filtering) }
            },
            case("filtering-undo-compact", window = WindowSize(411, 550), keyboard = true, content = filterUndo),
            case("filtering-undo-wide", window = WindowSize(840, 600), keyboard = true, content = filterUndo),
            case("refreshing", content = screen(ListFixtureStates.refreshing)),
            case("undo-snackbar", content = screen(ListFixtureStates.undoSnackbar, ListFixtureStates.undoMessage)),
            case("completed-snackbar", content = screen(ListFixtureStates.completedSnackbar, ListFixtureStates.completedMessage)),
            case("last-updated", content = screen(ListFixtureStates.lastUpdated)),
            case("completed-row", content = screen(ListFixtureStates.completedRow)),
            case("above-total", content = screen(ListFixtureStates.aboveTotal)),
            case("offline-cached", content = screen(ListFixtureStates.offlineCached, ListFixtureStates.offlineMessage)),
            case("signed-out", content = screen(ListFixtureStates.signedOut)),
            case("session-expired", content = screen(ListFixtureStates.sessionExpired)),
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
            // The tab row scrolled by hand to its end, a far tab tapped: the row moves once, from there to the tab.
            Transition(
                "completed",
                start = "watching-tabs-scrolled",
                millis = TAB_SWITCH,
                at = listOf(FRAME, TAB_SWITCH / 6, TAB_SWITCH / 3, TAB_SWITCH),
                lead = 2 * FRAME,
            ) { changed ->
                ScrolledTabsListScreen(if (changed) ListFixtureStates.completed else ListFixtureStates.watching)
            },
        )
}

// The List with its tab row scrolled by hand to the end, through the row's own scroll action, once it is laid out.
@Composable
private fun ScrolledTabsListScreen(state: ListState) {
    FixtureListScreen(state)
    val root = LocalView.current as ViewRootForTest
    LaunchedEffect(root) {
        repeat(LAYOUT_FRAMES) {
            withFrameNanos {}
            val row =
                root.semanticsOwner.unmergedRootSemanticsNode
                    .all()
                    .firstOrNull { node -> node.children.any { it.isTab() } }
            val range = row?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
            if (range != null && range.maxValue() > 0f) {
                row.config[SemanticsActions.ScrollBy].action?.invoke(range.maxValue(), 0f)
                return@LaunchedEffect
            }
        }
    }
}

private fun SemanticsNode.all(): Sequence<SemanticsNode> = sequenceOf(this) + children.asSequence().flatMap { it.all() }

private fun SemanticsNode.isTab() = config.getOrNull(SemanticsProperties.Role) == Role.Tab

private const val LAYOUT_FRAMES = 10
