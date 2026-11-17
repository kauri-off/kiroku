package io.github.kauri_off.kiroku.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Where "Mark watched" from a notification arrives; the write it starts is not built yet.
class MarkWatchedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = Unit
}
