package io.github.kauri_off.kiroku.edit

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.manga.MangaTitleContent
import io.github.kauri_off.kiroku.manga.MangaTitleScreen
import io.github.kauri_off.kiroku.manga.MangaTitleState
import io.github.kauri_off.kiroku.manga.mangaEntry
import io.github.kauri_off.kiroku.manga.mangaFixture
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class MangaEditSheetScreenshots : ScreenshotRegistry() {
    override val content = contentCase("existing") { MangaEditSheet(MangaEditFixtures.existing) {} }

    override val loading = notShown("The sheet opens with the entry's current values")

    override val empty = notShown("The sheet always shows a form for an existing entry")

    override val error = shown { MangaEditSheet(MangaEditFixtures.failed) {} }

    override val cases =
        listOf(
            case("unknown-fields") { MangaEditSheet(MangaEditFixtures.unknown) {} },
            case("invalid-count", largeText = true) { MangaEditSheet(MangaEditFixtures.existing.copy(chapters = "2147483648")) {} },
            case("score-cleared") { MangaEditSheet(MangaEditFixtures.existing.copy(score = 0)) {} },
            case("saving", largeText = true) { MangaEditSheet(MangaEditFixtures.saving) {} },
            case("removable", largeText = true) {
                MangaEditSheet(MangaEditFixtures.changed.copy(canRemove = true)) {}
            },
            case("confirm-removal", largeText = true) {
                MangaEditSheet(MangaEditFixtures.changed.copy(canRemove = true, confirmRemove = true)) {}
            },
            case("removing") { RemovalCompletion(confirmed = false) },
            case("removal-completed") { RemovalCompletion(confirmed = true, initiallyOpen = false) },
            case("checking-removal") {
                MangaEditSheet(MangaEditFixtures.existing.copy(submission = MangaEditSubmission.CheckingRemoval)) {}
            },
            case("removal-refresh", largeText = true) {
                MangaEditSheet(
                    MangaEditFixtures.existing.copy(
                        submission =
                            MangaEditSubmission.Failed(
                                canRetry = false,
                                message = R.string.manga_edit_removal_unconfirmed,
                                removal = true,
                                canReconcile = true,
                            ),
                    ),
                ) {}
            },
            case("removal-retry", largeText = true) {
                MangaEditSheet(
                    MangaEditFixtures.existing.copy(
                        submission =
                            MangaEditSubmission.Failed(
                                canRetry = true,
                                message = R.string.manga_edit_could_not_remove,
                                removal = true,
                            ),
                    ),
                ) {}
            },
            case("checking-save") {
                MangaEditSheet(MangaEditFixtures.saving.copy(submission = MangaEditSubmission.Reconciling)) {}
            },
            case("refresh-to-confirm", largeText = true) {
                MangaEditSheet(
                    MangaEditFixtures.failed.copy(
                        submission =
                            MangaEditSubmission.Failed(
                                canRetry = false,
                                message = R.string.manga_edit_unconfirmed,
                                canReconcile = true,
                                canStartOver = false,
                            ),
                    ),
                ) {}
            },
            case("failure-without-retry") {
                MangaEditSheet(MangaEditFixtures.failed.copy(submission = MangaEditSubmission.Failed(canRetry = false))) {}
            },
            case("discard-confirm") { MangaEditSheet(MangaEditFixtures.changed.copy(confirmDiscard = true)) {} },
            case("wide-short", window = WindowSize(840, 420), largeText = true) {
                MangaEditSheet(MangaEditFixtures.existing) {}
            },
            case("keyboard-open", keyboard = true, window = WindowSize(360, 640), largeText = true) {
                CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                    MangaEditSheet(MangaEditFixtures.changed, focusVolumes = true, openScoreMenu = false) {}
                }
            },
            case("score-menu", window = WindowSize(360, 640), largeText = true) {
                MangaEditSheet(MangaEditFixtures.unknown, focusVolumes = false, openScoreMenu = true) {}
            },
            case("restore-status") {
                MangaEditSheet(MangaEditFixtures.unknown.copy(status = MangaEditStatus.OnHold, chapters = "13")) {}
            },
            case("status-chosen") { MangaEditSheet(MangaEditFixtures.statusChosen) {} },
        )

    override val transitions =
        listOf(
            Transition(
                "confirm-removal",
                start = "removable",
                millis = 2 * MotionBounds.ENTER,
                at = listOf(16L, 48L, 96L, 2 * MotionBounds.ENTER),
            ) { changed ->
                MangaEditSheet(MangaEditFixtures.changed.copy(canRemove = true, confirmRemove = changed)) {}
            },
            Transition(
                "removal-completed",
                start = "removing",
                millis = 2 * MotionBounds.ENTER,
                at = listOf(16L, 48L, 96L, 2 * MotionBounds.ENTER),
            ) { changed ->
                RemovalCompletion(confirmed = changed)
            },
            Transition(
                "status-chosen",
                start = "existing",
                millis = 2 * MotionBounds.ENTER,
                at = listOf(16L, 80L, 160L, 2 * MotionBounds.ENTER),
            ) {
                MangaEditSheet(if (it) MangaEditFixtures.statusChosen else MangaEditFixtures.existing) {}
            },
        )
}

@Composable
private fun RemovalCompletion(
    confirmed: Boolean,
    initiallyOpen: Boolean = true,
) {
    val title = mangaFixture.titles.romaji
    MangaTitleScreen(
        MangaTitleState(MangaTitleContent.Shown(mangaFixture, title), listEntry = mangaEntry.takeUnless { confirmed }),
        {},
        remember { SnackbarHostState() },
    )
    var open by remember { mutableStateOf(initiallyOpen) }
    if (open) {
        MangaEditSheet(MangaEditFixtures.existing.copy(title = title, submission = MangaEditSubmission.Removing, closing = confirmed)) {
            if (it == MangaEditSheetEvent.Dismissed) open = false
        }
    }
}

internal object MangaEditFixtures {
    val existing =
        MangaEditSheetState(
            title = "Kagerou Toshokan",
            baseline = MangaEditBaseline(MangaEditStatus.Reading.readingStatus, chaptersRead = 12, volumesRead = 2, score = 8),
        )

    val unknown =
        MangaEditSheetState(
            title = existing.title,
            baseline = MangaEditBaseline(MangaReadingStatus("rereading"), chaptersRead = null, volumesRead = null, score = null),
        )

    val changed = existing.copy(chapters = "13", volumes = "3")
    val saving = changed.copy(status = MangaEditStatus.OnHold, submission = MangaEditSubmission.Saving)
    val failed = saving.copy(submission = MangaEditSubmission.Failed(canRetry = true))
    val statusChosen = existing.copy(status = MangaEditStatus.OnHold)
}
