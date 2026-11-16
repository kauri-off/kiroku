package io.github.kauri_off.kiroku.notifysettings

import io.github.kauri_off.kiroku.core.model.AnimeId
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
        val settings: NotifySettings,
        val device: DeviceState,
        // Null while nobody is signed in: nothing is announced and nothing is recorded (S9).
        val record: NotifyRecord?,
    ) : NotifySettingsContent
}

data class NotifySettings(
    val on: Boolean,
    val alsoPlanned: Boolean,
    val delay: NotifyDelay,
)

enum class NotifyDelay(
    val minutes: Int,
) {
    None(0),
    HalfHour(30),
    Hour(60),
    ThreeHours(180),
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

enum class BatteryUse {
    Unrestricted,

    // The system's standby bucket for an app the user rarely opens.
    RarelyUsed,
    Restricted,
}

// The system screens a row of the device's states opens.
enum class DeviceSetting { Notifications, ExactAlarms, Battery }

data class NotifyRecord(
    val listSyncedAt: Instant?,
    val schedulesUpdatedAt: Instant?,
    // Soonest first.
    val upcoming: List<UpcomingNotification>,
    // Newest first.
    val sent: List<SentNotification>,
)

data class UpcomingNotification(
    val id: AnimeId,
    val title: String,
    val episode: Int,
    val notifyAt: Instant,
)

data class SentNotification(
    val id: AnimeId,
    val title: String,
    val episode: Int,
    val airedAt: Instant,
    val sentAt: Instant,
    val recheck: Recheck,
)

// How the check of the air time with AniList before a post ended (N3).
enum class Recheck {
    Confirmed,
    Failed,
    TooSlow,
    Offline,

    // The post came from a run the alarm did not start, which checks nothing.
    NotRun,
}

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
        val delay: NotifyDelay,
    ) : NotifySettingsEvent

    data class OpenSetting(
        val setting: DeviceSetting,
    ) : NotifySettingsEvent

    data object SendTest : NotifySettingsEvent

    data class OpenTitle(
        val id: AnimeId,
    ) : NotifySettingsEvent
}
