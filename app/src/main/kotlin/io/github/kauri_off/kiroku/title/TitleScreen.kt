package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterCard
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.RefreshBox
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.component.StatNumber
import io.github.kauri_off.kiroku.design.component.StatNumbers
import io.github.kauri_off.kiroku.design.component.TitleSelection
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.component.WAVY_INDICATOR
import io.github.kauri_off.kiroku.design.component.stepButtonMargin
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.genreName
import io.github.kauri_off.kiroku.text.label
import io.github.kauri_off.kiroku.text.named
import io.github.kauri_off.kiroku.text.statusName

// The title page: one column in a fixed order, drawn from [state] alone. Its snackbars go to [snackbars], the host
// the caller draws.
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
    state: TitleState.Shown,
    onEvent: (TitleEvent) -> Unit,
    padding: PaddingValues,
) {
    val page = state.page
    val direction = LocalLayoutDirection.current
    // Whether the watch order was on the page before this composition; read and set without a recomposition.
    val chainShown = remember { BooleanArray(1) }
    val chainWasShown = chainShown[0]
    SideEffect { chainShown[0] = page.chain.isNotEmpty() }
    val heading = headingRoom()
    RefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onEvent(TitleEvent.Refresh) },
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
            contentPadding = PaddingValues(top = Spacing.s, bottom = padding.calculateBottomPadding() + Spacing.l),
        ) {
            item(key = Section.Header) { Box(Modifier.testTag(TitleTags.header)) { Header(page) } }
            // The list block and the airing lines are one group.
            item(key = Section.List) {
                Gap {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Box(Modifier.testTag(TitleTags.list)) { ListBlock(page.entry, page.total, onEvent) }
                        page.airing?.let { AiringItem(it, page, Modifier.testTag(TitleTags.airing)) }
                    }
                }
            }
            val details = page.details
            if (details == null) {
                when (val wait = state.detailsWait) {
                    DetailsWait.Brief -> Unit
                    DetailsWait.Loading -> item(key = Section.Loading) { Gap { DetailsLoading() } }
                    // The state's own padding separates it from the block above.
                    is DetailsWait.Failed ->
                        item(key = Section.Failed) {
                            ScreenState(
                                ScreenState.Error(stringResource(wait.error.message()), onRetry = { onEvent(TitleEvent.Retry) }),
                                Modifier.testTag(TitleTags.detailsFailed),
                            )
                        }
                }
            } else {
                details(details, state, chainWasShown, heading, onEvent)
            }
        }
    }
}

// Separates two blocks by [space], measured from the last line of the one above to the first line of the next.
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

// A gap below zero lays the block over the blank room the block above leaves under its last line.
@Composable
private fun Spaced(
    space: Dp,
    content: @Composable () -> Unit,
) = if (space >= Dp.Hairline) Gap(space, content = content) else Column(Modifier.trimTop(-space)) { content() }

// The blank room above a section header's title: the header is a touch target tall with its title centred.
@Composable
private fun headingRoom(): Dp {
    val title =
        with(LocalDensity.current) {
            MaterialTheme.typography.titleMedium.lineHeight
                .toDp()
        }
    return maxOf((LocalMinimumInteractiveComponentSize.current - title) / 2, Dp.Hairline)
}

// The poster leads; the title, its facts and MAL's numbers are centred under it. A title without a picture has no box.
@Composable
private fun Header(page: TitlePage) {
    val anime = page.anime
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
        if (anime.posterUrl != null || anime.largePosterUrl != null) {
            Poster(anime.posterUrl, PosterSize.Hero, Modifier.testTag(TitleTags.poster), large = anime.largePosterUrl)
            Spacer(Modifier.height(Spacing.l))
        }
        TitleSelection {
            TitleText(
                page.title,
                maxLines = Int.MAX_VALUE,
                style =
                    MaterialTheme.typography.headlineSmallEmphasized.copy(
                        lineBreak = LineBreak.Heading,
                        textAlign = TextAlign.Center,
                    ),
                modifier = Modifier.semantics { heading() },
            )
        }
        val facts = factsLine(page.facts)
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            SupportingText(facts, textAlign = TextAlign.Center)
        }
        Numbers(page)
    }
}

