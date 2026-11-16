package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.wiring.AppGraph

// A release carries no fixture user, so Profile keeps its placeholder until its data layer exists.
@Suppress("UNUSED_PARAMETER")
internal fun profileStandIn(graph: AppGraph): ProfileStandIn? = null
