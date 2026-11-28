package io.github.kauri_off.kiroku.search

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarColors
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.WindowSnackbarHost
import io.github.kauri_off.kiroku.design.component.WindowWithoutAutofill
import io.github.kauri_off.kiroku.design.component.withoutAutofill
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import kotlinx.coroutines.launch

// Search: the stock search bar, which opens the full-screen search view to type in. Both
// show what the state holds for the field's text: the recent searches and the browse lists before a query, then the
// query's results. Snackbars go to [snackbars], the shell's host.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    onEvent: (SearchEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
    rememberBar: @Composable () -> SearchBarState = {
        rememberContainedSearchBarState(if (state.expanded) SearchBarValue.Expanded else SearchBarValue.Collapsed)
    },
) {
    val onTop = LocalOnTop.current
    BackHandler(enabled = onTop && state.query.text.isNotEmpty()) { onEvent(SearchEvent.Clear) }
    // Leaving for another screen closes the view at once: a new, closed bar replaces the open one.
    // Only for events that leave the screen: a new bar learns where the collapsed bar is when Search is laid out again.
    var bars by rememberSaveable { mutableIntStateOf(0) }
    val bar = key(bars) { rememberBar() }
    val manga = state.content is SearchContent.MangaStart || state.content is SearchContent.MangaResults
    val mangaQuery = (state.content as? SearchContent.MangaResults)?.query
    val mangaScroll = mangaQuery?.let { key(it) { rememberLazyListState() } }
    val expandedMangaScroll = mangaQuery?.let { key(it) { rememberLazyListState() } }
    val events: (SearchEvent) -> Unit = { event ->
        if (event is SearchEvent.OpenManga && bar.currentValue == SearchBarValue.Expanded && expandedMangaScroll != null) {
            mangaScroll?.requestScrollToItem(expandedMangaScroll.firstVisibleItemIndex, expandedMangaScroll.firstVisibleItemScrollOffset)
        }
        when (event) {
            is SearchEvent.Open, is SearchEvent.Browse, is SearchEvent.OpenManga, is SearchEvent.OpenRecent,
            SearchEvent.SignIn, SearchEvent.Manga, SearchEvent.BrowseManga,
            -> bars++
            else -> Unit
        }
        onEvent(event)
    }
    val colors = SearchBarDefaults.containedColors(bar)
    val field = @Composable { SearchField(state.query, bar, colors.inputFieldColors, events, manga) }
    Scaffold(
        modifier = modifier,
        // The bar's own container is clear; this one hides the rows that scroll under it, up to the screen's top edge.
        topBar = {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column {
                    // Inset and then centred as the rows are, so the field's edges are the rows' content edges.
                    Box(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        AppBarWithSearch(
                            state = bar,
                            inputField = field,
                            colors = SearchBarDefaults.appBarWithSearchColors(searchBarColors = colors),
                            windowInsets = SearchBarDefaults.windowInsets.only(WindowInsetsSides.Top),
                            modifier = Modifier.widthIn(max = Sizes.contentMaxWidth),
                            contentPadding = PaddingValues(horizontal = Spacing.s),
                        )
                    }
                    if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(SearchEvent.SignIn) })
                }
            }
        },
        // While the view is open, its own host shows the messages.
        snackbarHost = { if (bar.currentValue != SearchBarValue.Expanded) ScreenSnackbarHost(snackbars) },
    ) { padding ->
        Body(
            state.content,
            state.editable,
            events,
            padding,
            ListItemDefaults.colors(),
            autoLoadMore = onTop && bar.currentValue != SearchBarValue.Expanded && bar.targetValue != SearchBarValue.Expanded,
            mangaScroll = mangaScroll,
        )
    }
    // The field itself moves from the bar into the view, whose own container fades in behind it.
    ExpandedFullScreenContainedSearchBar(
        state = bar,
        inputField = {
            Box(
                Modifier.wrapContentWidth().widthIn(max = Sizes.contentMaxWidth - Spacing.l * 2),
                propagateMinConstraints = true,
                content = { field() },
            )
        },
        colors = colors,
    ) {
        WindowWithoutAutofill()
        // The view is a window over the screen's: it shows the session banner and the shell's messages itself.
        if (state.sessionExpired) SessionBanner(onSignIn = { events(SearchEvent.SignIn) })
        // The view already keeps its content clear of the navigation bar and the keyboard.
        Box(Modifier.fillMaxSize()) {
            Body(
                state.content,
                state.editable,
                events,
                PaddingValues(),
                viewRows(colors),
                autoLoadMore = onTop,
                mangaScroll = expandedMangaScroll,
            )
            WindowSnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// The view's rows lie on the view's container.
@Composable
private fun viewRows(colors: SearchBarColors): ListItemColors = ListItemDefaults.colors(containerColor = colors.containerColor)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: TextFieldState,
    bar: SearchBarState,
    colors: TextFieldColors,
    onEvent: (SearchEvent) -> Unit,
    manga: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val expanded = bar.currentValue == SearchBarValue.Expanded
    val label = stringResource(if (manga) R.string.manga_search_placeholder else R.string.search_placeholder)
    val semantics = if (manga) Modifier.semantics { contentDescription = label } else Modifier
    SearchBarDefaults.InputField(
        textFieldState = query,
        searchBarState = bar,
        colors = colors,
        onSearch = {
            keyboard?.hide()
            onEvent(SearchEvent.Submit)
        },
        modifier = Modifier.withoutAutofill().fillMaxWidth().then(semantics),
        placeholder = { Text(label, Modifier.clearAndSetSemantics {}) },
        leadingIcon = {
            if (expanded) {
                // The search view's own Back, which closes it as the system's Back does.
                val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                IconButton(onClick = { back?.onBackPressed() }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                }
            } else if (manga) {
                IconButton(onClick = { onEvent(SearchEvent.Back) }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                }
            } else {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
            }
        },
        trailingIcon =
            if (query.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onEvent(SearchEvent.Clear) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                    }
                }
            } else {
                null
            },
    )
}

