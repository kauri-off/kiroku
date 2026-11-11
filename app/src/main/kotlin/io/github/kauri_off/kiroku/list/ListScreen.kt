package io.github.kauri_off.kiroku.list

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.theme.Motion
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing

// The user's list, or another user's read-only list: a top app bar, the five status tabs and the rows of one tab.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    state: ListState,
    onEvent: (ListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbars = remember { SnackbarHostState() }
    Messages(state.message, snackbars, onEvent)
    BackHandler(enabled = state.filter != null) { onEvent(ListEvent.EndFilter) }
    val rows = state.content as? ListContent.Rows
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopBar(state, showActions = rows != null, onEvent = onEvent)
                if (rows != null) Tabs(state.tab, onEvent)
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(ListEvent.SignIn) })
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
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
            is ListContent.Rows ->
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { onEvent(ListEvent.Refresh) },
                    modifier = body.fillMaxSize(),
                ) {
                    if (content.rows.isEmpty()) {
                        ScreenState(emptyState(state, onEvent))
                    } else {
                        Rows(state, content.rows, PaddingValues(bottom = padding.calculateBottomPadding()), onEvent)
                    }
                }
        }
    }
    if (state.sortSheetOpen) SortSheet(state.sort, state.sorts, onEvent)
}

@Composable
private fun emptyState(
    state: ListState,
    onEvent: (ListEvent) -> Unit,
): ScreenState =
    if (state.filter.isNullOrEmpty()) {
        ScreenState.Empty(
            stringResource(R.string.list_empty, stringResource(state.tab.labelRes())),
            if (state.owner ==
                ListOwner.Me
            ) {
                StateAction(stringResource(R.string.list_empty_action)) { onEvent(ListEvent.Search) }
            } else {
                null
            },
        )
    } else {
        ScreenState.Empty(stringResource(R.string.list_no_matches))
    }

private fun ListFailure.messageRes(): Int =
    when (this) {
        ListFailure.NoConnection -> R.string.list_no_connection
        ListFailure.Refused -> R.string.list_refused
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
                IconButton(onClick = { onEvent(ListEvent.StartFilter) }) {
                    Icon(painterResource(R.drawable.ic_filter), contentDescription = stringResource(R.string.list_filter))
                }
                IconButton(onClick = { onEvent(ListEvent.OpenSort) }) {
                    Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.list_sort))
                }
            }
        },
    )
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.list_back))
    }
}

// The top app bar's title becomes this field; the surface it sits on is the bar's own.
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
            ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Tabs(
    selected: ListStatus,
    onEvent: (ListEvent) -> Unit,
) {
    PrimaryScrollableTabRow(selectedTabIndex = ListStatus.entries.indexOf(selected), edgePadding = Sizes.tabRowEdge) {
        ListStatus.entries.forEach { status ->
            Tab(
                selected = status == selected,
                onClick = { onEvent(ListEvent.SelectTab(status)) },
                text = { Text(stringResource(status.labelRes())) },
            )
        }
    }
}

@Composable
private fun Rows(
    state: ListState,
    rows: List<ListRow>,
    padding: PaddingValues,
    onEvent: (ListEvent) -> Unit,
) {
    val text = ListText(LocalContext.current, state.now)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(), contentPadding = padding) {
            items(rows, key = { it.id.value }) { row ->
                AnimeRow(
                    title = row.title,
                    posterUrl = row.posterUrl,
                    onClick = { onEvent(ListEvent.Open(row.id)) },
                    lines =
                        listOfNotNull(
                            RowLine(text.line2(row.line2)),
                            row.line3?.let { RowLine(text.line3(it), highlighted = it is Line3.ToWatch) },
                        ),
                    trailing = if (row.plusOne) RowTrailing.PlusOne { onEvent(ListEvent.PlusOne(row.id)) } else null,
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = Motion.componentEnter(),
                            placementSpec = Motion.fastSpatial(),
                            fadeOutSpec = Motion.componentExit(),
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.list_sort_by),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
        )
        Column(Modifier.selectableGroup().padding(bottom = Spacing.s)) {
            sorts.forEach { sort ->
                ListItem(
                    headlineContent = { Text(stringResource(sort.labelRes())) },
                    leadingContent = { RadioButton(selected = sort == selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = BottomSheetDefaults.ContainerColor),
                    modifier =
                        Modifier.selectable(
                            selected = sort == selected,
                            role = Role.RadioButton,
                            onClick = { onEvent(ListEvent.SelectSort(sort)) },
                        ),
                )
            }
        }
    }
}

// Shows each message once; Undo and the snackbar's end are reported with the message they belong to.
@Composable
private fun Messages(
    message: ListMessage?,
    host: SnackbarHostState,
    onEvent: (ListEvent) -> Unit,
) {
    val events = rememberUpdatedState(onEvent)
    val text =
        when (val kind = message?.kind) {
            null -> null
            is ListMessage.Kind.EpisodeWatched -> stringResource(R.string.list_episode_watched, kind.episode)
            ListMessage.Kind.MovedToCompleted -> stringResource(R.string.list_moved_to_completed)
            ListMessage.Kind.CouldNotRefresh -> stringResource(R.string.list_could_not_refresh)
        }
    val undo = stringResource(R.string.list_undo).takeIf { message?.kind != ListMessage.Kind.CouldNotRefresh }
    LaunchedEffect(message) {
        if (message == null || text == null) return@LaunchedEffect
        val result =
            host.showSnackbar(
                text,
                actionLabel = undo,
                duration = if (undo == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        events.value(if (result == SnackbarResult.ActionPerformed) ListEvent.Undo(message) else ListEvent.MessageDismissed(message))
    }
}
