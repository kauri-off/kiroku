package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.user.PublicMangaLists
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

@Serializable
internal data class PublicMangaListKey(
    val name: String,
) : NavKey

@Composable
internal fun PublicMangaListRoute(
    key: PublicMangaListKey,
    lists: PublicMangaLists,
    graph: AppGraph,
    onRefreshFailed: (DataError) -> Unit,
    snackbars: SnackbarHostState,
    onOpen: (MangaTitleKey) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val list =
        viewModel {
            PublicMangaListViewModel(
                key.name,
                lists,
                graph.connection,
                graph.titleLanguage,
                graph.session.observeSession(),
                createSavedStateHandle(),
                graph.background,
                onRefreshFailed,
            )
        }
    val state by list.state.collectAsScreenState()
    CountAsShown(list.shown)
    PublicMangaListScreen(key.name, state.copy(typed = list.typed), snackbars) { event ->
        when (event) {
            MangaListEvent.Back -> onBack()
            MangaListEvent.SignIn -> onSignIn()
            is MangaListEvent.Open -> {
                val content = state.content as? MangaListContent.Ready
                if (content != null) onOpen(MangaTitleKey(event.entry.summary(owner = null, language = content.language)))
            }
            else -> list.onEvent(event)
        }
    }
}
