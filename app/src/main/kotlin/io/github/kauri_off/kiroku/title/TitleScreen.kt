package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterCard
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown

// The title page of ARCHITECTURE section 9.4: one column in a fixed order, drawn from [state] alone.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleScreen(
    state: TitleState,
    onEvent: (TitleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHost = remember { SnackbarHostState() }
    TitleSnackbarEffect((state as? TitleState.Shown)?.snackbar, snackbarHost, onEvent)
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TitleTopBar(menu = state is TitleState.Shown, onEvent = onEvent, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        when (state) {
            TitleState.Loading -> ScreenState(ScreenState.Loading, Modifier.padding(padding))
            is TitleState.Failed ->
                ScreenState(
                    ScreenState.Error(stringResource(state.error.message()), onRetry = { onEvent(TitleEvent.Retry) }),
                    Modifier.padding(padding),
                )
            is TitleState.Shown -> TitleContent(state, onEvent, padding)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleTopBar(
    menu: Boolean,
    onEvent: (TitleEvent) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = { onEvent(TitleEvent.Back) }) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.title_back))
            }
        },
        actions = {
            if (menu) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.title_more_options))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.title_open_on_mal)) },
                            onClick = {
                                open = false
                                onEvent(TitleEvent.OpenOnMal)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.title_share)) },
                            onClick = {
                                open = false
                                onEvent(TitleEvent.Share)
                            },
                        )
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun TitleSnackbarEffect(
    snackbar: TitleSnackbar?,
    host: SnackbarHostState,
    onEvent: (TitleEvent) -> Unit,
) {
    val message =
        when (snackbar) {
            TitleSnackbar.AddedToPlanned -> stringResource(R.string.title_added_to_planned)
            TitleSnackbar.MovedToCompleted -> stringResource(R.string.title_moved_to_completed)
            null -> null
        }
    val action =
        when (snackbar) {
            TitleSnackbar.AddedToPlanned -> stringResource(R.string.title_change)
            TitleSnackbar.MovedToCompleted -> stringResource(R.string.title_undo)
            null -> null
        }
    val events by rememberUpdatedState(onEvent)
    LaunchedEffect(snackbar) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short)
        events(if (result == SnackbarResult.ActionPerformed) TitleEvent.SnackbarAction else TitleEvent.SnackbarDismissed)
    }
}

@Composable
private fun TitleContent(
    state: TitleState.Shown,
    onEvent: (TitleEvent) -> Unit,
    padding: PaddingValues,
) {
    val direction = LocalLayoutDirection.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
            contentPadding =
                PaddingValues(top = padding.calculateTopPadding() + Spacing.s, bottom = padding.calculateBottomPadding() + Spacing.l),
        ) {
            item(key = Section.Header) { Box(Modifier.testTag(TitleTags.header)) { Header(state) } }
            item(key = Section.List) { Gap(Modifier.testTag(TitleTags.list)) { ListBlock(state.entry, state.total, onEvent) } }
            state.airing?.let { block ->
                item(key = Section.Airing) { Gap(Modifier.testTag(TitleTags.airing)) { AiringBlockText(block, state) } }
            }
            val details = state.details
            if (details == null) {
                item(key = Section.Loading) { Gap { DetailsLoading() } }
            } else {
                details(details, state, onEvent)
            }
        }
    }
}

// Separates two sections.
@Composable
private fun Gap(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Spacer(Modifier.height(Spacing.xl))
        content()
    }
}

