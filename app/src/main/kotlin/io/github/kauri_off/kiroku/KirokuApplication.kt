package io.github.kauri_off.kiroku

import android.app.Application
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

class KirokuApplication : Application() {
    val graph: AppGraph by lazy {
        createGraph(BuildConfig.MAL_CLIENT_ID, Clock.systemDefaultZone(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
