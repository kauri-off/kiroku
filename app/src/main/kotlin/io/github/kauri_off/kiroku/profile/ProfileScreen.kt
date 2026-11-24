package io.github.kauri_off.kiroku.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.design.component.BarTitle
import io.github.kauri_off.kiroku.design.component.ListReadProgress
import io.github.kauri_off.kiroku.design.component.PageBlock
import io.github.kauri_off.kiroku.design.component.RefreshBox
import io.github.kauri_off.kiroku.design.component.ScreenScaffold
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.Spaced
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.StatNumbers
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.component.headingRoom
import io.github.kauri_off.kiroku.design.component.pageBlocks
import io.github.kauri_off.kiroku.design.component.routeImageLoader
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.currentLocale
import io.github.kauri_off.kiroku.text.genreName
import io.github.kauri_off.kiroku.text.label
import io.github.kauri_off.kiroku.text.mediaTypeLabel
import io.github.kauri_off.kiroku.title.countText
import io.github.kauri_off.kiroku.title.meanScoreText
import io.github.kauri_off.kiroku.title.roundedHalfUp
import java.text.NumberFormat

// The Profile, the user's own or another user's, drawn from [state] alone. Its snackbars
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
    ScreenScaffold(
        modifier = modifier,
        topBar = {
            when (val owner = state.owner) {
                ProfileOwner.Me ->
                    TopAppBar(
                        // The own name is in the header.
                        title = {},
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
                        title = { BarTitle(owner.name) },
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
                        StateAction(stringResource(R.string.profile_open_another)) { onEvent(ProfileEvent.StartOpenProfile()) },
                    ),
                    body,
                )
            ProfileContent.ListLoading -> state.reading?.let { ListReadProgress(it.loaded, it.total, body) }
            is ProfileContent.Failed -> ScreenState(failedState(content.error, state.owner, onEvent), body)
            is ProfileContent.Shown -> Shown(content, state, onEvent, padding, scrollBehavior.nestedScrollConnection)
        }
    }
    state.openProfile?.let { OpenProfileDialog(it, onEvent) }
}

// A list that cannot be read says why; a private list or an unknown name offers another name instead of Retry, and
// for an unknown name the dialog starts from the name that was not found.
@Composable
private fun failedState(
    error: DataError,
    owner: ProfileOwner,
    onEvent: (ProfileEvent) -> Unit,
): ScreenState {
    val label = stringResource(R.string.profile_open_another)
    val another = StateAction(label) { onEvent(ProfileEvent.StartOpenProfile()) }
    val retype = StateAction(label) { onEvent(ProfileEvent.StartOpenProfile((owner as? ProfileOwner.Other)?.name.orEmpty())) }
    return when (error) {
        DataError.PrivateList -> ScreenState.Empty(stringResource(R.string.profile_private_list), another)
        DataError.UnknownUser -> ScreenState.Empty(stringResource(R.string.profile_unknown_user), retype)
        DataError.NoConnection -> ScreenState.Error(stringResource(R.string.common_no_connection)) { onEvent(ProfileEvent.Retry) }
        DataError.Refused -> ScreenState.Error(stringResource(R.string.common_refused)) { onEvent(ProfileEvent.Retry) }
        DataError.NoClientId -> ScreenState.Error(stringResource(R.string.common_no_client_id)) { onEvent(ProfileEvent.Retry) }
        else -> ScreenState.Error(stringResource(R.string.profile_could_not_load)) { onEvent(ProfileEvent.Retry) }
    }
}

@Composable
private fun Shown(
    content: ProfileContent.Shown,
    state: ProfileState,
    onEvent: (ProfileEvent) -> Unit,
    padding: PaddingValues,
    barScroll: NestedScrollConnection,
) {
    val direction = LocalLayoutDirection.current
    val heading = headingRoom()
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
            modifier =
                Modifier
                    .widthIn(max = Sizes.contentMaxWidth)
                    .fillMaxSize()
                    .nestedScroll(barScroll)
                    .testTag(ProfileTags.page),
            contentPadding = PaddingValues(top = Spacing.s, bottom = padding.calculateBottomPadding() + Spacing.l),
        ) {
            pageBlocks(blocks(content, state.owner, heading, onEvent))
        }
    }
}

