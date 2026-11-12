package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

@Serializable
data class RelatedKey(
    val id: Long,
) : NavKey

@Serializable
data class DetailsKey(
    val id: Long,
) : NavKey

@Composable
fun RelatedRoute(
    key: RelatedKey,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTitle: (AnimeId) -> Unit,
    onSignIn: () -> Unit,
) {
    val related = viewModel { RelatedViewModel(graph, AnimeId(key.id)) }
    RelatedScreen(
        state = related.state.value,
        onEvent = { event ->
            when (event) {
                RelatedEvent.Back -> onBack()
                RelatedEvent.SignIn -> onSignIn()
                is RelatedEvent.Open -> onOpenTitle(event.id)
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
    val details = viewModel { DetailsViewModel(graph, AnimeId(key.id)) }
    DetailsScreen(
        state = details.state.value,
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
