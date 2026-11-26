package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.list.ListPane
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

@Serializable
internal data object MangaListKey : NavKey

@Composable
internal fun mangaListPane(
    lists: MangaListReader,
    graph: AppGraph,
    messages: ShellMessages,
    onSignIn: () -> Unit,
    onOpen: (MangaTitleKey) -> Unit,
): ListPane.Manga {
    val editor = LocalMangaEditing.current
    val origin = LocalMangaEditOrigin.current
    val list =
        viewModel {
            MangaListViewModel(
                lists,
                graph.clock,
                graph.connection,
                graph.session.observeSession(),
                graph.titleLanguage,
                graph.preferences,
                createSavedStateHandle(),
                edits = editor?.state ?: flowOf(MangaEditingState()),
                onRefreshed = { editor?.listRefreshed(it) },
            ) {
                messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it)))
            }
        }
    val loaded by list.state.collectAsScreenState()
    LaunchedEffect(editor) { editor?.watch() }
    val state = list.forDisplay(loaded)
    val ready = state.content as? MangaListContent.Ready
    CountAsShown(list.shown)
    return ListPane.Manga(state) {
        when (it) {
            MangaListEvent.SignIn -> onSignIn()
            MangaListEvent.Refresh -> list.refresh()
            is MangaListEvent.Edit ->
                loaded.owner?.let { owner ->
                    if (ready != null) editor?.open(owner, it.entry.id, it.entry.titles.preferred(ready.language), origin)
                }
            is MangaListEvent.Open -> {
                val owner = state.owner
                if (ready != null && owner != null) onOpen(MangaTitleKey(it.entry.summary(owner, ready.language)))
            }
            else -> state.owner?.let { owner -> list.control(owner, it) }
        }
    }
}

private fun MangaListEntry.summary(
    owner: SignInId,
    language: TitleLanguage,
): MangaTitleSummary = MangaTitleSummary(MangaDetails(id, titles, posterUrl, null, null, null, null, null, null, null), language, owner)
