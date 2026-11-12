package io.github.kauri_off.kiroku

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.appGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No field of the window is offered to autofill; a screen that needs it sets the window's root back to AUTO.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setKirokuContent { AppRoute(appGraph) }
    }
}