@Composable
private fun Body(
    content: SearchContent,
    editable: Boolean,
    onEvent: (SearchEvent) -> Unit,
    padding: PaddingValues,
    rows: ListItemColors,
    autoLoadMore: Boolean,
    mangaScroll: LazyListState?,
) {
    when (content) {
        is SearchContent.Start -> CentredList(padding) { start(content, onEvent, rows) }
        is SearchContent.Results -> AnimeResults(content, editable, onEvent, padding, rows, autoLoadMore)
        is SearchContent.AnimeLink ->
            CentredList(padding) {
                item(key = OPEN_ID, contentType = LINK) { OpenIdRow(content.id, onEvent, rows) }
            }
        is SearchContent.MangaStart ->
            CentredList(padding) {
                recentRows(content.recent, onEvent, rows)
                viewedRows(content.viewed, content.recent.isNotEmpty(), onEvent, rows)
                item(key = BROWSE, contentType = HEADER) {
                    val gap = if (content.recent.isEmpty() && content.viewed.isEmpty()) Modifier else Modifier.padding(top = Spacing.l)
                    SectionHeader(stringResource(R.string.search_browse), gap)
                }
                item(key = MANGA, contentType = LINK) {
                    LinkRow(stringResource(R.string.manga_ranking), rows) { onEvent(SearchEvent.BrowseManga) }
                }
            }
        is SearchContent.MangaResults ->
            Results(content.query, content.results, onEvent, padding, scroll = mangaScroll) { shown ->
                pagedManga(
                    content.query,
                    shown,
                    R.string.search_failed,
                    onOpen = { onEvent(SearchEvent.OpenManga(it)) },
                    onLoadMore = { onEvent(SearchEvent.LoadMore) },
                    onRetry = { onEvent(SearchEvent.Retry) },
                    autoLoadMore = autoLoadMore,
                    colors = rows,
                )
            }
    }
}

