package io.github.kauri_off.kiroku.notifysettings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.design.component.ConnectedChoice
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.text.AirTimeText
import io.github.kauri_off.kiroku.title.rememberAirTimeText

// The notification settings, drawn from [state] alone: the settings, what the device allows, what comes next and
// what was sent. Its snackbars go to [snackbars], the host the caller draws.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifySettingsScreen(
    state: NotifySettingsState,
    onEvent: (NotifySettingsEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.notify_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { onEvent(NotifySettingsEvent.Back) }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (state.sessionExpired) SessionBanner(onSignIn = { onEvent(NotifySettingsEvent.SignIn) })
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        when (val content = state.content) {
            NotifySettingsContent.Loading -> ScreenState(ScreenState.Loading, Modifier.padding(padding))
            is NotifySettingsContent.Shown -> Shown(content, rememberAirTimeText(state.now), onEvent, padding)
        }
    }
}

@Composable
private fun Shown(
    content: NotifySettingsContent.Shown,
    times: AirTimeText,
    onEvent: (NotifySettingsEvent) -> Unit,
    padding: PaddingValues,
) {
    val direction = LocalLayoutDirection.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.s),
        ) {
            item(key = Section.Settings) { Settings(content.settings, onEvent) }
            item(key = Section.Device) { Device(content.device, onEvent) }
            val record = content.record
            if (record == null) {
                item(key = Section.SignedOut) { SignedOut(onEvent) }
            } else {
                upcoming(record, content.settings.on, times, onEvent)
                sent(record.sent, times, onEvent)
            }
        }
    }
}

