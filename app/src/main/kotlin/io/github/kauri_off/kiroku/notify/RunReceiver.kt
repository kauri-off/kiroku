package io.github.kauri_off.kiroku.notify

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.notShown

// The alarm and the moments the system starts the app at (N2, N8): each starts one run and holds the process until it has ended.
class RunReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val trigger = triggers[intent.action] ?: return
        val runs = context.heldRuns ?: return
        val pending = goAsync()
        val foreground = intent.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0
        runs.start(trigger, if (foreground) HELD_FOR_FOREGROUND else HELD_FOR, pending::finish)
    }

    companion object {
        val ALARM = notShown("io.github.kauri_off.kiroku.notify.ALARM")

        // A change of the clock or the time zone is not among them: the alarm is at an instant, which neither moves.
        private val triggers =
            mapOf(
                ALARM to RunTrigger.Alarm,
                Intent.ACTION_BOOT_COMPLETED to RunTrigger.DeviceRestarted,
                Intent.ACTION_MY_PACKAGE_REPLACED to RunTrigger.AppUpdated,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED to RunTrigger.AlarmAccessChanged,
            )
    }
}
