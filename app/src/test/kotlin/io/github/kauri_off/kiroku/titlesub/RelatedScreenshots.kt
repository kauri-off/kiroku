package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class RelatedScreenshots : ScreenshotRegistry() {
    override val content = contentCase("grouped") { Screen(SubScreenFixtures.related(FixtureAnime.exactToWatch)) }
    override val loading = shown { Screen(RelatedState.Loading(SubScreenFixtures.session)) }
    override val empty =
        notShown("See all is offered only for a title with related anime, so the list always has at least one row.")
    override val error = shown { Screen(RelatedState.Failed(DataError.NoConnection, SubScreenFixtures.session)) }

    override val cases =
        listOf(
            case("single") { Screen(SubScreenFixtures.related(FixtureAnime.premiereYear)) },
            case("session-expired") { Screen(SubScreenFixtures.related(FixtureAnime.premiereYear, Fixtures.reference.expired)) },
        )
}

@Composable
private fun Screen(state: RelatedState) = RelatedScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })

object SubScreenFixtures {
    private val set = Fixtures.reference

    val session: Session = set.session

    fun related(
        anime: Anime,
        session: Session = set.session,
    ): RelatedState.Shown =
        relatedState(
            related = set.details.getValue(anime.id).related,
            entries = set.entries.associate { it.anime.id to it.values },
            airing = set.airing,
            language = TitleLanguage.Romaji,
            session = session,
        )

    fun details(
        anime: Anime,
        language: TitleLanguage = TitleLanguage.Romaji,
    ): DetailsState.Shown = detailsState(anime, set.details.getValue(anime.id), language, set.session, set.clock)

    // More synonyms than three lines hold, as long-running titles often have.
    val manySynonyms: Anime =
        FixtureAnime.exactToWatch.copy(
            titles =
                FixtureAnime.exactToWatch.titles.copy(
                    synonyms =
                        listOf(
                            "Kagetosho 2",
                            "Heat Haze Library II",
                            "The Library Where the Heat Haze Never Lifts: Second Year",
                            "Kagerou Toshokan Dai-ni-ki",
                            "KageTosho S2",
                        ),
                ),
        )
}
