package io.github.kauri_off.kiroku.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.await
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.logTypes
import io.github.kauri_off.kiroku.wiring.reportUnexpected
import io.github.kauri_off.kiroku.wiring.withoutScreen
import kotlinx.coroutines.CancellationException

// Where "Mark watched" from a notification arrives: the tap is handed to scheduled work, and the broadcast held until it is
// kept; one that cannot be kept says so at once.
class MarkWatchedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val mark = intent.markWatched() ?: return
        val runs = context.heldRuns ?: return
        val app = context.applicationContext
        val pending = goAsync()
        runs.held(HELD_FOR_FOREGROUND, pending::finish) {
            try {
                MarkWatchedWork.tapped(app, Tap(mark, DeviceClock.instant())).forEach { it.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Scheduled work's own store failed, which is the device's state and not the app's fault: it is logged.
                logTypes(e)
                app.withoutScreen?.let { MarkWatchedTries(it, DeviceClock, reportUnexpected).gaveUp(mark) }
            }
        }
    }
}
