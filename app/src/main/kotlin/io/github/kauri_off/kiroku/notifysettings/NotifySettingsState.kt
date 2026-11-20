package io.github.kauri_off.kiroku.notifysettings

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.Recheck
import java.time.Instant
import java.time.ZonedDateTime

data class NotifySettingsState(
    // Times on the screen are written relative to this moment, in its zone.
    val now: ZonedDateTime,
    val content: NotifySettingsContent,
    val sessionExpired: Boolean = false,
)

sealed interface NotifySettingsContent {
    data object Loading : NotifySettingsContent

    data class Shown(
        val settings: NotificationSettings,
        val device: DeviceState,
        // Null while nobody is signed in: nothing is announced and nothing is recorded (S9).
        val record: NotifyRecord?,
        // The last test notification was not shown, and the device still shows none of the app's.
        val testNotShown: Boolean = false,
    ) : NotifySettingsContent

    data class Failed(
        val error: DataError,
    ) : NotifySettingsContent
}

// What the device lets the app do, as read when the screen is shown.
data class DeviceState(
    val notifications: NotificationAccess,
    val exactAlarms: ExactAlarms,
    val battery: BatteryUse,
)

enum class NotificationAccess { Allowed, Blocked, ChannelOff }

enum class ExactAlarms {
    Allowed,
    NotAllowed,

    // Before Android 12, where exact alarms need no access and the system has no screen for it.
    NoSetting,
}

// Restricted is the user's own restriction of the app's background use, Android 9 and later.
enum class BatteryUse { Unrestricted, Restricted }

// The system screens a row of the device's states opens.
enum class DeviceSetting { Notifications, ExactAlarms, Battery }

data class NotifyRecord(
    val listSyncedAt: Instant?,
    // Soonest first; null until a run of the engine has worked it out.
    val upcoming: List<UpcomingNotification>?,
    // Newest first.
    val sent: List<SentEntry>,
)

data class UpcomingNotification(
    val id: AnimeId,
    val title: String,
    val episode: Int,
    val notifyAt: Instant,
)

data class SentEntry(
    val id: AnimeId,
    val title: String,
    val episode: Int,
    val airedAt: Instant,
    val sentAt: Instant,
    val recheck: Recheck,
    // False when the device showed none of the app's notifications at the time.
    val shown: Boolean = true,
)

sealed interface NotifySettingsEvent {
    data object Back : NotifySettingsEvent

    data object SignIn : NotifySettingsEvent

    data class SetOn(
        val on: Boolean,
    ) : NotifySettingsEvent

    data class SetAlsoPlanned(
        val on: Boolean,
    ) : NotifySettingsEvent

    data class SetDelay(
        val delay: NotificationDelay,
    ) : NotifySettingsEvent

    data class OpenSetting(
        val setting: DeviceSetting,
    ) : NotifySettingsEvent

    data object SendTest : NotifySettingsEvent

    data object Retry : NotifySettingsEvent

    data class OpenTitle(
        val id: AnimeId,
    ) : NotifySettingsEvent
}
