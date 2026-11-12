package io.github.kauri_off.kiroku

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.createGraph
import io.github.kauri_off.kiroku.wiring.debugTools
import io.github.kauri_off.kiroku.wiring.standInPosters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KirokuApplication :
    Application(),
    AppGraphOwner,
    SingletonImageLoader.Factory {
    override val graph: AppGraph by lazy {
        createGraph(BuildConfig.MAL_CLIENT_ID, DeviceClock, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    override fun onCreate() {
        super.onCreate()
        debugTools?.invoke(this)
    }

    // The debug build draws its fixture titles' posters; the release build has no stand-ins.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { standInPosters?.let { add(it) } }.build()
}
