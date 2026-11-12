package io.github.kauri_off.kiroku.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.design.component.COUNTED_WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.RefreshBox
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.design.theme.isLargeText
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.label
import io.github.kauri_off.kiroku.title.countText
import io.github.kauri_off.kiroku.title.scoreText
import java.text.NumberFormat

// The Profile of ARCHITECTURE section 9.7, the user's own or another user's, drawn from [state] alone. Its snackbars
// go to [snackbars], the host the caller draws.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileState,
    onEvent: (ProfileEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                when (val owner = state.owner) {
                    ProfileOwner.Me ->
                        TopAppBar(
                            title = { Text(stringResource(R.string.profile_title)) },
                            actions = {
                                IconButton(onClick = { onEvent(ProfileEvent.OpenSettings) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_settings),
                                        contentDescription = stringResource(R.string.profile_settings),
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    is ProfileOwner.Other ->
                        TopAppBar(
                            title = { Text(owner.name) },
                            navigationIcon = {
                                IconButton(onClick = { onEvent(ProfileEvent.Back) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = stringResource(R.string.common_back),
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                }
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(ProfileEvent.SignIn) })
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        val body = Modifier.padding(padding)
        when (val content = state.content) {
            ProfileContent.Loading -> ScreenState(ScreenState.Loading, body)
            ProfileContent.SignedOut ->
                ScreenState(
                    ScreenState.Empty(
                        stringResource(R.string.profile_signed_out),
                        StateAction(stringResource(R.string.profile_sign_in)) { onEvent(ProfileEvent.SignIn) },
                    ),
                    body,
                )
            is ProfileContent.ListLoading -> ListProgress(content, body)
            is ProfileContent.Failed -> ScreenState(failedState(content.error, onEvent), body)
            is ProfileContent.Shown -> Shown(content, state, onEvent, padding)
        }
    }
    state.openProfile?.let { OpenProfileDialog(it, onEvent) }
}

// A list that cannot be read says why; a private list or an unknown name offers another name instead of Retry.
@Composable
private fun failedState(
    error: DataError,
    onEvent: (ProfileEvent) -> Unit,
): ScreenState {
    val another = StateAction(stringResource(R.string.profile_open_another)) { onEvent(ProfileEvent.StartOpenProfile) }
    return when (error) {
        DataError.PrivateList -> ScreenState.Empty(stringResource(R.string.profile_private_list), another)
        DataError.UnknownUser -> ScreenState.Empty(stringResource(R.string.profile_unknown_user), another)
        DataError.NoConnection -> ScreenState.Error(stringResource(R.string.common_no_connection)) { onEvent(ProfileEvent.Retry) }
        DataError.Refused -> ScreenState.Error(stringResource(R.string.common_refused)) { onEvent(ProfileEvent.Retry) }
        else -> ScreenState.Error(stringResource(R.string.profile_could_not_load)) { onEvent(ProfileEvent.Retry) }
    }
}

// The counted wait of a long list: the share read so far and the line "Loading 3,000 of 12,400", where the loading
// state would stand.
@Composable
private fun ListProgress(
    progress: ProfileContent.ListLoading,
    modifier: Modifier,
) {
    val loaded = countText(progress.loaded)
    val line =
        progress.total?.let { stringResource(R.string.profile_loading_list, loaded, countText(it)) }
            ?: pluralStringResource(R.plurals.profile_loading_list_unknown_total, progress.loaded, loaded)
    Box(
        modifier.fillMaxSize().padding(horizontal = Spacing.l, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = Sizes.messageMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            val total = progress.total
            val indicator = Modifier.fillMaxWidth().semantics { contentDescription = line }
            if (total != null && total > 0) {
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

@Composable
private fun Shown(
    content: ProfileContent.Shown,
    state: ProfileState,
    onEvent: (ProfileEvent) -> Unit,
    padding: PaddingValues,
) {
    val direction = LocalLayoutDirection.current
    RefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onEvent(ProfileEvent.Refresh) },
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                ),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(top = Spacing.s, bottom = padding.calculateBottomPadding() + Spacing.s),
        ) {
            content.header?.let { header -> item(key = Section.Header) { Header(header) } }
            item(key = Section.Numbers) {
                // The header's row ends in its own padding.
                Numbers(content.statistics, Modifier.padding(top = if (content.header != null) Spacing.s else Spacing.m))
            }
            item(key = Section.Statuses) { Statuses(content.statistics, Modifier.padding(top = Spacing.l)) }
            item(key = Section.Rows) { Rows(state.owner, onEvent, Modifier.padding(top = Spacing.s)) }
        }
    }
}

@Composable
private fun Header(profile: UserProfile) {
    ListItem(
        leadingContent = { Avatar(profile.pictureUrl) },
        supportingContent = profile.joined?.let { { Text(stringResource(R.string.profile_joined, it.year)) } },
        content = { Text(profile.name) },
    )
}

// The user's picture cropped to a circle, or the person icon on the placeholder's colour when there is none.
@Composable
private fun Avatar(url: String?) {
    Box(
        Modifier
            .size(Sizes.avatar)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// The three numbers the Profile is opened for: in one row, or one per line with large text (ARCHITECTURE 8.3) and
// where the row cannot give each caption its longest word.
@Composable
private fun Numbers(
    statistics: UserStatistics,
    modifier: Modifier = Modifier,
) {
    val mean = statistics.meanScore
    Layout(
        content = {
            StatNumber(countText(statistics.statuses.completed), stringResource(R.string.profile_completed))
            StatNumber(daysText(statistics.daysWatched), stringResource(R.string.profile_days_watched))
            StatNumber(
                value = mean?.let { scoreText(it) } ?: stringResource(R.string.profile_no_mean_score_value),
                caption = stringResource(R.string.profile_mean_score),
                spokenValue = if (mean == null) stringResource(R.string.profile_no_mean_score) else null,
            )
        },
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        measurePolicy = numbersPolicy(stacked = isLargeText(), gap = Spacing.m, stackedGap = Spacing.m),
    )
}

private fun numbersPolicy(
    stacked: Boolean,
    gap: Dp,
    stackedGap: Dp,
) = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    val gapPx = gap.roundToPx()
    val widths =
        if (stacked) null else rowWidths(measurables.map { it.minIntrinsicWidth(Constraints.Infinity) }, width, gapPx)
    if (widths == null) {
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val between = stackedGap.roundToPx()
        layout(width, placeables.sumOf { it.height } + between * (placeables.size - 1)) {
            var y = 0
            placeables.forEach {
                it.place(0, y)
                y += it.height + between
            }
        }
    } else {
        val placeables = measurables.mapIndexed { i, it -> it.measure(Constraints(maxWidth = widths[i])) }
        layout(width, placeables.maxOf { it.height }) {
            var x = 0
            placeables.forEachIndexed { i, it ->
                it.place(x, 0)
                x += widths[i] + gapPx
            }
        }
    }
}

// Each number's width in one row: equal shares, except that a number whose longest word is wider than its share gets
// that word's width and the others share the rest. Null when the longest words alone do not fit.
internal fun rowWidths(
    words: List<Int>,
    width: Int,
    gap: Int,
): List<Int>? {
    val space = width - gap * (words.size - 1)
    if (words.sum() > space) return null
    val fixed = mutableSetOf<Int>()
    var share: Int
    do {
        val free = words.indices - fixed
        share = (space - fixed.sumOf { words[it] }) / free.size
        val wider = free.filter { words[it] > share }
        fixed += wider
    } while (wider.isNotEmpty() && fixed.size < words.size)
    return words.indices.map { if (it in fixed) words[it] else share }
}

@Composable
private fun daysText(days: Double): String =
    NumberFormat
        .getNumberInstance(currentLocale())
        .apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(days)

@Composable
private fun Statuses(
    statistics: UserStatistics,
    modifier: Modifier = Modifier,
) {
    val statuses = statistics.statuses
    Column(modifier) {
        for (status in STATUS_ORDER) StatBar(stringResource(status.label()), statuses[status], statuses.total)
    }
}

// The sub-screens of the statistics, then the one thing to do from here: open another user, or this user's list.
@Composable
private fun Rows(
    owner: ProfileOwner,
    onEvent: (ProfileEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        for (screen in ProfileStatistics.entries) {
            LinkRow(stringResource(screen.label()), opensScreen = true) { onEvent(ProfileEvent.OpenStatistics(screen)) }
        }
        Spacer(Modifier.height(Spacing.s))
        when (owner) {
            ProfileOwner.Me ->
                LinkRow(stringResource(R.string.profile_open_another), opensScreen = false) { onEvent(ProfileEvent.StartOpenProfile) }
            is ProfileOwner.Other ->
                LinkRow(
                    stringResource(R.string.profile_view_list),
                    opensScreen = true,
                ) { onEvent(ProfileEvent.ViewList) }
        }
    }
}

// A row that opens a screen ends in a chevron; one that opens a dialog does not.
@Composable
private fun LinkRow(
    label: String,
    opensScreen: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        trailingContent = if (opensScreen) ({ Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }) else null,
        // Labelled on the row itself, so a row cut off at the screen's edge is labelled too.
        modifier = Modifier.semantics { contentDescription = label },
        content = { Text(label) },
    )
}

internal fun ProfileStatistics.label(): Int =
    when (this) {
        ProfileStatistics.ScoreDistribution -> R.string.profile_score_distribution
        ProfileStatistics.Genres -> R.string.profile_genres
        ProfileStatistics.FormatsAndYears -> R.string.profile_formats_and_years
    }

private val STATUS_ORDER = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped, ListStatus.Planned)

internal object ProfileTags {
    val nameField = notShown("profile-name-field")
}

private enum class Section { Header, Numbers, Statuses, Rows }
