package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class ScoresScreenshots : ScreenshotRegistry() {
    override val content = contentCase("scored") { Screen(ScoresFixtures.shown(FixtureAnime.exactToWatch)) }
    override val loading = shown { Screen(ScoresState.Loading(SubScreenFixtures.session)) }

    // Nobody scored the title.
    override val empty = shown { Screen(ScoresFixtures.shown(FixtureAnime.premiereTimeSoon)) }
    override val error = shown { Screen(ScoresState.Failed(DataError.ServerError, SubScreenFixtures.session)) }

    override val cases =
        listOf(
            case("error-large-text", largeText = true) {
                Screen(ScoresState.Failed(DataError.ServerError, SubScreenFixtures.session))
            },
            case("offline") { Screen(ScoresState.Failed(DataError.NoConnection, SubScreenFixtures.session)) },
            case("not-known") { Screen(ScoresState.NotKnown(SubScreenFixtures.session)) },
            case("many-votes") { Screen(ScoresState.Shown(ScoresFixtures.manyVotes, SubScreenFixtures.session)) },
            case("session-expired") {
                Screen(ScoresFixtures.shown(FixtureAnime.exactToWatch).copy(session = Fixtures.reference.expired))
            },
        )
}

object ScoresFixtures {
    fun shown(anime: Anime): ScoresState.Shown = ScoresState.Shown(Fixtures.reference.scores.getValue(anime.id), SubScreenFixtures.session)

    // The longest counts a title has: a popular one's, seven digits and a share under one percent.
    val manyVotes =
        ScoreDistribution(
            mapOf(
                10 to 373_989,
                9 to 304_979,
                8 to 229_987,
                7 to 109_252,
                6 to 36_626,
                5 to 15_172,
                4 to 5_590,
                3 to 2_220,
                2 to 1_234,
                1 to 2_500,
            ),
        )
}

@Composable
private fun Screen(state: ScoresState) = ScoresScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })
