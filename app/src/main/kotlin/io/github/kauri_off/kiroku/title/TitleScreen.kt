package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterCard
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.stepButtonMargin
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.genreName
import io.github.kauri_off.kiroku.text.label
import io.github.kauri_off.kiroku.text.relationName

// The title page of ARCHITECTURE section 9.4: one column in a fixed order, drawn from [state] alone. Its snackbars go
// to [snackbars], the host the caller draws.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleScreen(
    state: TitleState,
    onEvent: (TitleEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TitleTopBar(menu = state is TitleState.Shown, onEvent = onEvent, scrollBehavior = scrollBehavior)
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(TitleEvent.SignIn) })
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        when (state) {
            is TitleState.Loading -> ScreenState(ScreenState.Loading, Modifier.padding(padding))
            is TitleState.Failed ->
                ScreenState(
                    ScreenState.Error(stringResource(state.error.message()), onRetry = { onEvent(TitleEvent.Retry) }),
                    Modifier.padding(padding),
                )
            is TitleState.Shown -> TitleContent(state.page, state.moment, state.detailsWaitShown, onEvent, padding)
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
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
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
private fun TitleContent(
    page: TitlePage,
    moment: TitleMoment,
    waitShown: Boolean,
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
            item(key = Section.Header) { Box(Modifier.testTag(TitleTags.header)) { Header(page) } }
            item(key = Section.List) { Gap(modifier = Modifier.testTag(TitleTags.list)) { ListBlock(page.entry, page.total, onEvent) } }
            page.airing?.let { phase ->
                item(key = Section.Airing) { Gap(modifier = Modifier.testTag(TitleTags.airing)) { AiringBlock(phase, page) } }
            }
            val details = page.details
            if (details == null) {
                if (waitShown) item(key = Section.Loading) { Gap { DetailsLoading() } }
            } else {
                details(details, page, moment.synopsisExpanded, onEvent)
            }
        }
    }
}

// Separates two sections; [space] is less than xl where the section above already ends in blank room.
@Composable
private fun Gap(
    space: Dp = Spacing.xl,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Spacer(Modifier.height(space))
        content()
    }
}