// MAL's score, the rank and the members in equal shares of the width. A score not known yet is left out, so nothing
// claims that MAL has none; the members come with the details.
@Composable
private fun Numbers(page: TitlePage) {
    val score = page.score
    val rank = page.anime.rank
    val members = page.details?.statistics?.members
    if (score == MalScore.NotKnownYet && rank == null && members == null) return
    Spacer(Modifier.height(Spacing.l))
    val centre = Alignment.CenterHorizontally
    StatNumbers(alignment = centre) {
        if (score != MalScore.NotKnownYet) {
            StatNumber(
                value = (score as? MalScore.Given)?.let { scoreText(it.value) } ?: stringResource(R.string.title_no_score_value),
                caption = stringResource(R.string.title_mal_score),
                spokenValue = if (score == MalScore.None) stringResource(R.string.title_no_score) else null,
                horizontalAlignment = centre,
            )
        }
        rank?.let {
            StatNumber(
                stringResource(R.string.title_rank_value, countText(it)),
                stringResource(R.string.title_rank),
                horizontalAlignment = centre,
            )
        }
        members?.let { StatNumber(countText(it), stringResource(R.string.title_members), horizontalAlignment = centre) }
    }
}

@Composable
private fun SupportingText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
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

// The status across the width, where "Add to list" stands for a title not on the list; under it the two values the
// user changes most: the stepper, and the score as a button that takes the rest of the stepper's line, so the line
// ends where the status button does. On a narrow screen the score takes a line of its own.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnList(
    entry: ListValues,
    total: Int?,
    onEvent: (TitleEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        StatusButton(entry.status, onEvent, Modifier.padding(horizontal = Spacing.l).fillMaxWidth())
        // A step button's touch margin counts towards the gaps.
        val margin = stepButtonMargin
        FlowRow(
            Modifier.fillMaxWidth().padding(start = Spacing.l - margin, end = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m - margin * 2),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeStepper(
                watched = entry.watchedEpisodes,
                total = total,
                onWatchedChange = { onEvent(TitleEvent.SetWatched(it)) },
                onStep = { onEvent(TitleEvent.StepWatched(it)) },
            )
            ScoreButton(entry.score, onEvent, Modifier.padding(start = margin).weight(1f))
        }
    }
}

@Composable
private fun StatusButton(
    status: EntryStatus,
    onEvent: (TitleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = { onEvent(TitleEvent.Edit) },
        shapes = ButtonDefaults.shapesFor(PrimaryButtonHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(PrimaryButtonHeight, hasEndIcon = true),
        modifier = modifier.heightIn(min = PrimaryButtonHeight),
    ) {
        Text(statusName(status), style = ButtonDefaults.textStyleFor(PrimaryButtonHeight))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(PrimaryButtonHeight)))
        Icon(
            painterResource(R.drawable.ic_arrow_drop_down),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(PrimaryButtonHeight)),
        )
    }
}

