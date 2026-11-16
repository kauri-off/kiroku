package io.github.kauri_off.kiroku

import android.app.Application
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

class KirokuApplication :
    Application(),
    AppGraphOwner,
    LinksOwner,
    SingletonImageLoader.Factory {
    override val graph: AppGraph by lazy {
        createGraph(this, BuildConfig.MAL_CLIENT_ID, DeviceClock, applicationScope(reportUnexpected))
    }

    override val links = AppLinks()

    override fun onCreate() {
        super.onCreate()
        debugTools?.invoke(this)
    }

    // The debug build draws its fixture titles' posters; the release build has no stand-ins.
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader(context, graph.images, standInPosters)
}
