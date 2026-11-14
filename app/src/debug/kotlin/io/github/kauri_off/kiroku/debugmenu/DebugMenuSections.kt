package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.runtime.Composable
import io.github.kauri_off.kiroku.scenario.ScenarioRoute
import io.github.kauri_off.kiroku.source.SourceRoute

// The menu's sections in their order. Each is one file with its own strings file; a new tool adds its line here.
val debugMenuSections: List<@Composable () -> Unit> =
    listOf(
        { ScenarioRoute() },
        { SourceRoute() },
        { BuildRoute() },
        { DesignRoute() },
    )
