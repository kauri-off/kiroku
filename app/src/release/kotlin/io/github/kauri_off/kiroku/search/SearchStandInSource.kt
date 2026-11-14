package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.wiring.AppGraph

// A release carries no fixture titles, so Search keeps its placeholder until its data layer exists.
@Suppress("UNUSED_PARAMETER")
internal fun searchStandIn(graph: AppGraph): SearchStandIn? = null