private fun LazyListScope.start(
    content: SearchContent.Start,
    onEvent: (SearchEvent) -> Unit,
    rows: ListItemColors,
) {
    recentRows(content.recent, onEvent, rows)
    viewedRows(content.viewed, content.recent.isNotEmpty(), onEvent, rows)
    item(key = BROWSE, contentType = HEADER) {
        val gap = if (content.recent.isEmpty() && content.viewed.isEmpty()) Modifier else Modifier.padding(top = Spacing.l)
        SectionHeader(stringResource(R.string.search_browse), gap)
    }
    if (content.suggestions) {
        item(key = BrowseList.Suggestions, contentType = LINK) {
            LinkRow(browseName(BrowseList.Suggestions), rows) { onEvent(SearchEvent.Browse(BrowseList.Suggestions)) }
        }
    }
    items(browseLists, key = { it }, contentType = { LINK }) { list ->
        LinkRow(browseName(list), rows) { onEvent(SearchEvent.Browse(list)) }
    }
    if (content.mangaAvailable) {
        item(key = MANGA, contentType = LINK) {
            LinkRow(stringResource(R.string.manga_catalogue), rows) { onEvent(SearchEvent.Manga) }
        }
    }
}

private fun LazyListScope.recentRows(
    recent: List<String>,
    onEvent: (SearchEvent) -> Unit,
    rows: ListItemColors,
) {
    if (recent.isNotEmpty()) {
        item(key = RECENT, contentType = HEADER) { SectionHeader(stringResource(R.string.search_recent)) }
        items(recent, key = { it }, contentType = { RECENT_QUERY }) { query ->
            RecentSearchRow(query, rows, onEvent)
        }
    }
}

private fun LazyListScope.viewedRows(
    viewed: List<RecentTitleRow>,
    afterQueries: Boolean,
    onEvent: (SearchEvent) -> Unit,
    rows: ListItemColors,
) {
    if (viewed.isEmpty()) return
    item(key = VIEWED, contentType = HEADER) {
        val gap = if (afterQueries) Modifier.padding(top = Spacing.l) else Modifier
        SectionHeader(stringResource(R.string.search_recent_titles), gap)
    }
    items(viewed, key = { (it.key is RecentTitleId.Manga) to it.key.value }, contentType = { VIEWED_TITLE }) { title ->
        DismissibleHistoryRow(
            removeLabel = stringResource(R.string.search_remove_viewed),
            onRemove = { onEvent(SearchEvent.RemoveViewed(title.key)) },
            onClick = { onEvent(SearchEvent.OpenRecent(title.key)) },
            colors = rows,
            supportingContent = {
                Text(stringResource(if (title.key is RecentTitleId.Manga) R.string.list_mode_manga else R.string.list_mode_anime))
            },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
            content = { Text(title.title) },
        )
    }
}

@Composable
private fun RecentSearchRow(
    query: String,
    rows: ListItemColors,
    onEvent: (SearchEvent) -> Unit,
) {
    DismissibleHistoryRow(
        removeLabel = stringResource(R.string.search_remove_recent),
        onRemove = { onEvent(SearchEvent.RemoveRecent(query)) },
        onClick = { onEvent(SearchEvent.SearchAgain(query)) },
        colors = rows,
        content = { Text(query) },
    )
}

@Composable
private fun DismissibleHistoryRow(
    removeLabel: String,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    colors: ListItemColors,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val scope = rememberCoroutineScope()
    val remove by rememberUpdatedState(onRemove)
    val dismiss: (SwipeToDismissBoxValue) -> Unit =
        remember(swipe, scope) {
            {
                remove()
                scope.launch { swipe.snapTo(SwipeToDismissBoxValue.Settled) }
            }
        }
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.clipToBounds(),
        onDismiss = dismiss,
        backgroundContent = {
            val direction = swipe.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = Spacing.l),
                        contentAlignment =
                            if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = null)
                    }
                }
            }
        },
    ) {
        ListItem(
            onClick = onClick,
            modifier =
                Modifier.semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(removeLabel) {
                                onRemove()
                                true
                            },
                        )
                },
            colors = colors,
            supportingContent = supportingContent,
            trailingContent = trailingContent,
            content = content,
        )
    }
}

