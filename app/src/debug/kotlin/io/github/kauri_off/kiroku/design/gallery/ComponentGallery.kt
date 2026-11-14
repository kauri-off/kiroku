package io.github.kauri_off.kiroku.design.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.ConnectedChoice
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.FactRow
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterCard
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Spacing
import java.text.NumberFormat
import java.util.Locale

// Every component of the design system in every state it can be in, with the hardest content it meets.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentGallery(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            section(R.string.gallery_poster) { PosterSection() }
            section(R.string.gallery_anime_row) { AnimeRowSection() }
            section(R.string.gallery_poster_card) { PosterCardSection() }
            section(R.string.gallery_section_header) { SectionHeaderSection() }
            section(R.string.gallery_stat_bar) { StatBarSection() }
            section(R.string.gallery_stat_number) { StatNumberSection() }
            section(R.string.gallery_fact_row) { FactRowSection() }
            section(R.string.gallery_episode_stepper) { EpisodeStepperSection() }
            section(R.string.gallery_connected_choice) { ConnectedChoiceSection() }
            section(R.string.gallery_screen_state) { ScreenStateSection() }
            section(R.string.gallery_session_banner, last = true) { SessionBanner(onSignIn = {}) }
        }
    }
}

private fun LazyListScope.section(
    title: Int,
    last: Boolean = false,
    content: @Composable () -> Unit,
) {
    item(key = title) {
        Column {
            SectionHeader(stringResource(title))
            content()
            if (!last) Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PosterSection() {
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        for (url in listOf(FixtureAnime.exactToWatch.posterUrl, null)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                Poster(url, PosterSize.Header)
                Poster(url, PosterSize.Strip)
                Poster(url, PosterSize.Row)
            }
        }
    }
}

@Composable
internal fun AnimeRowSection() {
    val plusOne = RowTrailing.PlusOne {}
    Column {
        AnimeRow(
            title = FixtureAnime.exactToWatch.title(),
            posterUrl = FixtureAnime.exactToWatch.posterUrl,
            onClick = {},
            lines =
                listOf(
                    RowLine(stringResource(R.string.gallery_progress, 12, 24)),
                    RowLine(stringResource(R.string.gallery_to_watch, 2), highlighted = true),
                ),
            trailing = plusOne,
        )
        AnimeRow(
            title = FixtureAnime.longTitle.title(),
            posterUrl = FixtureAnime.longTitle.posterUrl,
            onClick = {},
            lines =
                listOf(
                    RowLine(stringResource(R.string.gallery_progress, 1088, 1122)),
                    RowLine(stringResource(R.string.gallery_to_watch_estimated, 6), highlighted = true),
                ),
            trailing = plusOne,
        )
        AnimeRow(
            title = FixtureAnime.exactCaughtUpLater.title(),
            posterUrl = FixtureAnime.exactCaughtUpLater.posterUrl,
            onClick = {},
            lines =
                listOf(
                    RowLine(stringResource(R.string.gallery_progress, 14, 24)),
                    RowLine(stringResource(R.string.gallery_next_episode)),
                ),
            trailing = RowTrailing.PlusOne(prominent = false) {},
        )
        AnimeRow(
            title = FixtureAnime.premiereUnannounced.title(),
            posterUrl = FixtureAnime.premiereUnannounced.posterUrl,
            onClick = {},
            lines = listOf(RowLine(stringResource(R.string.gallery_not_yet_aired))),
        )
        AnimeRow(
            title = FixtureAnime.film.title(),
            posterUrl = FixtureAnime.film.posterUrl,
            onClick = {},
            lines = listOf(RowLine(stringResource(R.string.gallery_score, 8))),
        )
        AnimeRow(
            title = FixtureAnime.prequel.title(),
            posterUrl = FixtureAnime.prequel.posterUrl,
            onClick = {},
            lines =
                listOf(
                    RowLine(stringResource(R.string.gallery_facts)),
                    RowLine(stringResource(R.string.gallery_list_status)),
                ),
            trailing = RowTrailing.MalScore(decimal(8.73)),
        )
    }
}

private class Card(
    val anime: Anime,
    val relation: Int,
    val title: String = anime.title(),
    val caption2: @Composable () -> String,
)

@Composable
internal fun PosterCardSection() {
    val cards =
        listOf(
            Card(FixtureAnime.prequel, R.string.gallery_sequel) { stringResource(R.string.gallery_completed) },
            Card(FixtureAnime.longTitle, R.string.gallery_side_story) { stringResource(R.string.gallery_planned) },
            Card(FixtureAnime.exactCaughtUpLater, R.string.gallery_alt_version) { stringResource(R.string.gallery_tv_year, 2019) },
            Card(FixtureAnime.premiereUnannounced, R.string.gallery_parent_story) { stringResource(R.string.gallery_watching) },
            Card(FixtureAnime.film, R.string.gallery_summary) { stringResource(R.string.gallery_movie_year, 2024) },
            Card(FixtureAnime.exactToWatch, R.string.gallery_prequel) { stringResource(R.string.gallery_on_hold) },
            Card(FixtureAnime.relatedFullStory, R.string.gallery_full_story) { stringResource(R.string.gallery_dropped) },
            Card(FixtureAnime.relatedSpinOff, R.string.gallery_spin_off) { stringResource(R.string.gallery_tv_year, 2021) },
            Card(FixtureAnime.relatedAlternativeSetting, R.string.gallery_alt_setting) { stringResource(R.string.gallery_planned) },
            Card(
                FixtureAnime.onHoldAiring,
                R.string.gallery_side_story,
                title =
                    FixtureAnime.onHoldAiring.titles.english
                        .orEmpty(),
            ) {
                stringResource(R.string.gallery_on_hold)
            },
        )
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        items(cards, key = { it.anime.id.value }) { card ->
            PosterCard(
                title = card.title,
                caption1 = stringResource(card.relation),
                caption2 = card.caption2(),
                posterUrl = card.anime.posterUrl,
                onClick = {},
            )
        }
    }
}

