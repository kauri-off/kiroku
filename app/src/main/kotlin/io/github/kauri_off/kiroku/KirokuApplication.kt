package io.github.kauri_off.kiroku

import android.app.Application
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KirokuApplication :
    Application(),
    AppGraphOwner {
    override val graph: AppGraph by lazy {
        createGraph(BuildConfig.MAL_CLIENT_ID, DeviceClock, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
