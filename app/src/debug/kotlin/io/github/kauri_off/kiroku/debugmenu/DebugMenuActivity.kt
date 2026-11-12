package io.github.kauri_off.kiroku.debugmenu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.scenario.ScenarioEvent
import io.github.kauri_off.kiroku.scenario.ScenarioState
import io.github.kauri_off.kiroku.scenario.Scenarios
import io.github.kauri_off.kiroku.scenario.scenarios

// The debug menu, over whatever screen of the app was open; Back returns to it.
class DebugMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKirokuContent {
            DebugMenuRoute(scenarios, onGallery = { startActivity(GalleryActivity.intent(this)) }, onClose = ::finish)
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DebugMenuActivity::class.java)
    }
}

@Composable
fun DebugMenuRoute(
    scenarios: Scenarios?,
    onGallery: () -> Unit,
    onClose: () -> Unit,
) {
    val build = remember { BuildInfo.read() }
    val scenarioState =
        if (scenarios == null) {
            ScenarioState.Unavailable
        } else {
            val scenario by scenarios.scenario.collectAsScreenState()
            val reset by scenarios.reset.collectAsScreenState()
            ScenarioState.Ready(scenario, reset)
        }
    DebugMenuScreen(DebugMenuState(scenarioState, build), onEvent = { event ->
        when (event) {
            is DebugMenuEvent.Scenario ->
                when (val change = event.event) {
                    is ScenarioEvent.Change -> scenarios?.set(change.scenario)
                    ScenarioEvent.Reset -> scenarios?.reset()
                }
            DebugMenuEvent.OpenGallery -> onGallery()
            DebugMenuEvent.Close -> onClose()
        }
    })
}
