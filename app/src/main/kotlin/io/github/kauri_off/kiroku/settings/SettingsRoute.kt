package io.github.kauri_off.kiroku.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsRoute(
    graph: AppGraph,
    messages: ShellMessages,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onAbout: () -> Unit,
) {
    val language by remember(graph) { graph.titleLanguage }.collectAsScreenState(initial = null)
    val adult by remember(graph) { graph.showAdultInSearch }.collectAsScreenState(initial = null)
    val display by remember(graph) { graph.preferences.observeDisplayPreferences() }.collectAsScreenState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appLanguageAvailable = Build.VERSION.SDK_INT >= 33
    SettingsScreen(
        SettingsState(language, adult, appLanguageAvailable, display),
        onEvent = { event ->
            when (event) {
                SettingsEvent.Back -> onBack()
                SettingsEvent.OpenNotifications -> onNotifications()
                SettingsEvent.OpenAbout -> onAbout()
                SettingsEvent.OpenAppLanguage ->
                    if (appLanguageAvailable) {
                        val packageUri = (notShown("package:") + context.packageName).toUri()
                        try {
                            context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, packageUri))
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                        }
                    }
                is SettingsEvent.SetTheme ->
                    scope.launch {
                        save(messages) { graph.preferences.setTheme(event.mode) }
                    }
                is SettingsEvent.SetStartupTab ->
                    scope.launch {
                        save(messages) { graph.preferences.setStartupTab(event.tab) }
                    }
                is SettingsEvent.SetTitleLanguage ->
                    scope.launch {
                        save(messages) { graph.preferences.setTitleLanguage(event.language) }
                    }
                SettingsEvent.ToggleAdultInSearch ->
                    scope.launch {
                        save(messages) { graph.preferences.changeAdultInSearch { !it } }
                    }
            }
        },
        snackbars = snackbars,
    )
}

private suspend fun save(
    messages: ShellMessages,
    write: suspend () -> Outcome<Unit>,
) {
    withContext(NonCancellable) {
        if (write() is Outcome.Failure) messages.post(ShellMessage(ShellMessage.Text.CouldNotSave))
    }
}
