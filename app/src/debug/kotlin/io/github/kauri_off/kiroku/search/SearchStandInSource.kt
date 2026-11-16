package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.wiring.AppGraph

// The fixture titles, moved to the graph's clock as the demo's are, on made-up data alone, until Search has its data layer.
internal fun searchStandIn(graph: AppGraph): SearchStandIn? =
    if (graph.madeUp) SearchFixtureStates.standIn(Fixtures.at(graph.clock)) else null
