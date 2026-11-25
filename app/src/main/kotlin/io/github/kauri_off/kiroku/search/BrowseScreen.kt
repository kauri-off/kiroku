package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody

data class BrowseState(
    val list: BrowseList,
    // Each with its place in front for a ranked list.
    val rows: Paged<ResultRow>,
    val sessionExpired: Boolean = false,
    // Whether a row's long press opens its edit sheet: only while there is a list to edit.
    val editable: Boolean = false,
    val available: Boolean = true,
)

sealed interface BrowseEvent {
    data object Back : BrowseEvent

    data class Open(
        val id: AnimeId,
    ) : BrowseEvent

    data class Edit(
        val id: AnimeId,
    ) : BrowseEvent

    data object LoadMore : BrowseEvent

    data object Retry : BrowseEvent

    data object SignIn : BrowseEvent
}

// A browse list opened from Search: the rows of search results, page by page, under the list's name.
@Composable
fun BrowseScreen(
    state: BrowseState,
    onEvent: (BrowseEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
    autoLoadMore: Boolean = true,
) {
    val body =
        if (!state.available) {
            SubScreenBody.State(ScreenState.Empty(stringResource(R.string.search_suggestions_unavailable)))
        } else {
            browseBody(state.rows, onRetry = { onEvent(BrowseEvent.Retry) }) { rows ->
                pagedRows(
                    state.list,
                    rows.items,
                    rows.more,
                    R.string.search_browse_failed,
                    onOpen = { onEvent(BrowseEvent.Open(it)) },
                    onEdit = if (state.editable) ({ onEvent(BrowseEvent.Edit(it)) }) else null,
                    onLoadMore = { onEvent(BrowseEvent.LoadMore) },
                    onRetry = { onEvent(BrowseEvent.Retry) },
                    autoLoadMore = autoLoadMore,
                )
            }
        }
    SubScreen(
        title = browseName(state.list),
        sessionExpired = state.sessionExpired,
        onBack = { onEvent(BrowseEvent.Back) },
        onSignIn = { onEvent(BrowseEvent.SignIn) },
        snackbars = snackbars,
        body = body,
        modifier = modifier,
    )
}

@Composable
internal fun <T> browseBody(
    rows: Paged<T>,
    onRetry: () -> Unit,
    scroll: LazyListState? = null,
    content: LazyListScope.(Paged.Shown<T>) -> Unit,
): SubScreenBody =
    when (rows) {
        Paged.Loading -> SubScreenBody.State(ScreenState.Loading)
        is Paged.Failed ->
            SubScreenBody.State(
                ScreenState.Error(stringResource(rows.error.message(R.string.search_browse_failed)), onRetry),
            )
        is Paged.Shown ->
            if (rows.items.isEmpty() && rows.more == More.None) {
                SubScreenBody.State(ScreenState.Empty(stringResource(R.string.search_browse_empty)))
            } else {
                SubScreenBody.List(scroll = scroll) { content(rows) }
            }
    }
