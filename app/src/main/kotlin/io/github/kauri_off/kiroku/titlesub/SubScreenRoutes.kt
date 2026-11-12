package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RelatedKey(
    val id: Long,
) : NavKey {
    @Transient
    var opened: TitleSummary? = null
        private set

    constructor(id: Long, opened: TitleSummary?) : this(id) {
        this.opened = opened
    }
}

@Serializable
data class DetailsKey(
    val id: Long,
) : NavKey {
    @Transient
    var opened: TitleSummary? = null
        private set

    constructor(id: Long, opened: TitleSummary?) : this(id) {
        this.opened = opened
    }
}

@Composable
fun RelatedRoute(
    key: RelatedKey,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTitle: (AnimeId, TitleSummary?) -> Unit,
    onSignIn: () -> Unit,
) {
    val related =
        viewModel {
            RelatedViewModel(AnimeId(key.id), graph.anime, graph.list, graph.airing, graph.session, graph.titleLanguage, key.opened)
        }
    val state by related.state.collectAsScreenState()
    RelatedScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                RelatedEvent.Back -> onBack()
                RelatedEvent.SignIn -> onSignIn()
                is RelatedEvent.Open -> onOpenTitle(event.id, related.summaryOf(event.id))
                RelatedEvent.Retry -> related.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
}

@Composable
fun DetailsRoute(
    key: DetailsKey,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val details =
        viewModel {
            DetailsViewModel(AnimeId(key.id), graph.anime, graph.session, graph.titleLanguage, graph.clock, key.opened)
        }
    val state by details.state.collectAsScreenState()
    DetailsScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                DetailsEvent.Back -> onBack()
                DetailsEvent.SignIn -> onSignIn()
                DetailsEvent.Retry -> details.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
}