// The page in one rhythm: Spacing.l from the header to the numbers, which end it, and Spacing.xl between groups.
private fun blocks(
    content: ProfileContent.Shown,
    owner: ProfileOwner,
    heading: Dp,
    onEvent: (ProfileEvent) -> Unit,
): List<PageBlock> =
    buildList {
        val own = owner == ProfileOwner.Me
        val statistics = content.statistics
        // Another user's name is in the bar.
        val header = content.header?.takeIf { own }
        // Beside the own header the numbers keep to its start; with the name in the bar they are centred.
        val alignment = if (own) Alignment.Start else Alignment.CenterHorizontally
        add(
            PageBlock(Section.Numbers, Spacing.l) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    header?.let { Header(it) }
                    Numbers(statistics, alignment)
                }
            },
        )
        if (!own) {
            add(
                PageBlock(Section.List, Spacing.xl) {
                    WideButton(R.string.profile_view_list, tonal = true) { onEvent(ProfileEvent.ViewList) }
                },
            )
        }
        // A list of statuses the app does not know alone has no bars to show, and still its rows.
        if (statistics.statuses.total > 0) {
            add(PageBlock(Section.Statuses, Spacing.xl, lead = heading, trail = StatBarEnd) { Statuses(statistics, heading) })
        }
        if (statistics.titleCount > 0) {
            add(PageBlock(Section.Rows, Spacing.xl, lead = RowEnd, trail = RowEnd) { Rows(statistics, onEvent) })
        } else {
            add(PageBlock(Section.Nothing, Spacing.xl) { NothingOnList() })
        }
        if (own) {
            add(
                PageBlock(Section.Another, Spacing.xl) {
                    WideButton(R.string.profile_open_another, tonal = false) { onEvent(ProfileEvent.StartOpenProfile()) }
                },
            )
        }
    }

