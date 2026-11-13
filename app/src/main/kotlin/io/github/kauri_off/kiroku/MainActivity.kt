package io.github.kauri_off.kiroku

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.shell.AppRoute
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
        setKirokuContent { CompositionLocalProvider(LocalSplashHold provides hold) { AppRoute(appGraph) } }
    }
}