// The user's score after a star, or "Rate"; either opens the edit sheet. As tall as the count field and outlined as it
// is, as both are values of the entry.
@Composable
private fun ScoreButton(
    score: Int?,
    onEvent: (TitleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = score?.let { stringResource(R.string.title_your_score_spoken, it) }
    val height = PrimaryButtonHeight
    OutlinedButton(
        onClick = { onEvent(TitleEvent.Edit) },
        shapes = ButtonDefaults.shapesFor(height),
        border = BorderStroke(ButtonDefaults.outlinedButtonBorder().width, MaterialTheme.colorScheme.outline),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
        modifier = (if (spoken == null) modifier else modifier.semantics { contentDescription = spoken }).heightIn(min = height),
    ) {
        Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
        Text(score?.toString() ?: stringResource(R.string.title_rate), style = ButtonDefaults.textStyleFor(height))
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

// The airing lines after a calendar, with a list item's look and without its padding, so they keep the group's rhythm.
@Composable
private fun AiringItem(
    phase: AiringPhase,
    page: TitlePage,
    modifier: Modifier = Modifier,
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
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(line1, style = MaterialTheme.typography.bodyLarge)
            line2?.let { SupportingText(it) }
        }
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

// The sections under the list group, each Spacing.xl from the last line of the one above to its own first line: the
// room a block carries above its first line or under its last one is taken off the gap.
private fun LazyListScope.details(
    details: AnimeDetails,
    state: TitleState.Shown,
    chainWasShown: Boolean,
    heading: Dp,
    onEvent: (TitleEvent) -> Unit,
) {
    val page = state.page
    var trail = Dp.Hairline
    val next = { lead: Dp, after: Dp -> (Spacing.xl - trail - lead).also { trail = after } }
    if (page.chain.isNotEmpty()) {
        watchOrder(page.chain, state.chainLoading, animated = chainWasShown, next(heading, CardEnd), onEvent)
    }
    val genres = page.anime.genres.orEmpty()
    if (details.synopsis != null || genres.isNotEmpty()) {
        val space = next(if (details.synopsis != null) heading else Dp.Hairline, if (genres.isNotEmpty()) Dp.Hairline else ButtonEnd)
        item(key = Section.Synopsis) {
            Spaced(space) { Synopsis(details.synopsis, state.moment.synopsisExpanded, genres, onEvent) }
        }
    }
    // Drawn whenever there is something to open from it: the strip, or only "See all" when no related title fits it.
    if (page.related.isNotEmpty() || page.moreRelated) {
        val space = next(heading, if (page.related.isEmpty()) heading else CardEnd)
        item(key = Section.Related) { Spaced(space) { Related(page, onEvent) } }
    }
    details.statistics?.let { statistics ->
        val space = next(heading, StatBarEnd)
        item(key = Section.Statistics) { Spaced(space) { Statistics(statistics) } }
    }
    val space = next(RowLead, Dp.Hairline)
    item(key = Section.More) { Spaced(space) { More(details, onEvent) } }
}

// The text cut to its lines until expanded, the button that opens or closes it, then the genres.
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
            SelectionContainer {
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else SYNOPSIS_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }
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
            SupportingText(names.joinToString(stringResource(R.string.common_separator)), Modifier.padding(horizontal = Spacing.l))
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
                    caption2 = card.status?.let { statusName(it) } ?: typeAndYear(anime),
                    posterUrl = anime.posterUrl,
                    onClick = { onEvent(TitleEvent.OpenRelated(anime.id)) },
                )
            }
        }
    }
}

// The chain of sequels as a strip of cards in the order to watch it, opened on the title before the page's own. The
// page's own card says so and opens nothing. A card carries its place only once the whole chain is known. A title the
// walk finds fades in, as content that finishes loading does, once the section is [animated]: it arrives with the
// rest of the details without a fade. Nothing slides: a move cannot be kept to the frame the walk changes the cards,
// and every other change of height, a refresh among them, is not animated (section 8.5).
private fun LazyListScope.watchOrder(
    chain: List<ChainCard>,
    loading: Boolean,
    animated: Boolean,
    space: Dp,
    onEvent: (TitleEvent) -> Unit,
) {
    item(key = Section.WatchOrder) {
        Spaced(space) {
            Column {
                SectionHeader(stringResource(R.string.title_watch_order))
                val own = chain.indexOfFirst { it.current }
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = (own - 1).coerceAtLeast(0)),
                    contentPadding = PaddingValues(horizontal = Spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    itemsIndexed(chain, key = { _, card -> card.anime.id.value }, contentType = { _, card -> card.current }) { _, card ->
                        OrderCard(card, chain.size, onEvent, chainItem(animated))
                    }
                }
            }
        }
    }
    // Under the cards' own room, so the indicator is Spacing.m from their last line, as in a group.
    if (loading) item(key = Section.ChainLoading) { ChainLoading(chainItem(animated)) }
}

@Composable
private fun LazyItemScope.chainItem(animated: Boolean): Modifier {
    val motionScheme = MaterialTheme.motionScheme
    return Modifier.animateItem(
        fadeInSpec = if (animated) motionScheme.defaultEffectsSpec() else null,
        placementSpec = null,
        fadeOutSpec = null,
    )
}