// The picture at the start, the name and the year the user joined beside it.
@Composable
private fun Header(profile: UserProfile) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile.pictureUrl)
        Column {
            Text(profile.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            profile.joined?.let {
                Text(
                    stringResource(R.string.profile_joined, it.year),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The user's picture cropped to a circle, or the person icon on the placeholder's colour when there is none.
@Composable
private fun Avatar(url: String?) {
    Box(
        Modifier
            .size(Sizes.avatar)
            .testTag(ProfileTags.avatar)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                imageLoader = routeImageLoader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// The numbers the Profile is opened for, then the list's size, its episodes and its scores.
@Composable
private fun Numbers(
    statistics: UserStatistics,
    alignment: Alignment.Horizontal,
) {
    val mean = statistics.meanScore
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        StatNumbers(alignment = alignment) {
            StatNumber(
                countText(statistics.statuses.completed),
                stringResource(R.string.profile_completed),
                horizontalAlignment = alignment,
            )
            StatNumber(daysText(statistics.daysWatched), stringResource(R.string.profile_days_watched), horizontalAlignment = alignment)
            StatNumber(
                value = mean?.let { meanScoreText(it) } ?: stringResource(R.string.profile_no_mean_score_value),
                caption = stringResource(R.string.profile_mean_score),
                spokenValue = if (mean == null) stringResource(R.string.profile_no_mean_score) else null,
                horizontalAlignment = alignment,
            )
        }
        StatNumbers(alignment = alignment) {
            StatNumber(countText(statistics.titleCount), stringResource(R.string.profile_titles), horizontalAlignment = alignment)
            StatNumber(countText(statistics.episodesWatched), stringResource(R.string.profile_episodes), horizontalAlignment = alignment)
            StatNumber(countText(statistics.scored), stringResource(R.string.profile_scored), horizontalAlignment = alignment)
        }
    }
}

@Composable
private fun daysText(days: Double): String =
    if (days == 0.0) {
        countText(0)
    } else {
        NumberFormat
            .getNumberInstance(currentLocale())
            .apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }.format(roundedHalfUp(days, 1))
    }

// The list by status, each a share of the whole list.
@Composable
private fun Statuses(
    statistics: UserStatistics,
    heading: Dp,
) {
    val statuses = statistics.statuses
    Column {
        SectionHeader(stringResource(R.string.profile_by_status))
        // The first bar's line is Spacing.m under the heading's.
        Spaced(Spacing.m - heading - StatBarEnd) {
            Column { for (status in STATUS_ORDER) StatBar(stringResource(status.label()), statuses[status], statuses.total) }
        }
    }
}

@Composable
private fun NothingOnList() {
    Text(
        stringResource(R.string.profile_nothing_on_list),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
    )
}

// The rows the rest of the statistics stand behind, each with what it holds under its name.
@Composable
private fun Rows(
    statistics: UserStatistics,
    onEvent: (ProfileEvent) -> Unit,
) {
    val separator = stringResource(R.string.common_separator)
    val listSeparator = stringResource(R.string.common_list_separator)
    val scored = statistics.scored
    val values =
        mapOf(
            ProfileStatistics.ScoreDistribution to
                if (scored > 0) {
                    stringResource(R.string.profile_scored_of, countText(scored), countText(statistics.titleCount))
                } else {
                    stringResource(R.string.profile_nothing_scored)
                },
            ProfileStatistics.Genres to
                statistics.genres
                    .take(LEADING)
                    .map { genreName(it.value) }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(listSeparator),
            ProfileStatistics.FormatsAndYears to
                listOfNotNull(
                    statistics.mediaTypes.firstOrNull()?.let {
                        stringResource(R.string.profile_counted, mediaTypeLabel(it.value), countText(it.count))
                    },
                    statistics.years.keys
                        .takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.profile_year_range, it.min(), it.max()) },
                ).takeIf { it.isNotEmpty() }?.joinToString(separator),
        )
    Column {
        for (screen in ProfileStatistics.entries) {
            ValuedRow(stringResource(screen.label()), values[screen]) { onEvent(ProfileEvent.OpenStatistics(screen)) }
        }
    }
}

@Composable
private fun ValuedRow(
    label: String,
    supporting: String?,
    onClick: () -> Unit,
) {
    val spoken = listOfNotNull(label, supporting).joinToString(stringResource(R.string.common_list_separator))
    ListItem(
        onClick = onClick,
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        supportingContent = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        // Labelled on the row itself, so a row cut off at the screen's edge is labelled too.
        modifier = Modifier.semantics { contentDescription = spoken },
        content = { Text(label) },
    )
}

// A full-width button of the medium size, as the title page's status button: tonal for the page's main action.
@Composable
private fun WideButton(
    label: Int,
    tonal: Boolean,
    onClick: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val modifier = Modifier.padding(horizontal = Spacing.l).heightIn(min = height).fillMaxWidth()
    val text = @Composable {
        Text(stringResource(label), style = ButtonDefaults.textStyleFor(height), textAlign = TextAlign.Center)
    }
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = modifier,
        ) { text() }
    } else {
        OutlinedButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = modifier,
        ) { text() }
    }
}

internal fun ProfileStatistics.label(): Int =
    when (this) {
        ProfileStatistics.ScoreDistribution -> R.string.profile_score_distribution
        ProfileStatistics.Genres -> R.string.profile_genres
        ProfileStatistics.FormatsAndYears -> R.string.profile_formats_and_years
    }

private val STATUS_ORDER = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped, ListStatus.Planned)

internal object ProfileTags {
    val avatar = notShown("profile-avatar")
    val page = notShown("profile-page")
    val nameField = notShown("profile-name-field")
}

private enum class Section { Numbers, List, Statuses, Nothing, Rows, Another }

// The genres a row names.
private const val LEADING = 3

// The blank room under a statistics bar's line, and above and under a two-line list item's lines.
private val StatBarEnd = Spacing.s
private val RowEnd = Spacing.m
