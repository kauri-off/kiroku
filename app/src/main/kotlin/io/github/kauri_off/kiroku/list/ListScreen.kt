package io.github.kauri_off.kiroku.list

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RefreshBox
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowLongPress
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.design.theme.settleMillis
import io.github.kauri_off.kiroku.text.label

// The screen owns the pager, so the tab row follows the finger; the tab the pager settles on is sent as SelectTab.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    state: ListState,
    onEvent: (ListEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.filter != null) { onEvent(ListEvent.EndFilter) }
    val tabs = state.content as? ListContent.Tabs
    val statuses = ListStatus.entries
    val pager = rememberPagerState(initialPage = statuses.indexOf(state.tab)) { statuses.size }
    val motion = rememberListTabMotion(pager)
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopBar(state, showActions = tabs != null, onEvent = onEvent)
                if (tabs != null) ListTabs(motion, onEvent)
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(ListEvent.SignIn) })
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        val body = Modifier.padding(top = padding.calculateTopPadding())
        when (val content = state.content) {
            ListContent.Loading -> ScreenState(ScreenState.Loading, body)
            ListContent.SignedOut ->
                ScreenState(
                    ScreenState.Empty(
                        stringResource(R.string.list_signed_out),
                        StateAction(stringResource(R.string.list_sign_in)) { onEvent(ListEvent.SignIn) },
                    ),
                    body,
                )
            is ListContent.Failed ->
                ScreenState(ScreenState.Error(stringResource(content.failure.messageRes())) { onEvent(ListEvent.Retry) }, body)
            is ListContent.Tabs ->
                RefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { onEvent(ListEvent.Refresh) },
                    modifier = body.fillMaxSize(),
                ) {
                    Pages(state, content, motion, PaddingValues(bottom = padding.calculateBottomPadding()), onEvent)
                }
        }
    }
    val page = state.page
    if (state.sortSheetOpen && page != null) SortSheet(page.sort, page.sorts, onEvent)
}

// One page per tab, each with its own scroll position, and its neighbours composed ahead.
@Composable
private fun Pages(
    state: ListState,
    tabs: ListContent.Tabs,
    motion: ListTabMotion,
    padding: PaddingValues,
    onEvent: (ListEvent) -> Unit,
) {
    val statuses = ListStatus.entries
    val pager = motion.pager
    val events = rememberUpdatedState(onEvent)
    val tab = rememberUpdatedState(state.tab)
    LaunchedEffect(motion) {
        motion.followRest { index -> if (statuses[index] != tab.value) events.value(ListEvent.SelectTab(statuses[index])) }
    }
    LaunchedEffect(state.tab) { motion.show(statuses.indexOf(state.tab)) }
    HorizontalPager(
        pager,
        Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        key = { statuses[it] },
        flingBehavior =
            PagerDefaults.flingBehavior(
                pager,
                snapAnimationSpec = motion.pageSpec,
                snapPositionalThreshold = PAGE_TURN,
            ),
    ) { index ->
        Page(state, statuses[index], tabs.pages.getValue(statuses[index]), padding, onEvent)
    }
}

// The unfiltered rows and the filtered ones keep separate scroll positions, so ending the filter returns the user
// to where they were. A new order or filter text replaces the rows at once, from the top; only rows leaving or
// returning within one order animate (ARCHITECTURE 8.5).
@Composable
private fun Page(
    state: ListState,
    status: ListStatus,
    page: ListPage,
    padding: PaddingValues,
    onEvent: (ListEvent) -> Unit,
) {
    val positions = rememberSaveableStateHolder()
    val filter = state.filter.orEmpty()
    // Keyed by whether the rows are unfiltered.
    positions.SaveableStateProvider(filter.isEmpty()) {
        key(page.sort, page.ordering, filter) {
            if (page.rows.isEmpty()) {
                // A list, so that an empty tab can be pulled to refresh too.
                val empty = emptyState(state, status, onEvent)
                LazyColumn(Modifier.fillMaxSize()) { item { ScreenState(empty, Modifier.fillParentMaxSize()) } }
            } else {
                Rows(state, page.rows, padding, onEvent)
            }
        }
    }
    LaunchedEffect(filter.isEmpty()) { if (filter.isEmpty()) positions.removeState(false) }
}

@Composable
private fun emptyState(
    state: ListState,
    status: ListStatus,
    onEvent: (ListEvent) -> Unit,
): ScreenState =
    if (state.filter.isNullOrEmpty()) {
        val search = StateAction(stringResource(R.string.list_empty_action)) { onEvent(ListEvent.Search) }
        ScreenState.Empty(
            stringResource(R.string.list_empty, stringResource(status.label())),
            search.takeIf { state.owner == ListOwner.Me },
        )
    } else {
        ScreenState.Empty(stringResource(R.string.list_no_matches))
    }

