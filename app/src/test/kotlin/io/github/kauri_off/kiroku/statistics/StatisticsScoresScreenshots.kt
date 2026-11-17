package io.github.kauri_off.kiroku.statistics

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.statistics.StatisticsFixtureStates.of

// The scores page, and the states every statistics page shares.
class StatisticsScoresScreenshots : ScreenshotRegistry() {
    private val fixtures = StatisticsFixtureStates
    private val page = StatisticsPage.Scores
    private val other = fixtures.otherName

    override val content = contentCase("own") { StatisticsShot(fixtures.shown(page)) }
    override val loading = shown { StatisticsShot(of(page, StatisticsContent.Loading)) }
    override val empty = shown { StatisticsShot(fixtures.shown(page, fixtures.nothing)) }
    override val error = shown { StatisticsShot(of(page, StatisticsContent.Failed(DataError.StorageError))) }

    override val cases =
        listOf(
            case("other-large") { StatisticsShot(fixtures.shown(page, fixtures.large, other)) },
            case("nothing-scored") { StatisticsShot(fixtures.shown(page, fixtures.nothingScored)) },
            case("single") { StatisticsShot(fixtures.shown(page, fixtures.single)) },
            case("session-expired") { StatisticsShot(fixtures.shown(page).copy(sessionExpired = true)) },
            case("signed-out") { StatisticsShot(of(page, StatisticsContent.SignedOut)) },
            case("other-reading") { StatisticsShot(fixtures.reading(page, 3_000, 12_400)) },
            case("other-reading-no-total") { StatisticsShot(fixtures.reading(page, 1_200, null)) },
            case("other-offline") { StatisticsShot(of(page, StatisticsContent.Failed(DataError.NoConnection), other)) },
            case("other-refused") { StatisticsShot(of(page, StatisticsContent.Failed(DataError.Refused), other)) },
            case("private-list") { StatisticsShot(of(page, StatisticsContent.Failed(DataError.PrivateList), other)) },
            case("unknown-name") { StatisticsShot(of(page, StatisticsContent.Failed(DataError.UnknownUser), other)) },
        )
}

@Composable
internal fun StatisticsShot(state: StatisticsState) = StatisticsScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })
