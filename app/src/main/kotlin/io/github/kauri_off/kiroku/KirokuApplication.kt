package io.github.kauri_off.kiroku

import android.app.Application
import androidx.annotation.VisibleForTesting
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.link.LinksOwner
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.applicationScope
import io.github.kauri_off.kiroku.wiring.createGraph
import io.github.kauri_off.kiroku.wiring.debugTools
import io.github.kauri_off.kiroku.wiring.imageLoader
import io.github.kauri_off.kiroku.wiring.reportUnexpected
import io.github.kauri_off.kiroku.wiring.standInPosters
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

class KirokuApplication :
    Application(),
    AppGraphOwner,
    LinksOwner,
    SingletonImageLoader.Factory {
    private val scope = applicationScope(reportUnexpected)

    override val graph: AppGraph by lazy { createGraph(this, BuildConfig.MAL_CLIENT_ID, DeviceClock, scope) }

    // A JVM runs one test's application after another; each ends its work, and waits for it, before the next starts.
    @VisibleForTesting
    internal suspend fun endWork() = scope.coroutineContext.job.cancelAndJoin()

    override val links = AppLinks()

    override fun onCreate() {
        super.onCreate()
        debugTools?.invoke(this)
    }

    // The debug build draws its fixture titles' posters; the release build has no stand-ins.
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader(context, graph.images, standInPosters)
}
