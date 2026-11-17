package io.github.kauri_off.kiroku.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.design.component.COUNTED_WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.StatNumbers
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.text.genreName
import io.github.kauri_off.kiroku.text.mediaTypeLabel
import io.github.kauri_off.kiroku.title.countText
import io.github.kauri_off.kiroku.title.scoreText
import java.math.BigDecimal
import java.math.RoundingMode

// A statistics page of the signed-in user or of another, drawn from [state] alone. Its snackbars go to [snackbars],
// the host the caller draws.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    state: StatisticsState,
    onEvent: (StatisticsEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(state.page.title()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { onEvent(StatisticsEvent.Back) }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(StatisticsEvent.SignIn) })
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        val body = Modifier.padding(padding)
        when (val content = state.content) {
            StatisticsContent.Loading -> ScreenState(ScreenState.Loading, body)
            StatisticsContent.SignedOut ->
                ScreenState(
                    ScreenState.Empty(
                        stringResource(R.string.statistics_signed_out),
                        StateAction(stringResource(R.string.statistics_sign_in)) { onEvent(StatisticsEvent.SignIn) },
                    ),
                    body,
                )
            is StatisticsContent.Reading -> ListProgress(content.progress, body)
            is StatisticsContent.Failed -> ScreenState(failedState(content.error, onEvent), body)
            is StatisticsContent.Shown ->
                if (content.statistics.titleCount == 0) {
                    ScreenState(ScreenState.Empty(stringResource(R.string.statistics_nothing_on_list)), body)
                } else {
                    Page(state.page, content.statistics, padding)
                }
        }
    }
}

internal fun StatisticsPage.title(): Int =
    when (this) {
        StatisticsPage.Scores -> R.string.statistics_score_distribution
        StatisticsPage.Genres -> R.string.statistics_genres
        StatisticsPage.FormatsAndYears -> R.string.statistics_formats_and_years
    }

// A private list and an unknown name are answers, not failures, so they offer no Retry.
@Composable
private fun failedState(
    error: DataError,
    onEvent: (StatisticsEvent) -> Unit,
): ScreenState {
    val retry = { onEvent(StatisticsEvent.Retry) }
    return when (error) {
        DataError.PrivateList -> ScreenState.Empty(stringResource(R.string.statistics_private_list))
        DataError.UnknownUser -> ScreenState.Empty(stringResource(R.string.statistics_unknown_user))
        DataError.NoConnection -> ScreenState.Error(stringResource(R.string.common_no_connection), retry)
        DataError.Refused -> ScreenState.Error(stringResource(R.string.common_refused), retry)
        else -> ScreenState.Error(stringResource(R.string.statistics_could_not_load), retry)
    }
}

// The counted wait of a long list, where the loading state would stand: the share read so far and the line under it.
@Composable
private fun ListProgress(
    progress: UserListProgress,
    modifier: Modifier,
) {
    val loaded = countText(progress.loaded)
    val total = progress.total?.takeIf { it > 0 }
    val line =
        total?.let { stringResource(R.string.statistics_loading_list, loaded, countText(it)) }
            ?: pluralStringResource(R.plurals.statistics_loading_list_unknown_total, progress.loaded, loaded)
    Box(modifier.fillMaxSize().padding(horizontal = Spacing.l, vertical = Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = Sizes.messageMaxWidth).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            val indicator = Modifier.fillMaxWidth()
            if (total != null) {
                LinearWavyProgressIndicator(
                    progress = { (progress.loaded.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = indicator.testTag(COUNTED_WAVY_INDICATOR),
                )
            } else {
                LinearWavyProgressIndicator(indicator.testTag(WAVY_INDICATOR))
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

// The page's one column, as wide as content may be and clear of the bars, its lines in the Profile's rhythm.
@Composable
private fun Page(
    page: StatisticsPage,
    statistics: UserStatistics,
    padding: PaddingValues,
) {
    val direction = LocalLayoutDirection.current
    val groups = groups(page, statistics)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val columns = barColumns(groups.flatMap { it.bars }, minOf(maxWidth, Sizes.contentMaxWidth) - Spacing.l * 2)
        val metrics = pageMetrics()
        LazyColumn(
            modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(top = Spacing.s, bottom = padding.calculateBottomPadding() + Spacing.l),
        ) {
            lines(groups.flatMap { it.lines(columns, metrics) })
        }
    }
}

// What each page shows, as groups in the page's order.
@Composable
internal fun groups(
    page: StatisticsPage,
    statistics: UserStatistics,
): List<Group> =
    when (page) {
        StatisticsPage.Scores ->
            listOf(
                Group(GroupKey.Numbers, numbers = { ScoreNumbers(statistics) }),
                Group(
                    GroupKey.Scores,
                    bars = if (statistics.rated == 0) emptyList() else scoreBars(statistics),
                    nothing = stringResource(R.string.statistics_nothing_rated),
                ),
            )
        StatisticsPage.Genres ->
            listOf(
                Group(
                    GroupKey.Genres,
                    bars = statistics.genres.map { Bar(genreName(it.value), it.count) },
                    nothing = stringResource(R.string.statistics_none_known),
                ),
            )
        StatisticsPage.FormatsAndYears ->
            listOf(
                Group(
                    GroupKey.Formats,
                    heading = stringResource(R.string.statistics_formats),
                    bars = statistics.mediaTypes.map { Bar(mediaTypeLabel(it.value), it.count) },
                    nothing = stringResource(R.string.statistics_none_known),
                ),
                Group(
                    GroupKey.Years,
                    heading = stringResource(R.string.statistics_years),
                    bars = statistics.decades.map { Bar(stringResource(R.string.statistics_decade, it.value), it.count) },
                    nothing = stringResource(R.string.statistics_none_known),
                ),
            )
    }

// Every score from 10 down, so the distribution keeps its shape; each is spoken with the word.
@Composable
private fun scoreBars(statistics: UserStatistics): List<Bar> =
    (HIGHEST_SCORE downTo 1).map { score ->
        Bar(countText(score), statistics.scores[score] ?: 0, spokenLabel = stringResource(R.string.statistics_score_spoken, score))
    }

@Composable
private fun ScoreNumbers(statistics: UserStatistics) {
    val centre = Alignment.CenterHorizontally
    val mean = statistics.meanScore
    StatNumbers(Modifier.padding(horizontal = Spacing.l), alignment = centre) {
        StatNumber(
            value = mean?.let { scoreText(roundedHalfUp(it)) } ?: stringResource(R.string.statistics_no_mean_score_value),
            caption = stringResource(R.string.statistics_mean_score),
            spokenValue = if (mean == null) stringResource(R.string.statistics_no_mean_score) else null,
            horizontalAlignment = centre,
        )
        StatNumber(countText(statistics.rated), stringResource(R.string.statistics_rated), horizontalAlignment = centre)
        StatNumber(countText(statistics.unrated), stringResource(R.string.statistics_unrated), horizontalAlignment = centre)
    }
}

// The mean to two decimals, half up from the value as written in decimals, as MAL rounds its profile's numbers.
internal fun roundedHalfUp(mean: Double): Double = BigDecimal.valueOf(mean).setScale(MEAN_DIGITS, RoundingMode.HALF_UP).toDouble()

private const val HIGHEST_SCORE = 10
private const val MEAN_DIGITS = 2
