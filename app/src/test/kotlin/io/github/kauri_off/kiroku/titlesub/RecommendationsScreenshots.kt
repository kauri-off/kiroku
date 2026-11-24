package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown
import io.github.kauri_off.kiroku.search.resultRow

class RecommendationsScreenshots : ScreenshotRegistry() {
    override val content = contentCase("recommendations") { Screen(rows()) }
    override val loading = shown { Screen(RecommendationsContent.Loading) }
    override val empty = shown { Screen(RecommendationsContent.Shown(emptyList())) }
    override val error = shown { Screen(RecommendationsContent.Failed(DataError.NoConnection)) }

    private fun rows(): RecommendationsContent.Shown {
        val set = Fixtures.reference
        val entries = set.entries.associateBy { it.anime.id }
        val titles = set.details.getValue(FixtureAnime.exactToWatch.id).recommendations
        return RecommendationsContent.Shown(titles.map { resultRow(it, entries[it.id]?.values, TitleLanguage.Romaji) })
    }
}

@Composable
private fun Screen(content: RecommendationsContent) =
    RecommendationsScreen(
        RecommendationsState(content, Fixtures.reference.session),
        onEvent = {},
        snackbars = remember { SnackbarHostState() },
    )
