package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class DetailsScreenshots : ScreenshotRegistry() {
    override val content = contentCase("full") { Screen(SubScreenFixtures.details(FixtureAnime.exactToWatch)) }
    override val loading = shown { Screen(DetailsState.Loading(SubScreenFixtures.session)) }
    override val empty = shown { Screen(DetailsState.Shown(emptyList(), SubScreenFixtures.session)) }
    override val error = shown { Screen(DetailsState.Failed(DataError.NoConnection, SubScreenFixtures.session)) }

    override val cases =
        listOf(
            case("sparse") { Screen(SubScreenFixtures.details(FixtureAnime.premiereUnannounced)) },
            case("english") {
                Screen(SubScreenFixtures.details(FixtureAnime.exactToWatch, TitleLanguage.English))
            },
            case("many-synonyms") { Screen(SubScreenFixtures.details(SubScreenFixtures.manySynonyms)) },
        )
}

@Composable
private fun Screen(state: DetailsState) = DetailsScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })
