package io.github.kauri_off.kiroku.notifysettings

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.notify.EpisodeNotifications

// What the device lets the app do with notifications, and the system screen where the person changes each.
class DeviceStates(
    context: Context,
) {
    private val context = context.applicationContext
    private val packageName = this.context.packageName

    fun read(): DeviceState = DeviceState(notifications(), exactAlarms(), battery())

    // The system screen for [setting], started from [from] and only when the person tapped its row.
    fun open(
        from: Context,
        setting: DeviceSetting,
    ) {
        val intent = intent(setting)
        try {
            from.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            from.startActivity(details())
        }
    }

    fun intent(setting: DeviceSetting): Intent =
        when (setting) {
            DeviceSetting.Notifications ->
                if (notifications() == NotificationAccess.ChannelOff) {
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, EpisodeNotifications.CHANNEL)
                } else {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            DeviceSetting.ExactAlarms ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())
                } else {
                    details()
                }
            DeviceSetting.Battery -> details()
        }

    private fun details() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri() = Uri.fromParts(notShown("package"), packageName, null)

    private fun notifications(): NotificationAccess {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return NotificationAccess.Blocked
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(EpisodeNotifications.CHANNEL)
        return if (channel?.importance == NotificationManager.IMPORTANCE_NONE) NotificationAccess.ChannelOff else NotificationAccess.Allowed
    }

    private fun exactAlarms(): ExactAlarms =
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> ExactAlarms.NoSetting
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() -> ExactAlarms.Allowed
            else -> ExactAlarms.NotAllowed
        }

    // The standby bucket is not read: opening the app to see this screen moves it to the active bucket.
    private fun battery(): BatteryUse =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        ) {
            BatteryUse.Restricted
        } else {
            BatteryUse.Unrestricted
        }
}
