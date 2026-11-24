package io.github.kauri_off.kiroku

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.link.LinksOwner
import io.github.kauri_off.kiroku.notify.HeldRuns
import io.github.kauri_off.kiroku.notify.HeldRunsOwner
import io.github.kauri_off.kiroku.notify.PeriodicWork
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import io.github.kauri_off.kiroku.wiring.WithoutScreenOwner
import io.github.kauri_off.kiroku.wiring.applicationScope
import io.github.kauri_off.kiroku.wiring.createGraph
import io.github.kauri_off.kiroku.wiring.debugTools
import io.github.kauri_off.kiroku.wiring.forgetLegacyImages
import io.github.kauri_off.kiroku.wiring.reportUnexpected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class KirokuApplication :
    Application(),
    AppGraphOwner,
    LinksOwner,
    HeldRunsOwner,
    WithoutScreenOwner,
    Configuration.Provider,
    SingletonImageLoader.Factory {
    private val scope = applicationScope(reportUnexpected)

    override val graph: AppGraph by lazy { createGraph(this, BuildConfig.MAL_CLIENT_ID, DeviceClock, scope) }

    // A JVM runs one test's application after another; each ends its work, and waits for it, before the next starts.
    @VisibleForTesting
    internal suspend fun endWork() = scope.coroutineContext.job.cancelAndJoin()

    override val links = AppLinks()

    override val withoutScreen: WithoutScreen get() = graph.withoutScreen

    override val heldRuns = HeldRuns(scope, { PeriodicWork.schedule(this) }) { withoutScreen.notificationRuns }

    // Scheduled work is set up when it is first asked for, never when the process starts.
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) { forgetLegacyImages() }
        debugTools?.invoke(this)
        heldRuns.atEachOpening(ProcessLifecycleOwner.get().lifecycle)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.newImageLoader(context)
}
