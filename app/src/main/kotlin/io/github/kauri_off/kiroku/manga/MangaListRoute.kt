package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

@Serializable
internal data object MangaListKey : NavKey

@Composable
internal fun MangaListRoute(
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpen: (MangaTitleKey) -> Unit,
) {
    val lists = graph.mangaList
    if (lists == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val list =
        viewModel {
            MangaListViewModel(lists, graph.clock, graph.connection, graph.session.observeSession(), graph.titleLanguage) {
                messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it)))
            }
        }
    val state by list.state.collectAsScreenState()
    CountAsShown(list.shown)
    key(state.owner) {
        MangaListScreen(
            state,
            onEvent = {
                when (it) {
                    MangaListEvent.Back -> onBack()
                    MangaListEvent.SignIn -> onSignIn()
                    MangaListEvent.Refresh -> list.refresh()
                    is MangaListEvent.Open -> {
                        val content = state.content as? MangaListContent.Ready
                        val owner = state.owner
                        if (content != null && owner != null) onOpen(MangaTitleKey(it.entry.summary(owner, content.language)))
                    }
                }
            },
            snackbars,
        )
    }
}

private fun MangaListEntry.summary(
    owner: SignInId,
    language: TitleLanguage,
): MangaTitleSummary = MangaTitleSummary(MangaDetails(id, titles, posterUrl, null, null, null, null, null, null, null), language, owner)
