package io.github.kauri_off.kiroku.notifysettings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsFixtureStates.allowed
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsFixtureStates.record
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsFixtureStates.settings
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsFixtureStates.state
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown
import io.github.kauri_off.kiroku.screenshots.shown

class NotifySettingsScreenshots : ScreenshotRegistry() {
    private val fixtures = NotifySettingsFixtureStates

    override val content = contentCase { Screen(state()) }
    override val loading = shown { Screen(fixtures.loading()) }

    // Signed in and synced, with nothing coming and nothing sent yet.
    override val empty = shown { Screen(state(record = record.copy(upcoming = emptyList(), sent = emptyList()))) }
    override val error =
        notShown("Everything it shows is read from the device's own storage and the system, which give no error to retry")

    override val cases =
        listOf(
            case("notifications-blocked") { Screen(state(device = allowed.copy(notifications = NotificationAccess.Blocked))) },
            case("channel-off") { Screen(state(device = allowed.copy(notifications = NotificationAccess.ChannelOff))) },
            case("exact-alarms-not-allowed") { Screen(state(device = allowed.copy(exactAlarms = ExactAlarms.NotAllowed))) },
            case("exact-alarms-no-setting") { Screen(state(device = allowed.copy(exactAlarms = ExactAlarms.NoSetting))) },
            case("battery-rarely-used") { Screen(state(device = allowed.copy(battery = BatteryUse.RarelyUsed))) },
            case("battery-restricted") { Screen(state(device = allowed.copy(battery = BatteryUse.Restricted))) },
            case("device-all-wrong") {
                Screen(state(device = DeviceState(NotificationAccess.Blocked, ExactAlarms.NotAllowed, BatteryUse.Restricted)))
            },
            case("off") { Screen(state(settings = settings.copy(on = false))) },
            case("planned-and-delay") {
                Screen(
                    state(
                        settings = settings.copy(alsoPlanned = true, delay = NotifyDelay.Hour),
                        record = fixtures.delayed(NotifyDelay.Hour),
                    ),
                )
            },
            case("nothing-sent") { Screen(state(record = record.copy(sent = emptyList()))) },
            case("never-synced") {
                Screen(
                    state(record = record.copy(listSyncedAt = null, schedulesUpdatedAt = null, upcoming = emptyList(), sent = emptyList())),
                )
            },
            case("long-record") { Screen(state(record = record.copy(sent = fixtures.longRecord))) },
            case("signed-out") { Screen(state(record = null)) },
            case("session-expired") { Screen(state().copy(sessionExpired = true)) },
        )
}

@Composable
private fun Screen(state: NotifySettingsState) = NotifySettingsScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })
