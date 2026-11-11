package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class RelatedScreenshots : ScreenshotRegistry() {
    override val content = contentCase("grouped") { RelatedScreen(SubScreenFixtures.related(FixtureAnime.exactToWatch), onEvent = {}) }
    override val loading = shown { RelatedScreen(RelatedState.Loading, onEvent = {}) }
    override val empty =
        notShown("See all is offered only for a title with related anime, so the list always has at least one row.")
    override val error = shown { RelatedScreen(RelatedState.Failed(DataError.NoConnection), onEvent = {}) }

    override val cases =
        listOf(
            case("single") { RelatedScreen(SubScreenFixtures.related(FixtureAnime.premiereYear), onEvent = {}) },
        )
}

object SubScreenFixtures {
    private val set = Fixtures.reference

    fun related(anime: Anime): RelatedState.Shown =
        relatedState(
            related = set.details.getValue(anime.id).related,
            entries = set.entries.associate { it.anime.id to it.values },
            language = TitleLanguage.Romaji,
        )

    fun details(anime: Anime): DetailsState.Shown = detailsState(anime, set.details.getValue(anime.id), set.clock)
}
