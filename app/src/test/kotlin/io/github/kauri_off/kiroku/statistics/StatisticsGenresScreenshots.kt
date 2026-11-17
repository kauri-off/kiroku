package io.github.kauri_off.kiroku.statistics

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.statistics.StatisticsFixtureStates.of

class StatisticsGenresScreenshots : ScreenshotRegistry() {
    private val fixtures = StatisticsFixtureStates
    private val page = StatisticsPage.Genres

    override val content = contentCase("own") { StatisticsShot(fixtures.shown(page)) }
    override val loading = shown { StatisticsShot(of(page, StatisticsContent.Loading)) }
    override val empty = shown { StatisticsShot(fixtures.shown(page, fixtures.nothing)) }
    override val error = shown { StatisticsShot(of(page, StatisticsContent.Failed(DataError.NoConnection), fixtures.otherName)) }

    override val cases =
        listOf(
            case("every-genre", largeText = true) { StatisticsShot(fixtures.shown(page, fixtures.everyGenre)) },
            case("other-large") { StatisticsShot(fixtures.shown(page, fixtures.large, fixtures.otherName)) },
            case("single") { StatisticsShot(fixtures.shown(page, fixtures.single)) },
            case("nothing-known") { StatisticsShot(fixtures.shown(page, fixtures.nothingKnown)) },
        )
}