@Composable
private fun Header(page: TitlePage) {
    val anime = page.anime
    Row(Modifier.padding(horizontal = Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Poster(anime.posterUrl, PosterSize.Header)
        Column(Modifier.weight(1f)) {
            TitleText(
                page.title,
                maxLines = Int.MAX_VALUE,
                style = MaterialTheme.typography.headlineSmallEmphasized.copy(lineBreak = LineBreak.Heading),
                modifier = Modifier.semantics { heading() },
            )
            val facts = factsLine(page.facts)
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    if (entry == null) {
        Button(
            onClick = { onEvent(TitleEvent.AddToList) },
            shapes = ButtonDefaults.shapesFor(PrimaryButtonHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(PrimaryButtonHeight),
            modifier =
                Modifier
                    .padding(horizontal = Spacing.l)
                    .heightIn(min = PrimaryButtonHeight)
                    .fillMaxWidth(),
        ) {
            Text(stringResource(R.string.title_add_to_list), style = ButtonDefaults.textStyleFor(PrimaryButtonHeight))
        }
        return
    }
    OnList(entry, total, onEvent)
}

@OptIn(ExperimentalLayoutApi::class)
// The status across the width, where "Add to list" stands for a title not on the list; under it the two things the
// user changes most, each under its own label: the episodes watched and their score.
@Composable
private fun OnList(
    entry: ListValues,
    total: Int?,
    onEvent: (TitleEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        StatusButton(entry.status, onEvent, Modifier.padding(horizontal = Spacing.l).fillMaxWidth())
        // The score column starts on the block's centre line on every title, and on the content edge when it wraps
        // under the stepper.
        FlowRow(Modifier.padding(end = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(Modifier.toCentre(endPadding = Spacing.l)) {
                FieldLabel(stringResource(R.string.design_episodes_watched), Modifier.padding(start = Spacing.l))
                EpisodeStepper(
                    watched = entry.watchedEpisodes,
                    total = total,
                    onWatchedChange = { onEvent(TitleEvent.SetWatched(it)) },
                    onStep = { onEvent(TitleEvent.StepWatched(it)) },
                    modifier = Modifier.padding(start = Spacing.l - stepButtonMargin),
                )
            }
            Column(Modifier.padding(start = ButtonEdge)) {
                FieldLabel(stringResource(R.string.title_your_score_label), Modifier.padding(start = Spacing.m))
                Box(
                    Modifier
                        .heightIn(min = OutlinedTextFieldDefaults.MinHeight)
                        .then(if (entry.score == null) Modifier else Modifier.padding(start = Spacing.m)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ScoreValue(entry.score, onEvent)
                }
            }
        }
    }
}

@Composable
private fun StatusButton(
    status: ListStatus,
    onEvent: (TitleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = { onEvent(TitleEvent.Edit) },
        shapes = ButtonDefaults.shapesFor(PrimaryButtonHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(PrimaryButtonHeight, hasEndIcon = true),
        modifier = modifier.heightIn(min = PrimaryButtonHeight),
    ) {
        Text(stringResource(status.label()), style = ButtonDefaults.textStyleFor(PrimaryButtonHeight))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(PrimaryButtonHeight)))
        Icon(
            painterResource(R.drawable.ic_arrow_drop_down),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(PrimaryButtonHeight)),
        )
    }
}

@Composable
private fun FieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

// The user's score in the type role of the header's score, or "Rate" when there is none; both open the edit sheet.
@Composable
private fun ScoreValue(
    score: Int?,
    onEvent: (TitleEvent) -> Unit,
) {
    if (score == null) {
        LabelButton(stringResource(R.string.title_rate)) { onEvent(TitleEvent.Edit) }
    } else {
        Text(score.toString(), style = MaterialTheme.typography.titleLargeEmphasized)
    }
}

// A text button as wide as its label and padding, at least a touch target, so the label sits at the padding.
@Composable
private fun LabelButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.widthIn(min = LocalMinimumInteractiveComponentSize.current)) {
        Text(label)
    }
}

@Composable
private fun AiringBlock(
    phase: AiringPhase,
    page: TitlePage,
) {
    val times = rememberAirTimeText(page.now)
    val line1: AnnotatedString
    var line2: String? = null
    when (phase) {
        is AiringPhase.Aired -> {
            val aired = airedText(phase.aired)
            val toWatch = phase.toWatch?.let { toWatchText(it) }
            val separator = stringResource(R.string.common_separator)
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
            line2 = phase.next?.let { times.nextEpisode(it) }
        }
        AiringPhase.CountUnknown -> line1 = AnnotatedString(stringResource(R.string.title_airing))
        is AiringPhase.NotYetAired -> line1 = AnnotatedString(premiereText(phase.premiere, times))
    }
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(line1, style = MaterialTheme.typography.bodyMedium)
        line2?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DetailsLoading() {
    val loading = stringResource(R.string.title_loading_details)
    LinearWavyProgressIndicator(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l)
            .testTag(WAVY_INDICATOR)
            .semantics { contentDescription = loading },
    )
}

private fun LazyListScope.details(
    details: AnimeDetails,
    page: TitlePage,
    synopsisExpanded: Boolean,
    onEvent: (TitleEvent) -> Unit,
) {
    page.sequel?.let { sequel ->
        item(key = Section.Sequel) { Gap { Sequel(sequel, onEvent) } }
    }
    // The sequel's row ends in its own padding.
    val afterSequel = if (page.sequel != null) Spacing.l else Spacing.xl
    val genres = page.anime.genres.orEmpty()
    val synopsis = details.synopsis != null || genres.isNotEmpty()
    if (synopsis) {
        item(key = Section.Synopsis) {
            Gap(afterSequel) { Synopsis(details.synopsis, synopsisExpanded, genres, onEvent) }
        }
    }
    // Drawn whenever there is something to open from it: the strip, or only "See all" when no related title fits it.
    val related = page.related.isNotEmpty() || page.moreRelated
    if (related) {
        item(key = Section.Related) { Gap(if (synopsis) Spacing.xl else afterSequel) { Related(page, onEvent) } }
    }
    details.statistics?.let { statistics ->
        // The strip's cards end in their own blank room.
        val space =
            when {
                page.related.isNotEmpty() -> Spacing.s
                related -> Spacing.xl
                synopsis -> Spacing.xl
                else -> afterSequel
            }
        item(key = Section.Statistics) { Gap(space) { Statistics(statistics) } }
    }
    // A list row starts with its own padding.
    item(key = Section.More) { Gap(Spacing.s) { More(details, onEvent) } }
}

@Composable
private fun Synopsis(
    synopsis: String?,
    expanded: Boolean,
    genres: List<Genre>,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        if (synopsis != null) {
            SectionHeader(stringResource(R.string.title_synopsis))
            var cut by remember(synopsis) { mutableStateOf(false) }
            Text(
                synopsis,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else SYNOPSIS_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
            if (expanded) {
                Box(Modifier.padding(start = ButtonEdge)) {
                    LabelButton(stringResource(R.string.title_synopsis_less)) { onEvent(TitleEvent.CollapseSynopsis) }
                }
            } else if (cut) {
                Box(Modifier.padding(start = ButtonEdge)) {
                    LabelButton(stringResource(R.string.title_synopsis_more)) { onEvent(TitleEvent.ExpandSynopsis) }
                }
            } else if (genres.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
            }
        }
        if (genres.isNotEmpty()) {
            val names = genres.map { genreName(it) }
            Text(
                names.joinToString(stringResource(R.string.common_separator)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
        }
    }
}

@Composable
private fun Related(
    page: TitlePage,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        SectionHeader(
            stringResource(R.string.title_related),
            onSeeAll = if (page.moreRelated) ({ onEvent(TitleEvent.SeeAllRelated) }) else null,
        )
        if (page.related.isEmpty()) return@Column
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(page.related, key = { it.related.anime.id.value }) { card ->
                val anime = card.related.anime
                PosterCard(
                    title = card.title,
                    caption1 =
                        card.related.relation
                            .stripLabel()
                            .orEmpty(),
                    caption2 = card.status?.let { stringResource(it.label()) } ?: typeAndYear(anime),
                    posterUrl = anime.posterUrl,
                    onClick = { onEvent(TitleEvent.OpenRelated(anime.id)) },
                )
            }
        }
    }
}

// The direct sequel as a row of its own, above the strip, for whoever looks for what to watch next.
@Composable
private fun Sequel(
    sequel: RelatedCard,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        SectionHeader(relationName(RelationType.Sequel))
        val anime = sequel.related.anime
        AnimeRow(
            modifier = Modifier.trimTop(ListItemTop),
            title = sequel.title,
            posterUrl = anime.posterUrl,
            onClick = { onEvent(TitleEvent.OpenRelated(anime.id)) },
            lines =
                listOfNotNull(
                    typeAndYear(anime).takeIf { it.isNotEmpty() },
                    sequel.status?.let { stringResource(it.label()) },
                ).map { RowLine(it) },
        )
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
        onClick = onClick,
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        // Labelled on the row itself, so a row cut off at the screen's edge is labelled too.
        modifier = Modifier.semantics { contentDescription = label },
        content = { Text(label) },
    )
}

internal fun DataError.message(): Int =
    when (this) {
        DataError.NoConnection -> R.string.common_no_connection
        DataError.Refused -> R.string.common_refused
        DataError.NotFound -> R.string.title_not_found
        else -> R.string.title_could_not_load
    }

private const val SYNOPSIS_LINES = 4

// A centred list item's own top padding, which the room under a section header already provides.
private val ListItemTop = Spacing.s

// Leaves out the top [amount] of the content's own padding, so it sits as close to the header above as plain text does.
private fun Modifier.trimTop(amount: Dp) =
    layout { measurable, constraints ->
        val trim = amount.roundToPx()
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
    }

// At least as wide as the part of the block before its centre line, so the next column starts on that line.
private fun Modifier.toCentre(endPadding: Dp) =
    layout { measurable, constraints ->
        val half = ((constraints.maxWidth + endPadding.roundToPx()) / 2).coerceAtMost(constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = maxOf(constraints.minWidth, half)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

// The list block's one button, in the medium button size.
private val PrimaryButtonHeight = ButtonDefaults.MediumContainerHeight

// Where a button starts or ends so that its label or icon, Spacing.m inside it, lines up with the content edge.
private val ButtonEdge = Spacing.l - Spacing.m
private val STATISTICS_ORDER = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped, ListStatus.Planned)

// Tags of the blocks that must fit above the fold, for tests.
internal object TitleTags {
    val header = notShown("title-header")
    val list = notShown("title-list-block")
    val airing = notShown("title-airing-block")
}

private enum class Section { Header, List, Airing, Loading, Sequel, Synopsis, Related, Statistics, More }
