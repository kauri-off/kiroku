package io.github.kauri_off.kiroku.wiring

import coil3.intercept.Interceptor
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.standin.StandInPosters

internal val demoGraphFactory: DemoGraphFactory? = { clock, scope -> DemoGraph(Demo.startingAt(clock, scope)) }

internal val standInPosters: Interceptor? = StandInPosters

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
