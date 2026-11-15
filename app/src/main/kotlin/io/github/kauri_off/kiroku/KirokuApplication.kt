package io.github.kauri_off.kiroku

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.kauri_off.kiroku.data.preferences.StoredNotice
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.link.DeviceLinkNotice
import io.github.kauri_off.kiroku.link.LinksOwner
import io.github.kauri_off.kiroku.link.linksNeedConsent
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.createGraph
import io.github.kauri_off.kiroku.wiring.debugTools
import io.github.kauri_off.kiroku.wiring.imageLoader
import io.github.kauri_off.kiroku.wiring.reportUnexpected
import io.github.kauri_off.kiroku.wiring.standInPosters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class KirokuApplication :
    Application(),
    AppGraphOwner,
    LinksOwner,
    SingletonImageLoader.Factory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val graph: AppGraph by lazy { createGraph(this, BuildConfig.MAL_CLIENT_ID, DeviceClock, scope) }

    // Of the installed app, not of a source: the system allows the links for the app whichever source it shows.
    override val links: AppLinks by lazy {
        val notice = StoredNotice(File(noBackupFilesDir, notShown("links.preferences_pb")), scope, reportUnexpected)
        AppLinks(DeviceLinkNotice(notice) { linksNeedConsent(this) })
    }

    override fun onCreate() {
        super.onCreate()
        debugTools?.invoke(this)
    }

    // The debug build draws its fixture titles' posters; the release build has no stand-ins.
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader(context, graph.images, standInPosters)
}
