package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val list =
        viewModel {
            MangaListViewModel(lists, graph.clock, graph.connection, graph.session.observeSession(), graph.titleLanguage) {
                messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it)))
            }
        }
    val loaded by list.state.collectAsScreenState()
    val editor = LocalMangaEditing.current
    LaunchedEffect(editor) { editor?.watch() }
    val editing =
        editor
            ?.state
            ?.collectAsScreenState()
            ?.value
            ?.takeIf { it.owner == loaded.owner }
    val ready = loaded.content as? MangaListContent.Ready
    val snapshot = editing?.snapshot
    val state =
        if (ready != null && snapshot != null) {
            loaded.copy(content = ready.copy(entries = snapshot.entries.map(editing::displayed), canEdit = true))
        } else {
            loaded
        }
    CountAsShown(list.shown)
    return ListPane.Manga(state) {
        when (it) {
            MangaListEvent.SignIn -> onSignIn()
            MangaListEvent.Refresh -> list.refresh()
            is MangaListEvent.Edit ->
                loaded.owner?.let { owner ->
                    if (ready != null) editor?.open(owner, it.entry.id, it.entry.titles.preferred(ready.language))
                }
            is MangaListEvent.Open -> {
                val content = state.content as? MangaListContent.Ready
                val owner = state.owner
                if (content != null && owner != null) onOpen(MangaTitleKey(it.entry.summary(owner, content.language)))
            }
        }
    }
}

private fun MangaListEntry.summary(
    owner: SignInId,
    language: TitleLanguage,
): MangaTitleSummary = MangaTitleSummary(MangaDetails(id, titles, posterUrl, null, null, null, null, null, null, null), language, owner)
