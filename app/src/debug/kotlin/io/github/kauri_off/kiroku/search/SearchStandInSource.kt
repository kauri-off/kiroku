package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.wiring.AppGraph

// Debug and preview builds search the fixture titles, moved to the graph's clock as the demo's are, until Search has
// its data layer.
internal fun searchStandIn(graph: AppGraph): SearchStandIn? = SearchFixtureStates.standIn(Fixtures.at(graph.clock))