@Composable
private fun Header(state: TitleState.Shown) {
    val anime = state.anime
    Row(Modifier.padding(horizontal = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Poster(anime.posterUrl, PosterSize.Header)
        Column(Modifier.weight(1f)) {
            TitleText(
                state.title,
                maxLines = Int.MAX_VALUE,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                factsLine(anime, state.total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.l))
            val score = anime.meanScore
            StatNumber(
                value = score?.let { scoreText(it) } ?: stringResource(R.string.title_no_score_value),
                caption = stringResource(R.string.title_mal_score),
                spokenValue = if (score == null) stringResource(R.string.title_no_score) else null,
            )
            anime.rank?.let { rank ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(R.string.title_ranked, countText(rank)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ListBlock(
    entry: ListValues?,
    total: Int?,
    onEvent: (TitleEvent) -> Unit,
) {
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (entry == null) {
            Button(onClick = { onEvent(TitleEvent.AddToList) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.title_add_to_list))
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = { onEvent(TitleEvent.Edit) },
                contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.l),
            ) {
                Text(stringResource(entry.status.label()))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Icon(
                    painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
            Spacer(Modifier.weight(1f))
            val score = entry.score
            if (score == null) {
                TextButton(onClick = { onEvent(TitleEvent.Edit) }, modifier = Modifier.offset(x = Spacing.m)) {
                    Text(stringResource(R.string.title_rate))
                }
            } else {
                Text(stringResource(R.string.title_your_score, score), style = MaterialTheme.typography.bodyMedium)
            }
        }
        EpisodeStepper(
            watched = entry.watchedEpisodes,
            total = total,
            onWatchedChange = { onEvent(TitleEvent.SetWatched(it)) },
            modifier = Modifier.offset(x = -Spacing.l),
        )
    }
}

@Composable
private fun AiringBlockText(
    block: AiringBlock,
    state: TitleState.Shown,
) {
    val line1: AnnotatedString
    var line2: String? = null
    when (block) {
        is AiringBlock.Aired -> {
            val aired = airedText(block)
            val toWatch = block.toWatch?.let { toWatchText(it) }
            val separator = stringResource(R.string.title_separator)
            val emphasis = MaterialTheme.typography.labelLarge.fontWeight
            val tertiary = MaterialTheme.colorScheme.tertiary
            line1 =
                buildAnnotatedString {
                    append(aired)
                    if (toWatch != null) {
                        append(separator)
                        withStyle(SpanStyle(color = tertiary, fontWeight = emphasis)) { append(toWatch) }
                    }
                }
            line2 = block.next?.let { stringResource(R.string.title_next_episode, it.number, airTime(it.airsAt, state.now)) }
        }
        AiringBlock.CountUnknown -> line1 = buildAnnotatedString { append(stringResource(R.string.title_airing)) }
        is AiringBlock.Premieres -> line1 = buildAnnotatedString { append(premiereText(block.premiere, state.now)) }
    }
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(line1, style = MaterialTheme.typography.bodyMedium)
        line2?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DetailsLoading() {
    val loading = stringResource(R.string.title_loading_details)
    LinearProgressIndicator(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l)
            .semantics { contentDescription = loading },
    )
}

private fun LazyListScope.details(
    details: AnimeDetails,
    state: TitleState.Shown,
    onEvent: (TitleEvent) -> Unit,
) {
    val genres = state.anime.genres.orEmpty()
    if (details.synopsis != null || genres.isNotEmpty()) {
        item(key = Section.Synopsis) {
            Gap { Synopsis(details.synopsis, state.synopsisExpanded, genres, onEvent) }
        }
    }
    if (state.related.isNotEmpty()) {
        item(key = Section.Related) { Gap { Related(state, onEvent) } }
    }
    details.statistics?.let { statistics -> item(key = Section.Statistics) { Gap { Statistics(statistics) } } }
    item(key = Section.More) { Gap { More(details, onEvent) } }
}

@Composable
private fun Synopsis(
    synopsis: String?,
    expanded: Boolean,
    genres: List<Genre>,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.title_synopsis))
        Column(Modifier.padding(horizontal = Spacing.l)) {
            if (synopsis != null) {
                var cut by remember(synopsis) { mutableStateOf(false) }
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else SYNOPSIS_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
                )
                if (cut && !expanded) {
                    TextButton(onClick = { onEvent(TitleEvent.ExpandSynopsis) }, modifier = Modifier.offset(x = -Spacing.m)) {
                        Text(stringResource(R.string.title_synopsis_more))
                    }
                } else if (genres.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s))
                }
            }
            if (genres.isNotEmpty()) {
                val names = genres.map { genreName(it) }
                Text(
                    names.joinToString(stringResource(R.string.title_separator)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Related(
    state: TitleState.Shown,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        SectionHeader(
            stringResource(R.string.title_related),
            onSeeAll = if (state.moreRelated) ({ onEvent(TitleEvent.SeeAllRelated) }) else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(state.related, key = { it.related.anime.id.value }) { card ->
                val anime = card.related.anime
                PosterCard(
                    title = card.title,
                    caption1 =
                        card.related.relation
                            .stripLabel()
                            ?.let { stringResource(it) }
                            .orEmpty(),
                    caption2 = card.status?.let { stringResource(it.label()) } ?: factsLine(anime, total = null),
                    posterUrl = anime.posterUrl,
                    onClick = { onEvent(TitleEvent.OpenRelated(anime.id)) },
                )
            }
        }
    }
}

@Composable
private fun Statistics(statistics: TitleStatistics) {
    Column {
        SectionHeader(stringResource(R.string.title_statistics))
        val total = statistics.statuses.total
        for (status in STATISTICS_ORDER) {
            StatBar(stringResource(status.label()), statistics.statuses[status], total)
        }
        Text(
            pluralStringResource(R.plurals.title_members, statistics.members, countText(statistics.members)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
    }
}

@Composable
private fun More(
    details: AnimeDetails,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        MoreRow(stringResource(R.string.title_details)) { onEvent(TitleEvent.OpenDetails) }
        if (details.recommendations.isNotEmpty()) {
            MoreRow(stringResource(R.string.title_recommendations)) { onEvent(TitleEvent.OpenRecommendations) }
        }
    }
}

@Composable
private fun MoreRow(
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun DataError.message(): Int =
    when (this) {
        DataError.NoConnection -> R.string.title_no_connection
        DataError.Refused -> R.string.title_refused
        DataError.NotFound -> R.string.title_not_found
        else -> R.string.title_could_not_load
    }

private const val SYNOPSIS_LINES = 4
private val STATISTICS_ORDER = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped, ListStatus.Planned)

// Tags of the blocks that must fit above the fold, for tests.
internal object TitleTags {
    val header = notShown("title-header")
    val list = notShown("title-list-block")
    val airing = notShown("title-airing-block")
}

private enum class Section { Header, List, Airing, Loading, Synopsis, Related, Statistics, More }
