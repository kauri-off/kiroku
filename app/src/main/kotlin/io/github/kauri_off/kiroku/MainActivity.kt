package io.github.kauri_off.kiroku

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.link.appLinks
import io.github.kauri_off.kiroku.notify.tappedTitle
import io.github.kauri_off.kiroku.notifysettings.AskForNotifications
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.linkedPage
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.appGraph

class MainActivity : ComponentActivity() {
    // Held at a start until the List has content; null once restored.
    var splash: SplashHold? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No field of the window is offered to autofill; a screen that needs it sets the window's root back to AUTO.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val hold = splashHold(window.decorView, savedInstanceState)
        splash = hold
        val links = appLinks
        if (savedInstanceState == null) intent.tappedTitle()?.let { links?.opened(Link.Title(it)) }
        // A start by a link or a notification opens on its page, so the List never shows first; a restored window keeps its stacks.
        val start = if (savedInstanceState == null) links?.take() else null
        setKirokuContent {
            CompositionLocalProvider(LocalSplashHold provides hold) {
                AppRoute(appGraph, rememberShellNavigator(start?.let { linkedPage(it.link) }), links = links)
                AskForNotifications(appGraph.session)
            }
        }
    }

    // A tap on a notification while the screen exists opens its title over the List, as a link does.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.tappedTitle()?.let { appLinks?.opened(Link.Title(it)) }
    }
}