@Composable
private fun Settings(
    settings: NotificationSettings,
    onEvent: (NotifySettingsEvent) -> Unit,
) {
    Column(Modifier.padding(top = Spacing.s)) {
        SwitchRow(
            label = stringResource(R.string.notify_settings_on),
            detail = stringResource(R.string.notify_settings_on_detail),
            checked = settings.on,
            enabled = true,
        ) { onEvent(NotifySettingsEvent.SetOn(it)) }
        SwitchRow(
            label = stringResource(R.string.notify_settings_also_planned),
            detail = null,
            checked = settings.alsoPlanned,
            enabled = settings.on,
        ) { onEvent(NotifySettingsEvent.SetAlsoPlanned(it)) }
        ListItem(
            supportingContent = { Text(stringResource(R.string.notify_settings_delay_detail)) },
            content = { Text(stringResource(R.string.notify_settings_delay)) },
        )
        val delays = NotificationDelay.entries
        ConnectedChoice(
            labels = delays.map { stringResource(it.label()) },
            chosen = delays.indexOf(settings.delay),
            onChoose = { onEvent(NotifySettingsEvent.SetDelay(delays[it])) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
            enabled = settings.on,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) = ListItem(
    modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    supportingContent = detail?.let { { Text(it) } },
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    content = { Text(label) },
)

@Composable
private fun Device(
    device: DeviceState,
    onEvent: (NotifySettingsEvent) -> Unit,
) {
    Column(Modifier.padding(top = Spacing.l)) {
        SectionHeader(stringResource(R.string.notify_settings_device))
        DeviceRow(
            label = stringResource(R.string.notify_settings_notifications),
            value =
                if (device.notifications == NotificationAccess.ChannelOff) {
                    stringResource(R.string.notify_settings_notifications_channel_off, stringResource(R.string.notify_new_episodes))
                } else {
                    stringResource(device.notifications.text())
                },
            health = device.notifications.health(),
            setting = DeviceSetting.Notifications,
            onEvent = onEvent,
        )
        DeviceRow(
            label = stringResource(R.string.notify_settings_exact_alarms),
            value = stringResource(device.exactAlarms.text()),
            health = device.exactAlarms.health(),
            setting = DeviceSetting.ExactAlarms.takeIf { device.exactAlarms != ExactAlarms.NoSetting },
            onEvent = onEvent,
        )
        DeviceRow(
            label = stringResource(R.string.notify_settings_battery),
            value = stringResource(device.battery.text()),
            health = device.battery.health(),
            setting = DeviceSetting.Battery,
            onEvent = onEvent,
        )
        FilledTonalButton(
            onClick = { onEvent(NotifySettingsEvent.SendTest) },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
        ) {
            Text(stringResource(R.string.notify_settings_send_test))
        }
        Note(stringResource(R.string.notify_settings_limits), Modifier.padding(top = Spacing.s))
    }
}

// A state of the device with its mark; a row that has a system screen opens it.
@Composable
private fun DeviceRow(
    label: String,
    value: String,
    health: Health,
    setting: DeviceSetting?,
    onEvent: (NotifySettingsEvent) -> Unit,
) {
    val leading = @Composable { Icon(painterResource(health.icon), contentDescription = null, tint = health.tint()) }
    val supporting = @Composable { Text(value) }
    if (setting == null) {
        ListItem(leadingContent = leading, supportingContent = supporting, content = { Text(label) })
    } else {
        val spoken = listOf(label, value, stringResource(R.string.notify_settings_open_setting)).joinToString(separator())
        ListItem(
            onClick = { onEvent(NotifySettingsEvent.OpenSetting(setting)) },
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
            // Labelled on the row itself, so a row cut off at the screen's edge is labelled too.
            modifier = Modifier.semantics { contentDescription = spoken },
            content = { Text(label) },
        )
    }
}

private fun LazyListScope.upcoming(
    record: NotifyRecord,
    on: Boolean,
    times: AirTimeText,
    onEvent: (NotifySettingsEvent) -> Unit,
) {
    item(key = Section.Upcoming) {
        Column(Modifier.padding(top = Spacing.l)) {
            SectionHeader(stringResource(R.string.notify_settings_upcoming))
            Note(
                record.listSyncedAt?.let { stringResource(R.string.notify_settings_list_synced, times.pastTime(it)) }
                    ?: stringResource(R.string.notify_settings_list_not_synced),
            )
            when {
                !on -> Message(R.string.notify_settings_off)
                record.upcoming.isEmpty() -> Message(R.string.notify_settings_upcoming_none)
            }
        }
    }
    if (on) {
        items(record.upcoming) { coming ->
            TitleRow(coming.title, listOf(times.nextEpisode(coming.episode, coming.notifyAt))) {
                onEvent(NotifySettingsEvent.OpenTitle(coming.id))
            }
        }
    }
}

private fun LazyListScope.sent(
    sent: List<SentNotification>,
    times: AirTimeText,
    onEvent: (NotifySettingsEvent) -> Unit,
) {
    item(key = Section.Sent) {
        Column(Modifier.padding(top = Spacing.l)) {
            SectionHeader(stringResource(R.string.notify_settings_sent))
            if (sent.isEmpty()) Message(R.string.notify_settings_sent_none)
        }
    }
    items(sent) { one ->
        val sentAt = times.pastTime(one.sentAt)
        val lines =
            listOf(
                stringResource(R.string.notify_settings_sent_episode, one.episode, times.pastTime(one.airedAt)),
                one.recheck.text()?.let { stringResource(R.string.notify_settings_sent_at_checked, sentAt, stringResource(it)) }
                    ?: stringResource(R.string.notify_settings_sent_at, sentAt),
            )
        TitleRow(one.title, lines) { onEvent(NotifySettingsEvent.OpenTitle(one.id)) }
    }
}

// A title and its lines; a tap opens the title.
@Composable
private fun TitleRow(
    title: String,
    lines: List<String>,
    onClick: () -> Unit,
) {
    val spoken = (listOf(title) + lines).joinToString(separator())
    ListItem(
        onClick = onClick,
        supportingContent = { Column { lines.forEach { Text(it) } } },
        // Labelled on the row itself, so a row cut off at the screen's edge is labelled too.
        modifier = Modifier.semantics { contentDescription = spoken },
        content = { TitleText(title, maxLines = TITLE_LINES) },
    )
}

@Composable
private fun separator(): String = stringResource(R.string.common_list_separator)

@Composable
private fun SignedOut(onEvent: (NotifySettingsEvent) -> Unit) {
    Column(Modifier.padding(top = Spacing.l)) {
        Message(R.string.notify_settings_signed_out)
        Button(
            onClick = { onEvent(NotifySettingsEvent.SignIn) },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(horizontal = Spacing.l),
        ) {
            Text(stringResource(R.string.notify_settings_sign_in))
        }
    }
}

@Composable
private fun Message(
    @StringRes text: Int,
) = Text(
    stringResource(text),
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
)

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
)

private enum class Health(
    @param:DrawableRes val icon: Int,
) {
    Fine(R.drawable.ic_check_circle),
    Late(R.drawable.ic_warning),
    Broken(R.drawable.ic_error),
}

@Composable
private fun Health.tint(): Color =
    when (this) {
        Health.Fine -> MaterialTheme.colorScheme.primary
        Health.Late -> MaterialTheme.colorScheme.tertiary
        Health.Broken -> MaterialTheme.colorScheme.error
    }

private fun NotificationAccess.health(): Health = if (this == NotificationAccess.Allowed) Health.Fine else Health.Broken

private fun NotificationAccess.text(): Int =
    if (this ==
        NotificationAccess.Allowed
    ) {
        R.string.notify_settings_notifications_allowed
    } else {
        R.string.notify_settings_notifications_blocked
    }

private fun ExactAlarms.health(): Health = if (this == ExactAlarms.NotAllowed) Health.Late else Health.Fine

private fun ExactAlarms.text(): Int =
    if (this == ExactAlarms.NotAllowed) R.string.notify_settings_exact_alarms_not_allowed else R.string.notify_settings_exact_alarms_allowed

private fun BatteryUse.health(): Health =
    when (this) {
        BatteryUse.Unrestricted -> Health.Fine
        BatteryUse.RarelyUsed -> Health.Late
        BatteryUse.Restricted -> Health.Broken
    }

private fun BatteryUse.text(): Int =
    when (this) {
        BatteryUse.Unrestricted -> R.string.notify_settings_battery_unrestricted
        BatteryUse.RarelyUsed -> R.string.notify_settings_battery_rarely_used
        BatteryUse.Restricted -> R.string.notify_settings_battery_restricted
    }

private fun NotificationDelay.label(): Int =
    when (this) {
        NotificationDelay.None -> R.string.notify_settings_delay_none
        NotificationDelay.HalfHour -> R.string.notify_settings_delay_half_hour
        NotificationDelay.Hour -> R.string.notify_settings_delay_hour
        NotificationDelay.ThreeHours -> R.string.notify_settings_delay_three_hours
    }

private fun Recheck.text(): Int? =
    when (this) {
        Recheck.Answered -> R.string.notify_settings_recheck_confirmed
        Recheck.Failed -> R.string.notify_settings_recheck_failed
        Recheck.Late -> R.string.notify_settings_recheck_too_slow
        Recheck.Offline -> R.string.notify_settings_recheck_offline
        Recheck.NotAsked -> null
    }

private const val TITLE_LINES = 2

private enum class Section { Settings, Device, SignedOut, Upcoming, Sent }
