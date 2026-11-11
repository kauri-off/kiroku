package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class DetailsScreenshots : ScreenshotRegistry() {
    override val content = contentCase("full") { DetailsScreen(SubScreenFixtures.details(FixtureAnime.exactToWatch), onEvent = {}) }
    override val loading = shown { DetailsScreen(DetailsState.Loading, onEvent = {}) }
    override val empty = shown { DetailsScreen(DetailsState.Shown(emptyList()), onEvent = {}) }
    override val error = shown { DetailsScreen(DetailsState.Failed(DataError.NoConnection), onEvent = {}) }

    override val cases =
        listOf(
            case("sparse") { DetailsScreen(SubScreenFixtures.details(FixtureAnime.premiereUnannounced), onEvent = {}) },
        )
}
