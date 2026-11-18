package io.github.kauri_off.kiroku.notifysettings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.serialization.Serializable

@Serializable
data object NotifySettingsKey : NavKey

// The test notification is no source's: no run reads it as its own, withdraws it or records it as sent.
private val TEST = notShown("test")

@Composable
fun NotifySettingsRoute(
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTitle: (AnimeId) -> Unit,
    onSignIn: () -> Unit,
) {
    val context = LocalContext.current
    val device = remember(context) { DeviceStates(context) }
    val tests = remember(graph) { EpisodeNotifications(context, TEST, { graph.titleLanguage }) }
    val model =
        viewModel {
            NotifySettingsViewModel(
                graph.session,
                graph.notifications,
                graph.list,
                graph.clock,
                graph.titleLanguage,
                graph.background,
                device::read,
                tests::test,
                messages,
            )
        }
    // A return from the system's screens resumes the screen, so what was changed there shows at once.
    LifecycleResumeEffect(model) {
        model.readDevice()
        onPauseOrDispose { }
    }
    val state by model.state.collectAsScreenState()
    NotifySettingsScreen(state, snackbars = snackbars, onEvent = { event ->
        model.onEvent(event)
        when (event) {
            NotifySettingsEvent.Back -> onBack()
            NotifySettingsEvent.SignIn -> onSignIn()
            is NotifySettingsEvent.OpenTitle -> onOpenTitle(event.id)
            is NotifySettingsEvent.OpenSetting -> device.open(context, event.setting)
            else -> Unit
        }
    })
}
