package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.search.ResultAnimeRow
import io.github.kauri_off.kiroku.search.ResultRow

internal data class RecommendationsState(
    val content: RecommendationsContent,
    val session: Session? = null,
)

internal sealed interface RecommendationsContent {
    data object Loading : RecommendationsContent

    data class Failed(
        val error: DataError,
    ) : RecommendationsContent

    data class Shown(
        val rows: List<ResultRow>,
        val refreshing: Boolean = false,
    ) : RecommendationsContent
}

internal sealed interface RecommendationsEvent {
    data object Back : RecommendationsEvent

    data object SignIn : RecommendationsEvent

    data object Reload : RecommendationsEvent

    data class Open(
        val id: AnimeId,
    ) : RecommendationsEvent

    data class Edit(
        val id: AnimeId,
    ) : RecommendationsEvent
}

@Composable
internal fun RecommendationsScreen(
    state: RecommendationsState,
    onEvent: (RecommendationsEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val content = state.content
    val editable = state.session is Session.SignedIn || state.session is Session.Expired
    val reload = { onEvent(RecommendationsEvent.Reload) }
    val body =
        when (content) {
            RecommendationsContent.Loading -> SubScreenBody.State(ScreenState.Loading)
            is RecommendationsContent.Failed -> SubScreenBody.State(errorState(content.error, reload))
            is RecommendationsContent.Shown ->
                SubScreenBody.List(refreshing = content.refreshing, onRefresh = reload) {
                    if (content.rows.isEmpty()) {
                        item {
                            ScreenState(
                                ScreenState.Empty(stringResource(R.string.title_recommendations_empty)),
                                Modifier.fillParentMaxSize(),
                            )
                        }
                    } else {
                        items(content.rows, key = { it.id.value }) { row ->
                            ResultAnimeRow(
                                row,
                                onClick = { onEvent(RecommendationsEvent.Open(row.id)) },
                                onEdit = if (editable) ({ onEvent(RecommendationsEvent.Edit(row.id)) }) else null,
                            )
                        }
                    }
                }
        }
    SubScreen(
        title = stringResource(R.string.title_recommendations),
        sessionExpired = state.session is Session.Expired,
        onBack = { onEvent(RecommendationsEvent.Back) },
        onSignIn = { onEvent(RecommendationsEvent.SignIn) },
        snackbars = snackbars,
        body = body,
        modifier = modifier,
    )
}