private fun ListFailure.messageRes(): Int =
    when (this) {
        ListFailure.NoConnection -> R.string.common_no_connection
        ListFailure.Refused -> R.string.common_refused
        ListFailure.Other -> R.string.list_failed
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    state: ListState,
    showActions: Boolean,
    onEvent: (ListEvent) -> Unit,
) {
    val filter = state.filter
    val owner = state.owner
    TopAppBar(
        title = {
            when {
                filter != null -> FilterField(filter, onEvent)
                owner is ListOwner.Other -> Text(stringResource(R.string.list_title_other, owner.name))
                else -> Text(stringResource(R.string.list_title))
            }
        },
        navigationIcon = {
            when {
                filter != null -> BackButton { onEvent(ListEvent.EndFilter) }
                owner is ListOwner.Other -> BackButton { onEvent(ListEvent.Back) }
            }
        },
        actions = {
            if (showActions && filter == null) {
                IconButton(onClick = { onEvent(ListEvent.StartFilter) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_filter), contentDescription = stringResource(R.string.list_filter))
                }
                IconButton(onClick = { onEvent(ListEvent.OpenSort) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.list_sort))
                }
            }
        },
    )
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
    }
}

// The top app bar's title becomes this field, on the bar's own surface with no rule of its own.
@Composable
private fun FilterField(
    text: String,
    onEvent: (ListEvent) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val surface = MaterialTheme.colorScheme.surface
    TextField(
        value = text,
        onValueChange = { onEvent(ListEvent.FilterChanged(it)) },
        placeholder = { Text(stringResource(R.string.list_filter)) },
        singleLine = true,
        trailingIcon =
            if (text.isNotEmpty()) {
                {
                    IconButton(onClick = { onEvent(ListEvent.EndFilter) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.list_filter_clear))
                    }
                }
            } else {
                null
            },
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = surface,
                unfocusedContainerColor = surface,
                disabledContainerColor = surface,
                focusedIndicatorColor = surface,
                unfocusedIndicatorColor = surface,
            ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Rows(
    state: ListState,
    rows: List<ListRow>,
    padding: PaddingValues,
    onEvent: (ListEvent) -> Unit,
) {
    val context = LocalContext.current
    val text = remember(context, state.now) { ListText(context, state.now) }
    val edit = stringResource(R.string.list_edit)
    val motionScheme = MaterialTheme.motionScheme
    // The rows close up on the fast effects spring; across the window is the longest that takes.
    val moveMillis =
        motionScheme.fastEffectsSpec<Float>().settleMillis(
            LocalWindowInfo.current.containerSize.height
                .toFloat(),
        )
    val guard = remember { PlusOneGuard(moveMillis) }
    SideEffect {
        guard.moveMillis = moveMillis
        guard.shown(rows.map { it.id }, SystemClock.uptimeMillis())
    }
    val shownRows = rememberUpdatedState(rows)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(), contentPadding = padding) {
            // A row is reused only for a row of its own form.
            items(rows, key = { it.id.value }, contentType = { Triple(it.line3 != null, it.line3 is Line3.ToWatch, it.plusOne) }) { row ->
                AnimeRow(
                    title = row.title,
                    posterUrl = row.posterUrl,
                    onClick = { onEvent(ListEvent.Open(row.id)) },
                    lines =
                        listOfNotNull(
                            RowLine(text.line2(row.line2)),
                            row.line3?.let { RowLine(text.line3(it), highlighted = it is Line3.ToWatch) },
                        ),
                    trailing =
                        if (row.plusOne) {
                            RowTrailing.PlusOne(prominent = row.line3 is Line3.ToWatch) {
                                val ids = shownRows.value.map { it.id }
                                if (guard.accept(row.id, ids, SystemClock.uptimeMillis())) onEvent(ListEvent.PlusOne(row.id))
                            }
                        } else {
                            null
                        },
                    longPress = RowLongPress(edit) { onEvent(ListEvent.Edit(row.id)) }.takeIf { state.owner == ListOwner.Me },
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = motionScheme.defaultEffectsSpec(),
                            placementSpec = motionScheme.fastEffectsSpec(),
                            fadeOutSpec = motionScheme.fastEffectsSpec(),
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    selected: ListSort,
    sorts: List<ListSort>,
    onEvent: (ListEvent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onEvent(ListEvent.DismissSort) },
        sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Text(
            stringResource(R.string.list_sort_by),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
        )
        Column(Modifier.selectableGroup().padding(bottom = Spacing.s)) {
            sorts.forEach { sort ->
                ListItem(
                    selected = sort == selected,
                    onClick = { onEvent(ListEvent.SelectSort(sort)) },
                    leadingContent = { RadioButton(selected = sort == selected, onClick = null) },
                    // The radio button states the choice; the row keeps the sheet's colour.
                    colors =
                        ListItemDefaults.colors(
                            containerColor = BottomSheetDefaults.ContainerColor,
                            selectedContainerColor = BottomSheetDefaults.ContainerColor,
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    content = { Text(stringResource(sort.labelRes())) },
                )
            }
        }
    }
}

// The share of a page's width a released drag must pass to turn the page; the stock half asks for a long drag.
private const val PAGE_TURN = 0.3f
