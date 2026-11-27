package io.github.kauri_off.kiroku

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.model.StartupTab
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
        // A start by a link or a notification opens on its page, so the List never shows first; a restored window keeps its stacks.
        val start = if (savedInstanceState == null) links?.take() else null
        val navigation = ActivityNavigation(savedStateRegistry)
        val graph = appGraph
        setKirokuContent(graph.preferences.observeDisplayPreferences()) { display ->
            CompositionLocalProvider(LocalSplashHold provides hold) {
                var launchLink by remember { mutableStateOf(start) }
                var startup by remember { mutableStateOf<StartupTab?>(null) }
                if (navigation.restored == null && launchLink == null && startup == null) {
                    KeepSplash()
                    val pending = links?.link?.collectAsScreenState()?.value
                    val tab = display?.startupTab
                    LaunchedEffect(tab, pending) {
                        launchLink = links?.take()
                        if (launchLink == null) startup = tab
                    }
                } else {
                    val destination = startup?.destination ?: Destination.List
                    val navigator = rememberShellNavigator(launchLink?.let { linkedPage(it.link) }, navigation, destination)
                    AppRoute(graph, navigator, links = links)
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
