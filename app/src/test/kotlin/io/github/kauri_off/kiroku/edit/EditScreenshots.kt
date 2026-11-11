package io.github.kauri_off.kiroku.edit

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown

class EditScreenshots : ScreenshotRegistry() {
    override val content = contentCase("existing") { EditSheet(EditFixtures.existing, onEvent = {}) }

    override val loading =
        notShown("The sheet opens from a list row or a title page that already holds the entry, so nothing is loaded")

    override val empty = notShown("There is always an entry to edit: a title not on the list starts from new values")

    override val error =
        notShown("Nothing is written before Save, and a failed write is reported by the screen beneath once the sheet is gone")

    override val cases =
        listOf(
            case("new-entry") { EditSheet(EditFixtures.newEntry, onEvent = {}) },
            case("unknown-total") { EditSheet(EditFixtures.unknownTotal, onEvent = {}) },
            case("keyboard-open", keyboard = true) {
                EditSheet(EditFixtures.existing, onEvent = {}, typedEpisodes = 14.toString(), focusEpisodes = true)
            },
            case("over-total-error") {
                EditSheet(EditFixtures.existing, onEvent = {}, typedEpisodes = 26.toString(), focusEpisodes = false)
            },
            case("remove-confirm") { EditSheet(EditFixtures.existing.copy(dialog = EditDialog.ConfirmRemove), onEvent = {}) },
            case("discard-confirm") { EditSheet(EditFixtures.changed.copy(dialog = EditDialog.ConfirmDiscard), onEvent = {}) },
            case("date-picker") { EditSheet(EditFixtures.existing.copy(dialog = EditDialog.PickDate(DateField.Start)), onEvent = {}) },
        )
}

object EditFixtures {
    private val today = FixtureTime.reference.atZone(FixtureTime.zone).toLocalDate()

    private fun onList(anime: Anime): EditSheetState {
        val entry = Fixtures.reference.entries.first { it.anime.id == anime.id }
        return EditSheetState.of(entry.values, anime.malEpisodes, today)
    }

    val existing = onList(FixtureAnime.exactToWatch)

    val unknownTotal = onList(FixtureAnime.unknownTotal)

    val newEntry = EditSheetState.of(null, FixtureAnime.notOnListAiring.malEpisodes, today)

    val changed =
        existing
            .after(EditSheetEvent.StatusChosen(ListStatus.OnHold))
            .after(EditSheetEvent.ScoreChanged(9))
}
