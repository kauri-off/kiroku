package io.github.kauri_off.kiroku.wiring

import kotlinx.coroutines.CoroutineScope
import java.time.Clock

@Suppress("UNUSED_PARAMETER")
internal fun createGraph(
    clientId: String,
    clock: Clock,
    scope: CoroutineScope,
): AppGraph = LiveGraph(clock)
