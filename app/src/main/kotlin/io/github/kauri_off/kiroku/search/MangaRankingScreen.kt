package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.titlesub.SubScreen

internal data class MangaRankingState(
    val rows: Paged<MangaResultRow>,
    val sessionExpired: Boolean = false,
)

internal sealed interface MangaRankingEvent {
    data object Back : MangaRankingEvent

    data object LoadMore : MangaRankingEvent

    data object Retry : MangaRankingEvent

    data object SignIn : MangaRankingEvent

    data class Open(
        val id: MangaId,
    ) : MangaRankingEvent
}

@Composable
internal fun MangaRankingScreen(
    state: MangaRankingState,
    onEvent: (MangaRankingEvent) -> Unit,
    snackbars: SnackbarHostState,
    autoLoadMore: Boolean = true,
) {
    val scroll = rememberLazyListState()
    SubScreen(
        title = stringResource(R.string.manga_ranking),
        sessionExpired = state.sessionExpired,
        onBack = { onEvent(MangaRankingEvent.Back) },
        onSignIn = { onEvent(MangaRankingEvent.SignIn) },
        snackbars = snackbars,
        body =
            browseBody(state.rows, onRetry = { onEvent(MangaRankingEvent.Retry) }, scroll = scroll) { shown ->
                pagedManga(
                    Unit,
                    shown,
                    R.string.search_browse_failed,
                    onOpen = { onEvent(MangaRankingEvent.Open(it)) },
                    onLoadMore = { onEvent(MangaRankingEvent.LoadMore) },
                    onRetry = { onEvent(MangaRankingEvent.Retry) },
                    autoLoadMore = autoLoadMore,
                )
            },
    )
}
