package io.github.kauri_off.kiroku.title

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.edit.EditSheetRoute
import io.github.kauri_off.kiroku.edit.editSheetViewModel
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TitleKey(
    val id: Long,
) : NavKey {
    // Not saved: a page restored after process death starts from the store.
    @Transient
    var opened: TitleSummary? = null
        private set

    constructor(id: Long, opened: TitleSummary?) : this(id) {
        this.opened = opened
    }
}

// Where the title page's links lead; the shell decides.
class TitleLinks(
    val back: () -> Unit,
    val openTitle: (AnimeId, TitleSummary?) -> Unit,
    val seeAllRelated: (AnimeId, TitleSummary?) -> Unit,
    val details: (AnimeId, TitleSummary?) -> Unit,
    val recommendations: (AnimeId) -> Unit,
    val signIn: () -> Unit,
)

@Composable
fun TitleRoute(
    key: TitleKey,
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    links: TitleLinks,
) {
    val id = AnimeId(key.id)
    val title =
        viewModel {
            TitleViewModel(
                id,
                graph.anime,
                graph.list,
                graph.airing,
                graph.session,
                graph.clock,
                graph.titleLanguage,
                createSavedStateHandle(),
                messages,
                key.opened,
            )
        }
    val editor = editSheetViewModel(graph, messages)
    val context = LocalContext.current
    val state by title.state.collectAsScreenState()
    TitleScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                TitleEvent.Back -> links.back()
                TitleEvent.OpenOnMal -> context.open(Intent(Intent.ACTION_VIEW, malUrl(id).toUri()))
                TitleEvent.Share ->
                    context.open(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType(notShown("text/plain")).putExtra(Intent.EXTRA_TEXT, malUrl(id)),
                            null,
                        ),
                    )
                TitleEvent.Edit -> editor.open(id)
                is TitleEvent.OpenRelated -> links.openTitle(event.id, title.summaryOf(event.id))
                TitleEvent.SeeAllRelated -> links.seeAllRelated(id, title.summaryOf(id))
                TitleEvent.OpenDetails -> links.details(id, title.summaryOf(id))
                TitleEvent.OpenRecommendations -> links.recommendations(id)
                TitleEvent.SignIn -> links.signIn()
                else -> title.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
    EditSheetRoute(editor, messages, takes = { it == id })
}

fun malUrl(id: AnimeId): String = notShown("https://myanimelist.net/anime/${id.value}")

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Unit
    }
}
