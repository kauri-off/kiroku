package io.github.kauri_off.kiroku.scenario

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.design.theme.setKirokuContent
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.appGraph

// The scenario panel, over whatever screen of the app was open; Back returns to it.
class ScenarioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKirokuContent { ScenarioRoute(scenarios, onClose = ::finish) }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ScenarioActivity::class.java)
    }
}

// The demo's scenarios, or null when the app runs on a real account.
val Context.scenarios: Scenarios? get() = (appGraph as? DemoGraph)?.scenarios

@Composable
fun ScenarioRoute(
    scenarios: Scenarios?,
    onClose: () -> Unit,
) {
    if (scenarios == null) {
        ScenarioScreen(ScenarioState.Unavailable, onEvent = { if (it == ScenarioEvent.Close) onClose() })
        return
    }
    val scenario by scenarios.scenario.collectAsScreenState()
    val reset by scenarios.reset.collectAsScreenState()
    ScenarioScreen(ScenarioState.Ready(scenario, reset), onEvent = { event ->
        when (event) {
            is ScenarioEvent.Change -> scenarios.set(event.scenario)
            ScenarioEvent.Reset -> scenarios.reset()
            ScenarioEvent.Close -> onClose()
        }
    })
}
