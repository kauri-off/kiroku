package io.github.kauri_off.kiroku.search

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.WindowWithoutAutofill
import io.github.kauri_off.kiroku.design.component.withoutAutofill
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import kotlinx.coroutines.launch

// Search of ARCHITECTURE section 9.6: the stock search bar, which opens the full-screen search view to type in. Both
// show what the state holds for the field's text: the recent searches and the browse lists before a query, then the
// query's results. Snackbars go to [snackbars], the shell's host.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    onEvent: (SearchEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
    bar: SearchBarState = rememberSearchBarState(if (state.expanded) SearchBarValue.Expanded else SearchBarValue.Collapsed),
) {
    BackHandler(enabled = state.query.text.isNotEmpty()) { onEvent(SearchEvent.Clear) }
    // Leaving for another screen closes the search view at once, so Back returns to the results under the bar.
    val scope = rememberCoroutineScope()
    val events: (SearchEvent) -> Unit = { event ->
        if (event is SearchEvent.Open || event is SearchEvent.Browse) {
            scope.launch {
                bar.snapTo(COLLAPSED)
                onEvent(event)
            }
        } else {
            onEvent(event)
        }
    }
    val field = @Composable { SearchField(state.query, bar, events) }
    Scaffold(
        modifier = modifier,
        // The bar's own container is clear; this one hides the rows that scroll under it, up to the screen's top edge.
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    AppBarWithSearch(state = bar, inputField = field)
                    if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(SearchEvent.SignIn) })
                }
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        Body(state.content, events, padding)
    }
    // The view's rows are list items on the surface, as under the bar, so the view is the surface too.
    ExpandedFullScreenSearchBar(
        state = bar,
        inputField = field,
        colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        WindowWithoutAutofill()
        Body(
            state.content,
            events,
            WindowInsets.systemBars
                .union(WindowInsets.ime)
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: TextFieldState,
    bar: SearchBarState,
    onEvent: (SearchEvent) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val expanded = bar.currentValue == SearchBarValue.Expanded
    SearchBarDefaults.InputField(
        textFieldState = query,
        searchBarState = bar,
        onSearch = {
            keyboard?.hide()
            onEvent(SearchEvent.Submit)
        },
        modifier = Modifier.withoutAutofill(),
        // The field is spoken as "Search" already.
        placeholder = { Text(stringResource(R.string.search_placeholder), Modifier.clearAndSetSemantics {}) },
        leadingIcon = {
            if (expanded) {
                // The search view's own Back, which closes it as the system's Back does.
                val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                IconButton(onClick = { back?.onBackPressed() }) {
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
    onEvent: (SearchEvent) -> Unit,
    padding: PaddingValues,
) {
    when (content) {
        is SearchContent.Start -> CentredList(padding) { start(content.recent, onEvent) }
        is SearchContent.Results -> Results(content, onEvent, padding)
    }
}

private fun LazyListScope.start(
    recent: List<String>,
    onEvent: (SearchEvent) -> Unit,
) {
    if (recent.isNotEmpty()) {
        item(key = RECENT, contentType = HEADER) { SectionHeader(stringResource(R.string.search_recent)) }
        items(recent, key = { it }, contentType = { LINK }) { query ->
            ListItem(onClick = { onEvent(SearchEvent.SearchAgain(query)) }, content = { Text(query) })
        }
    }
    item(key = BROWSE, contentType = HEADER) {
        val gap = if (recent.isEmpty()) Modifier else Modifier.padding(top = Spacing.l)
        SectionHeader(stringResource(R.string.search_browse), gap)
    }
    items(browseLists, key = { it }, contentType = { LINK }) { list ->
        LinkRow(browseName(list)) { onEvent(SearchEvent.Browse(list)) }
    }
}

// A query of digits alone has the row that opens the title of that id above its results, also while they load or
// when there are none.
@Composable
private fun Results(
    content: SearchContent.Results,
    onEvent: (SearchEvent) -> Unit,
    padding: PaddingValues,
) {
    val openId = content.openId
    val results = content.results
    if (results is Paged.Shown && (results.items.isNotEmpty() || openId != null)) {
        CentredList(padding) {
            if (openId != null) item(key = OPEN_ID, contentType = LINK) { OpenIdRow(openId, onEvent) }
            pagedRows(
                results.items,
                results.more,
                R.string.search_failed,
                onOpen = { onEvent(SearchEvent.Open(it)) },
                onLoadMore = { onEvent(SearchEvent.LoadMore) },
                onRetry = { onEvent(SearchEvent.Retry) },
            )
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
            is Paged.Shown -> ScreenState.Empty(stringResource(R.string.search_no_results, content.query))
        }
    Centred(padding) {
        Column(Modifier.fillMaxSize()) {
            if (openId != null) OpenIdRow(openId, onEvent)
            ScreenState(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OpenIdRow(
    id: AnimeId,
    onEvent: (SearchEvent) -> Unit,
) = LinkRow(stringResource(R.string.search_open_id, id.value)) { onEvent(SearchEvent.Open(id)) }

// A row that opens another screen.
@Composable
private fun LinkRow(
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        content = { Text(label) },
    )
}

@Composable
internal fun browseName(list: BrowseList): String =
    when (list) {
        BrowseList.ThisSeason -> stringResource(R.string.search_this_season)
        BrowseList.TopAiring -> stringResource(R.string.search_top_airing)
        BrowseList.TopRated -> stringResource(R.string.search_top_rated)
        BrowseList.MostPopular -> stringResource(R.string.search_most_popular)
    }

@Composable
private fun CentredList(
    padding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    Centred(PaddingValues(start = padding.start(), end = padding.end())) {
        LazyColumn(
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

private const val COLLAPSED = 0f
private const val RECENT = 0
private const val BROWSE = 1
private const val OPEN_ID = 2
private const val HEADER = 0
private const val LINK = 1