// The strip card with the place before the type and year, then the user's status, or "This title" in the primary
// colour on the page's own card, which opens nothing. A screen reader hears the card as one sentence.
@Composable
private fun OrderCard(
    card: ChainCard,
    of: Int,
    onEvent: (TitleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anime = card.anime
    val facts = typeAndYear(anime)
    val place = card.place
    val placed =
        when {
            place == null -> facts
            facts.isEmpty() -> place.toString()
            else -> stringResource(R.string.title_place_facts, place, facts)
        }
    val last = if (card.current) stringResource(R.string.title_this_title) else card.status?.let { statusName(it) }
    val spoken =
        listOfNotNull(
            card.title,
            place?.let { pluralStringResource(R.plurals.title_place_spoken, of, it, of) },
            facts.takeIf { it.isNotEmpty() },
            last,
        ).joinToString(stringResource(R.string.common_list_separator))
    PosterCard(
        title = card.title,
        caption1 = placed,
        caption2 = last.orEmpty(),
        posterUrl = anime.posterUrl,
        onClick = if (card.current) null else ({ onEvent(TitleEvent.OpenRelated(anime.id)) }),
        modifier = modifier.testTag(TitleTags.chainCard),
        caption1Lines = 1,
        caption2Marked = card.current,
        spoken = spoken,
    )
}

@Composable
private fun ChainLoading(modifier: Modifier) {
    val loading = stringResource(R.string.title_loading_watch_order)
    LinearWavyProgressIndicator(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .testTag(WAVY_INDICATOR)
            .semantics { contentDescription = loading },
    )
}

@Composable
private fun Statistics(statistics: TitleStatistics) {
    Column {
        SectionHeader(stringResource(R.string.title_statistics))
        val total = statistics.statuses.total
        // A bar's own top padding would add to the header's room.
        Column(Modifier.trimTop(StatBarEnd)) {
            for (status in STATISTICS_ORDER) {
                StatBar(stringResource(status.label()), statistics.statuses[status], total)
            }
        }
    }
}

// Rows that open sub-screens, each with what it holds under its name.
@Composable
private fun More(
    details: AnimeDetails,
    onEvent: (TitleEvent) -> Unit,
) {
    Column {
        val facts =
            listOfNotNull(
                details.studios.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.common_list_separator)),
                details.source?.named { stringResource(it) },
            ).joinToString(stringResource(R.string.common_separator))
        MoreRow(stringResource(R.string.title_details), facts.takeIf { it.isNotEmpty() }) { onEvent(TitleEvent.OpenDetails) }
        val recommended = details.recommendations.size
        if (recommended > 0) {
            MoreRow(
                stringResource(R.string.title_recommendations),
                pluralStringResource(R.plurals.title_titles, recommended, recommended),
            ) { onEvent(TitleEvent.OpenRecommendations) }
        }
    }
}

@Composable
private fun MoreRow(
    label: String,
    supporting: String?,
    onClick: () -> Unit,
) {
    val spoken = listOfNotNull(label, supporting).joinToString(stringResource(R.string.common_list_separator))
    ListItem(
        onClick = onClick,
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        // Labelled on the row itself, so a row cut off at the screen's edge is labelled too.
        modifier = Modifier.semantics { contentDescription = spoken },
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

// The blank room under a strip card's last line, a label button's label and a statistics bar, and above a two-line
// list item's first line.
private val CardEnd = Spacing.s
private val ButtonEnd = Spacing.m
private val StatBarEnd = Spacing.s
private val RowLead = Spacing.m

// Leaves out the top [amount] of the content's own room, so it sits as close to the block above as plain text does.
private fun Modifier.trimTop(amount: Dp) =
    layout { measurable, constraints ->
        val trim = amount.roundToPx()
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
    }

// The list block's buttons, in the medium button size.
private val PrimaryButtonHeight = ButtonDefaults.MediumContainerHeight

// Where a button starts so that its label, Spacing.m inside it, lines up with the content edge.
private val ButtonEdge = Spacing.l - Spacing.m
private val STATISTICS_ORDER = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped, ListStatus.Planned)

// Tags for tests.
internal object TitleTags {
    val header = notShown("title-header")
    val poster = notShown("title-poster")
    val list = notShown("title-list-block")
    val airing = notShown("title-airing-block")
    val detailsFailed = notShown("title-details-failed")
    val chainCard = notShown("title-chain-card")
}

private enum class Section { Header, List, Loading, Failed, WatchOrder, ChainLoading, Synopsis, Related, Statistics, More }
