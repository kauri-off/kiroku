package io.github.kauri_off.kiroku.wiring

import android.os.Handler
import android.os.Looper
import android.util.Log
import coil3.intercept.Interceptor
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.scenario.Scenarios
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import io.github.kauri_off.kiroku.standin.StandInPosters
import kotlinx.coroutines.CoroutineScope

private val UNEXPECTED = notShown("An exception the app did not expect")

// A debug build crashes on an exception nobody expected, on the main thread, so it is never missed.
internal val reportUnexpected: (Throwable) -> Unit = { e ->
    Handler(Looper.getMainLooper()).post { throw IllegalStateException(UNEXPECTED, e) }
}

// One line per request: no header, value or body.
internal val requestLog: ((String) -> Unit)? = { Log.d(LOG_TAG, it) }

// The demo holds no HTTP client and no file: it reaches no host and keeps nothing.
internal val otherSource: OtherSource? = { context, clientId, clock, scope ->
    when (SourceChoice(context).startsOn(clientId)) {
        DataSource.Account -> null
        DataSource.Demo -> DemoGraph(Demo.startingAt(clock, scope, reportUnexpected), scope)
    }
}

internal val standInPosters: Interceptor? = StandInPosters

class DemoGraph(
    val demo: Demo,
    scope: CoroutineScope,
) : AppGraph {
    val scenarios = Scenarios(demo, scope)

    override val clock = demo.clock
    override val session = demo.session
    override val list = demo.list
    override val anime = demo.anime
    override val airing = demo.airing
    override val search = demo.search
    override val user = demo.user
}
