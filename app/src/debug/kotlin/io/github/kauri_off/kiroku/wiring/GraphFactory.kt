package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.demo.Demo
import kotlinx.coroutines.CoroutineScope
import java.time.Clock

internal fun createGraph(
    clientId: String,
    clock: Clock,
    scope: CoroutineScope,
): AppGraph = if (clientId.isEmpty()) DemoGraph(Demo.startingAt(clock, scope)) else LiveGraph(clock)

class DemoGraph(
    val demo: Demo,
) : AppGraph {
    override val clock = demo.clock
    override val session = demo.session
    override val list = demo.list
    override val anime = demo.anime
    override val airing = demo.airing
    override val search = demo.search
    override val user = demo.user
}
