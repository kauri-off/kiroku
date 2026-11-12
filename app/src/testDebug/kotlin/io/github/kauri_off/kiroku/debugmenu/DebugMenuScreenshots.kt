package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.scenario.EdgeMarker
import io.github.kauri_off.kiroku.scenario.ResetState
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.ScenarioState
import io.github.kauri_off.kiroku.scenario.StoreFault
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import java.time.Instant

class DebugMenuScreenshots : ScreenshotRegistry() {
    private val several = Scenario(offline = true, slow = true, writes = WriteFault.Refused, store = StoreFault.WritesFail)

    override val content = contentCase { Menu(ScenarioState.Ready(Scenario())) }
    override val loading = notShown("The menu reads its switches from memory and its build from the package, and never waits")
    override val empty = notShown("The menu always has its build and the gallery; on a real account its scenarios say why there are none")
    override val error = notShown("The menu makes no request that can fail")

    override val cases =
        listOf(
            case("several-on") { Menu(ScenarioState.Ready(several)) },
            case("waiting-for-save") { Menu(ScenarioState.Ready(several, ResetState.WaitingForSave)) },
            case("save-dropped") { Menu(ScenarioState.Ready(Scenario(), ResetState.SaveDropped)) },
            case("real-account") { Menu(ScenarioState.Unavailable) },
            case("uncommitted-build") { Menu(ScenarioState.Unavailable, BUILD.copy(commit = Commit(HASH, dirty = true))) },
            case("unnamed-build") { Menu(ScenarioState.Unavailable, BUILD.copy(commit = null, built = null)) },
            case("tab-on-list") { OverList(Scenario()) },
            case("marker-on-list") { OverList(Scenario(offline = true)) },
            case("marker-several-on-list") { OverList(several) },
        )

    private companion object {
        const val HASH = "72e26f6"
        val BUILD = BuildInfo("preview", "0.1.0", Commit(HASH, dirty = false), Instant.parse("2026-11-12T09:41:00Z"))
    }

    @Composable
    private fun Menu(
        scenarios: ScenarioState,
        build: BuildInfo = BUILD,
    ) = DebugMenuScreen(DebugMenuState(scenarios, build), onEvent = {})
}

// The tab where the app shows it, over the List.
@Composable
private fun OverList(scenario: Scenario) =
    Box(Modifier.fillMaxSize()) {
        FixtureListScreen(ListFixtureStates.watching)
        EdgeMarker(scenario, Modifier, onClick = {})
    }