// A query of digits alone has the row that opens the title of that id above its results, and above the state that
// stands in for them.
@Composable
private fun AnimeResults(
    content: SearchContent.Results,
    editable: Boolean,
    onEvent: (SearchEvent) -> Unit,
    padding: PaddingValues,
    rows: ListItemColors,
    autoLoadMore: Boolean,
) {
    val header: (@Composable () -> Unit)? = content.openId?.let { id -> { OpenIdRow(id, onEvent, rows) } }
    Results(content.query, content.results, onEvent, padding, header) { shown ->
        pagedRows(
            content.query,
            shown.items,
            shown.more,
            R.string.search_failed,
            onOpen = { onEvent(SearchEvent.Open(it)) },
            onEdit = if (editable) ({ onEvent(SearchEvent.Edit(it)) }) else null,
            onLoadMore = { onEvent(SearchEvent.LoadMore) },
            onRetry = { onEvent(SearchEvent.Retry) },
            colors = rows,
            autoLoadMore = autoLoadMore,
        )
    }
}

@Composable
private fun <T> Results(
    query: String,
    results: Paged<T>,
    onEvent: (SearchEvent) -> Unit,
    padding: PaddingValues,
    header: (@Composable () -> Unit)? = null,
    scroll: LazyListState? = null,
    content: LazyListScope.(Paged.Shown<T>) -> Unit,
) {
    if (results is Paged.Shown && (results.items.isNotEmpty() || results.more != More.None)) {
        CentredList(padding, scroll) {
            if (header != null) item(key = OPEN_ID, contentType = LINK) { header() }
            content(results)
        }
        return
    }
    val state =
        when (results) {
            Paged.Loading -> ScreenState.Loading
            is Paged.Failed ->
                ScreenState.Error(
                    stringResource(results.error.message(R.string.search_failed)),
                ) { onEvent(SearchEvent.Retry) }
            is Paged.Shown -> ScreenState.Empty(stringResource(R.string.search_no_results, query))
        }
    Centred(padding) {
        Column(Modifier.fillMaxSize()) {
            header?.invoke()
            ScreenState(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OpenIdRow(
    id: AnimeId,
    onEvent: (SearchEvent) -> Unit,
    rows: ListItemColors,
) = LinkRow(stringResource(R.string.search_open_id, id.value), rows) { onEvent(SearchEvent.Open(id)) }

// A row that opens another screen.
@Composable
private fun LinkRow(
    label: String,
    colors: ListItemColors,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        colors = colors,
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        content = { Text(label) },
    )
}

@Composable
internal fun browseName(list: BrowseList): String =
    when (list) {
        BrowseList.Suggestions -> stringResource(R.string.search_suggestions)
        BrowseList.ThisSeason -> stringResource(R.string.search_this_season)
        BrowseList.TopAiring -> stringResource(R.string.search_top_airing)
        BrowseList.TopRated -> stringResource(R.string.search_top_rated)
        BrowseList.MostPopular -> stringResource(R.string.search_most_popular)
    }

@Composable
private fun CentredList(
    padding: PaddingValues,
    scroll: LazyListState? = null,
    content: LazyListScope.() -> Unit,
) {
    Centred(PaddingValues(start = padding.start(), end = padding.end())) {
        LazyColumn(
            state = scroll ?: rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.s),
            content = content,
        )
    }
}

// The content width, centred on wider windows.
@Composable
private fun Centred(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = Sizes.contentMaxWidth)) { content() }
    }
}

@Composable
private fun PaddingValues.start() = calculateStartPadding(LocalLayoutDirection.current)

@Composable
private fun PaddingValues.end() = calculateEndPadding(LocalLayoutDirection.current)

private const val RECENT = 0
private const val BROWSE = 1
private const val OPEN_ID = 2
private const val MANGA = 3
private const val VIEWED = 4
private const val VIEWED_TITLE = 3
private const val HEADER = 0
private const val LINK = 1
private const val RECENT_QUERY = 2
