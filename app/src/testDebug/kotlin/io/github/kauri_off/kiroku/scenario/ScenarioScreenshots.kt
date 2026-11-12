package io.github.kauri_off.kiroku.scenario

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class ScenarioScreenshots : ScreenshotRegistry() {
    private val several = Scenario(offline = true, slow = true, writes = WriteFault.Refused, store = StoreFault.WritesFail)

    override val content = contentCase { ScenarioScreen(ScenarioState.Ready(Scenario()), onEvent = {}) }
    override val loading = notShown("The panel reads its switches from memory and never waits")
    override val empty = shown { ScenarioScreen(ScenarioState.Unavailable, onEvent = {}) }
    override val error = notShown("The panel makes no request that can fail")

    override val cases =
        listOf(
            case("several-on") { ScenarioScreen(ScenarioState.Ready(several), onEvent = {}) },
            case("resetting") { ScenarioScreen(ScenarioState.Ready(several, resetting = true), onEvent = {}) },
            case("marker-on-list") { OverList(Scenario(offline = true)) },
            case("marker-several-on-list") { OverList(several) },
        )
}

// The marker where the app shows it, over the List.
@Composable
private fun OverList(scenario: Scenario) =
    Box(Modifier.fillMaxSize()) {
        FixtureListScreen(ListFixtureStates.watching)
        FloatingScenarioMarker(scenario, onClick = {}, Modifier.align(Alignment.TopCenter))
    }
