package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.wiring.AppGraph

internal fun searchStandIn(graph: AppGraph): SearchStandIn? =
    if (graph.madeUp) SearchFixtureStates.standIn(Fixtures.at(graph.clock)) else null
