package io.github.kauri_off.kiroku

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.link.appLinks
import io.github.kauri_off.kiroku.notify.tappedTitle
import io.github.kauri_off.kiroku.notifysettings.AskForNotifications
import io.github.kauri_off.kiroku.shell.ActivityNavigation
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.destination
import io.github.kauri_off.kiroku.shell.discardUnreadableComposeState
import io.github.kauri_off.kiroku.shell.linkedPage
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Held at a start until the List has content; null once restored.
    var splash: SplashHold? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        discardUnreadableComposeState(savedInstanceState)
        super.onCreate(savedInstanceState)
        // No field of the window is offered to autofill; a screen that needs it sets the window's root back to AUTO.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val hold = splashHold(window.decorView, savedInstanceState)
        splash = hold
        val links = appLinks
        if (savedInstanceState == null) intent.tappedTitle()?.let { links?.opened(Link.Title(it)) }
        val navigation = ActivityNavigation(savedStateRegistry)
        val graph = appGraph
        setKirokuContent(graph.preferences.observeDisplayPreferences()) { display ->
            CompositionLocalProvider(LocalSplashHold provides hold) {
                var showShell by remember { mutableStateOf(false) }
                if (!showShell) {
                    KeepSplash()
                    ScreenState(ScreenState.Loading, delayLoading = false)
                    val pending = links?.link?.collectAsScreenState()?.value
                    var waited by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        withContext(Dispatchers.Main) { delay(PATIENCE) }
                        waited = true
                    }
                    val ready = display != null || (waited && (pending != null || navigation.restored != null))
                    LaunchedEffect(ready) {
                        if (ready) showShell = true
                    }
                } else {
                    val start = remember { if (navigation.restored == null) links?.link?.value else null }
                    val destination = display?.startupTab?.destination ?: Destination.List
                    val navigator = rememberShellNavigator(start?.let { linkedPage(it.link) }, navigation, destination)
                    val installed = remember(navigator) { start?.takeUnless { navigator.restoredNavigation } }
                    SideEffect { if (installed != null) links?.taken(installed) }
                    AppRoute(graph, navigator, links = links.takeIf { navigation.navigator === navigator })
                    AskForNotifications(graph.session)
                }
            }
        }
    }

    // A tap on a notification while the screen exists opens its title over the List, as a link does.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.tappedTitle()?.let { appLinks?.opened(Link.Title(it)) }
    }
}
