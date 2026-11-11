package io.github.kauri_off.kiroku.title

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.edit.EditSheetRoute
import io.github.kauri_off.kiroku.edit.EditSheetViewModel
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

@Serializable
data class TitleKey(
    val id: Long,
) : NavKey

// Where the title page's links lead; the shell decides.
class TitleLinks(
    val back: () -> Unit,
    val openTitle: (AnimeId) -> Unit,
    val seeAllRelated: (AnimeId) -> Unit,
    val details: (AnimeId) -> Unit,
    val recommendations: (AnimeId) -> Unit,
    val signIn: () -> Unit,
)

@Composable
fun TitleRoute(
    key: TitleKey,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    links: TitleLinks,
) {
    val id = AnimeId(key.id)
    val title = viewModel { TitleViewModel(graph, id, createSavedStateHandle()) }
    val editor = viewModel { EditSheetViewModel(graph, createSavedStateHandle()) }
    val context = LocalContext.current
    val state = title.state.value
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
                is TitleEvent.OpenRelated -> links.openTitle(event.id)
                TitleEvent.SeeAllRelated -> links.seeAllRelated(id)
                TitleEvent.OpenDetails -> links.details(id)
                TitleEvent.OpenRecommendations -> links.recommendations(id)
                TitleEvent.SignIn -> links.signIn()
                TitleEvent.SnackbarAction -> {
                    if ((state as? TitleState.Shown)?.moment?.snackbar == TitleSnackbar.AddedToPlanned) editor.open(id)
                    title.onEvent(event)
                }
                else -> title.onEvent(event)
            }
        },
        snackbars = snackbars,
    )
    EditSheetRoute(editor)
}

fun malUrl(id: AnimeId): String = notShown("https://myanimelist.net/anime/${id.value}")

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Unit
    }
}