@Composable
internal fun SectionHeaderSection() {
    Column {
        SectionHeader(stringResource(R.string.gallery_related), onSeeAll = {})
        SectionHeader(stringResource(R.string.gallery_recommendations), onSeeAll = {})
        SectionHeader(stringResource(R.string.gallery_statistics))
    }
}

@Composable
internal fun StatBarSection() {
    val counts =
        listOf(
            R.string.gallery_watching to 123_456,
            R.string.gallery_completed to 2_345_678,
            R.string.gallery_on_hold to 45_678,
            R.string.gallery_dropped to 0,
            R.string.gallery_planned to 1_234_567,
        )
    val total = counts.sumOf { it.second }
    Column {
        counts.forEach { (label, count) -> StatBar(stringResource(label), count, total) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatNumberSection() {
    FlowRow(
        Modifier.padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        StatNumber(integer(12_345), stringResource(R.string.gallery_completed))
        StatNumber(decimal(1_234.5), stringResource(R.string.gallery_days_watched))
        StatNumber(decimal(7.45), stringResource(R.string.gallery_mean_score))
        StatNumber(decimal(8.73), stringResource(R.string.gallery_mal_score))
        StatNumber(
            stringResource(R.string.gallery_no_value),
            stringResource(R.string.gallery_mal_score),
            spokenValue = stringResource(R.string.gallery_no_score),
        )
    }
}

@Composable
internal fun FactRowSection() {
    val long = FixtureAnime.longTitle.titles
    Column {
        FactRow(stringResource(R.string.gallery_source), stringResource(R.string.gallery_light_novel))
        FactRow(stringResource(R.string.gallery_broadcast), stringResource(R.string.gallery_broadcast_value))
        FactRow(
            stringResource(R.string.gallery_alternative_titles),
            listOfNotNull(long.english, long.japanese).plus(long.synonyms),
        )
    }
}

@Composable
internal fun EpisodeStepperSection() {
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        EpisodeStepper(watched = 12, total = 24, onWatchedChange = {})
        EpisodeStepper(watched = 0, total = 24, onWatchedChange = {})
        EpisodeStepper(watched = 24, total = 24, onWatchedChange = {})
        EpisodeStepper(watched = 1088, total = 1122, onWatchedChange = {})
        EpisodeStepper(watched = 1093, total = null, onWatchedChange = {})
        EpisodeStepper(watched = 25, total = 24, onWatchedChange = {})
        EpisodeStepper(watched = 12, total = 24, onWatchedChange = {}, enabled = false)
    }
}

@Composable
internal fun ConnectedChoiceSection() {
    val statuses =
        listOf(
            R.string.gallery_watching,
            R.string.gallery_planned,
            R.string.gallery_completed,
            R.string.gallery_on_hold,
            R.string.gallery_dropped,
        ).map { stringResource(it) }
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        ConnectedChoice(statuses, chosen = 0, onChoose = {})
        ConnectedChoice(statuses.take(2), chosen = 1, onChoose = {})
        ConnectedChoice(statuses.take(3), chosen = 2, onChoose = {}, enabled = false)
    }
}

@Composable
internal fun ScreenStateSection() {
    Column {
        ScreenState(ScreenState.Loading)
        ScreenState(ScreenState.Empty(stringResource(R.string.gallery_empty), StateAction(stringResource(R.string.gallery_sign_in)) {}))
        ScreenState(
            ScreenState.Empty(
                stringResource(R.string.gallery_signed_out),
                StateAction(stringResource(R.string.gallery_sign_in)) {},
                StateAction(stringResource(R.string.gallery_open_another)) {},
            ),
        )
        ScreenState(ScreenState.Error(stringResource(R.string.gallery_no_connection)) {})
    }
}

private fun Anime.title(): String = titles.romaji

@Composable
private fun locale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun integer(value: Int): String = NumberFormat.getIntegerInstance(locale()).format(value)

@Composable
private fun decimal(value: Double): String =
    NumberFormat
        .getNumberInstance(locale())
        .apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }.format(value)

@Composable
private fun GalleryPreview(content: @Composable () -> Unit) = KirokuTheme(dark = false, dynamicColor = false, content = content)

@Preview(heightDp = 2400)
@Composable
private fun GalleryPreview() = GalleryPreview { ComponentGallery() }

@Preview
@Composable
private fun PosterPreview() = GalleryPreview { PosterSection() }

@Preview
@Composable
private fun AnimeRowPreview() = GalleryPreview { AnimeRowSection() }

@Preview
@Composable
private fun PosterCardPreview() = GalleryPreview { PosterCardSection() }

@Preview
@Composable
private fun SectionHeaderPreview() = GalleryPreview { SectionHeaderSection() }

@Preview
@Composable
private fun StatBarPreview() = GalleryPreview { StatBarSection() }

@Preview
@Composable
private fun StatNumberPreview() = GalleryPreview { StatNumberSection() }

@Preview
@Composable
private fun FactRowPreview() = GalleryPreview { FactRowSection() }

@Preview
@Composable
private fun EpisodeStepperPreview() = GalleryPreview { EpisodeStepperSection() }

@Preview
@Composable
private fun ConnectedChoicePreview() = GalleryPreview { ConnectedChoiceSection() }

@Preview
@Composable
private fun ScreenStatePreview() = GalleryPreview { ScreenStateSection() }

@Preview
@Composable
private fun SessionBannerPreview() = GalleryPreview { SessionBanner(onSignIn = {}) }
