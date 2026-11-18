package io.github.kauri_off.kiroku.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import io.github.kauri_off.kiroku.core.notify.NotificationAlarm
import java.time.Instant

// Section 5.1's one alarm on the system's alarm service: it may fire while the device idles, and is exact only where the system allows.
class SystemAlarm(
    context: Context,
) : NotificationAlarm {
    private val context = context.applicationContext
    private val alarms = checkNotNull(context.getSystemService(AlarmManager::class.java))

    // The system keeps one alarm for equal intents, so each set takes the place of the one before.
    private val fires: PendingIntent
        get() =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RunReceiver::class.java).setAction(RunReceiver.ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

    override suspend fun set(at: Instant?) {
        if (at == null) return alarms.cancel(fires)
        val time = at.toEpochMilli()
        if (AlarmManagerCompat.canScheduleExactAlarms(alarms)) {
            try {
                return alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, fires)
            } catch (_: SecurityException) {
                // The access was taken away between the question and the call.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, fires)
    }
}
