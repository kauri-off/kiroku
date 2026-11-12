package io.github.kauri_off.kiroku

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.appGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKirokuContent { AppRoute(appGraph) }
    }
}
