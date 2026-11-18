package io.github.kauri_off.kiroku.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kauri_off.kiroku.wiring.DeviceClock
import java.util.concurrent.atomic.AtomicInteger

// Where "Mark watched" from a notification arrives: the tap is handed to scheduled work, and the broadcast held until it is kept.
class MarkWatchedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val mark = intent.markWatched() ?: return
        val pending = goAsync()
        val kept = MarkWatchedWork.tapped(context.applicationContext, Tap(mark, DeviceClock.instant()))
        val left = AtomicInteger(kept.size)
        kept.forEach { it.result.addListener({ if (left.decrementAndGet() == 0) pending.finish() }, Runnable::run) }
    }
}
