package io.github.kauri_off.kiroku.edit

import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
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
            case("invalid-count", largeText = true) { MangaEditSheet(MangaEditFixtures.existing.copy(chapters = "-1")) {} },
            case("score-cleared") { MangaEditSheet(MangaEditFixtures.existing.copy(score = 0)) {} },
            case("saving", largeText = true) { MangaEditSheet(MangaEditFixtures.saving) {} },
            case("failure-without-retry") {
                MangaEditSheet(MangaEditFixtures.failed.copy(submission = MangaEditSubmission.Failed(canRetry = false))) {}
            },
            case("discard-confirm") { MangaEditSheet(MangaEditFixtures.changed.copy(confirmDiscard = true)) {} },
            case("status-chosen") { MangaEditSheet(MangaEditFixtures.statusChosen) {} },
        )

    override val transitions =
        listOf(
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
    val saving = changed.copy(submission = MangaEditSubmission.Saving)
    val failed = changed.copy(submission = MangaEditSubmission.Failed(canRetry = true))
    val statusChosen = existing.copy(status = MangaEditStatus.OnHold)
}
