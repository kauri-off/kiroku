package io.github.kauri_off.kiroku.search

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
)

sealed interface BrowseEvent {
    data object Back : BrowseEvent

    data class Open(
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
) {
    val body =
        when (val rows = state.rows) {
            Paged.Loading -> SubScreenBody.State(ScreenState.Loading)
            is Paged.Failed ->
                SubScreenBody.State(
                    ScreenState.Error(stringResource(rows.error.message(R.string.search_browse_failed))) { onEvent(BrowseEvent.Retry) },
                )
            is Paged.Shown ->
                if (rows.items.isEmpty()) {
                    SubScreenBody.State(ScreenState.Empty(stringResource(R.string.search_browse_empty)))
                } else {
                    SubScreenBody.List {
                        pagedRows(
                            rows.items,
                            rows.more,
                            R.string.search_browse_failed,
                            onOpen = { onEvent(BrowseEvent.Open(it)) },
                            onLoadMore = { onEvent(BrowseEvent.LoadMore) },
                            onRetry = { onEvent(BrowseEvent.Retry) },
                        )
                    }
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
