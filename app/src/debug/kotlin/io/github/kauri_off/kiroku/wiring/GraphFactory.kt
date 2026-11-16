package io.github.kauri_off.kiroku.wiring

import android.os.Handler
import android.os.Looper
import android.util.Log
import coil3.intercept.Interceptor
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.data.network.OddAnswer
import io.github.kauri_off.kiroku.data.preferences.PreferencesFailure
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.session.TokensNotRead
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.reports.ReportLog
import io.github.kauri_off.kiroku.reports.reportLog
import io.github.kauri_off.kiroku.sandbox.SandboxGraph
import io.github.kauri_off.kiroku.scenario.Scenarios
import io.github.kauri_off.kiroku.scenario.ScenariosOwner
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import io.github.kauri_off.kiroku.standin.StandInPosters
import kotlinx.coroutines.CoroutineScope

private val UNEXPECTED = notShown("An exception the app did not expect")

// The debuggable build is the loud one; the preview build reports as a release does, and keeps the report as well.
internal val reportUnexpected: (Throwable) -> Unit = reporter(loud = BuildConfig.DEBUG, reportLog)

// Keeps each report for the debug menu; a loud build then crashes on the main thread on one nobody expected, once it is kept.
internal fun reporter(
    loud: Boolean,
    log: ReportLog,
): (Throwable) -> Unit =
    { e ->
        // S8: tokens or choices that cannot be read, and MAL's odd answers (section 2.3), never crash the app: they are logged.
        if (!loud || e is TokensNotRead || e is PreferencesFailure || e is OddAnswer) {
            log.keep(e)
            logTypes(e)
        } else {
            log.keep(e) { Handler(Looper.getMainLooper()).post { throw IllegalStateException(UNEXPECTED, e) } }
        }
    }

// One line per request: no header, value or body.
internal val requestLog: ((String) -> Unit)? = { Log.d(LOG_TAG, it) }

// The demo holds no HTTP client and no file: it reaches no host and keeps nothing. The sandbox answers on the device.
internal val otherSource: OtherSource? = { context, clientId, clock, scope ->
    when (SourceChoice(context).startsOn(clientId)) {
        DataSource.Account -> null
        DataSource.Demo -> DemoGraph(Demo.startingAt(clock, scope, reportUnexpected, deviceClockSet(context)), scope)
        DataSource.Sandbox ->
            SandboxGraph.create(context, clock) { source, device ->
                LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, reportUnexpected, requestLog, device)
            }
    }
}

internal val standInPosters: Interceptor? = StandInPosters

class DemoGraph(
    val demo: Demo,
    scope: CoroutineScope,
) : AppGraph,
    ScenariosOwner {
    override val scenarios = Scenarios(demo, scope)

    override val clock = demo.clock
    override val session = demo.session
    override val list = demo.list
    override val anime = demo.anime
    override val airing = demo.airing
    override val search = demo.search
    override val user = demo.user
    override val connection = demo.connection
    override val preferences = demo.preferences
    override val madeUp = true
}
