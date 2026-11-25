package io.github.kauri_off.kiroku.manga

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.CountAsShown
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class MangaTitleKey(
    val id: Long,
) : NavKey {
    @Transient
    var opened: MangaTitleSummary? = null
        private set

    constructor(opened: MangaTitleSummary) : this(opened.details.id.value) {
        this.opened = opened
    }
}

internal data class MangaTitleSummary(
    val details: MangaDetails,
    val language: TitleLanguage,
)

@Composable
internal fun MangaTitleRoute(
    key: MangaTitleKey,
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val manga =
        viewModel {
            MangaTitleViewModel(
                MangaId(key.id),
                graph.manga,
                graph.clock,
                graph.connection,
                graph.titleLanguage,
                graph.session.observeSession(),
                onRefreshFailed = { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(it))) },
                opened = key.opened,
            )
        }
    val state by manga.state.collectAsScreenState()
    CountAsShown(manga.shown)
    MangaTitleScreen(
        state,
        onEvent = {
            when (it) {
                MangaTitleEvent.Back -> onBack()
                MangaTitleEvent.SignIn -> onSignIn()
                MangaTitleEvent.Retry, MangaTitleEvent.Refresh -> manga.refresh()
            }
        },
        snackbars,
    )
}
