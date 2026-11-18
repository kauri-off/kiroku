package io.github.kauri_off.kiroku.notifysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.NotificationsRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZonedDateTime

// The notification settings over the engine's surface and the device's states; navigation and system screens are the route's.
@OptIn(ExperimentalCoroutinesApi::class)
class NotifySettingsViewModel(
    session: SessionRepository,
    private val notifications: NotificationsRepository,
    private val list: ListSource,
    private val clock: Clock,
    private val titleLanguage: TitleLanguage,
    background: CoroutineDispatcher,
    private val device: () -> DeviceState,
    // Each value asks for the device's states again, as they can change while the screen stays shown.
    deviceChecks: Flow<Unit>,
    private val test: suspend () -> Boolean,
    private val messages: ShellMessages,
) : ViewModel() {
    private val sessions = session.observeSession()
    private val retries = MutableStateFlow(0)
    private val devices = MutableStateFlow<DeviceState?>(null)
    private val testShown = MutableStateFlow<Boolean?>(null)

    // Every read goes to [devices], so the latest read is the one shown.
    private val deviceNow: Flow<DeviceState> =
        merge(devices.filterNotNull(), deviceChecks.transform<Unit, DeviceState> { readDevice() })

    // Null while signed out: nothing is announced and nothing is recorded (S9).
    private val record: Flow<NotifyRecord?> =
        sessions
            .map { it == Session.SignedOut }
            .distinctUntilChanged()
            .flatMapLatest { signedOut ->
                if (signedOut) {
                    flowOf(null)
                } else {
                    combine(list.observeSyncedAt(), notifications.observeComing(), notifications.observeSent()) { synced, coming, sent ->
                        NotifyRecord(synced, coming?.map { it.upcoming() }, sent.map { it.entry() })
                    }
                }
            }

    private val content: Flow<NotifySettingsContent> =
        combine(notifications.observeSettings(), deviceNow, record, testShown) { settings, device, record, testShown ->
            val testNotShown = testShown == false && device.notifications != NotificationAccess.Allowed
            NotifySettingsContent.Shown(settings, device, record, testNotShown) as NotifySettingsContent
        }.readAgainOnRetry(retries) { NotifySettingsContent.Failed(it) }

    val state: StateFlow<NotifySettingsState> =
        combine(content, sessions) { content, session ->
            NotifySettingsState(ZonedDateTime.now(clock), content, sessionExpired = session is Session.Expired)
        }.flowOn(background)
            .stateIn(viewModelScope, WhileShown, NotifySettingsState(ZonedDateTime.now(clock), NotifySettingsContent.Loading))

    // Reads what the device allows, as the screen is shown and each time the person comes back to it.
    fun readDevice() {
        devices.value = device()
    }

    fun onEvent(event: NotifySettingsEvent) {
        when (event) {
            is NotifySettingsEvent.SetOn -> change { it.copy(on = event.on) }
            is NotifySettingsEvent.SetAlsoPlanned -> change { it.copy(alsoPlanned = event.on) }
            is NotifySettingsEvent.SetDelay -> change { it.copy(delay = event.delay) }
            NotifySettingsEvent.SendTest -> viewModelScope.launch { testShown.value = test() }
            NotifySettingsEvent.Retry -> retries.update { it + 1 }
            else -> Unit
        }
    }

    // Stored at once and followed by a run of the engine (N5, N8); the switch moves when the stored settings say so.
    private fun change(change: (NotificationSettings) -> NotificationSettings) {
        viewModelScope.launch {
            if (notifications.changeSettings(change) is Outcome.Failure) messages.post(ShellMessage(ShellMessage.Text.CouldNotSave))
        }
    }

    private fun Coming.upcoming() = UpcomingNotification(anime.id, anime.titles.preferred(titleLanguage), episode, notifyAt)

    private fun SentNotification.entry() =
        SentEntry(anime.id, anime.titles.preferred(titleLanguage), episode, airedAt, postedAt, recheck, shown)
}
