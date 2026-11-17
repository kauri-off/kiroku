package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// A store that lives as long as its process.
class InMemoryNotificationStore(
    settings: NotificationSettings = NotificationSettings(),
) : NotificationStore {
    private data class Held(
        val stored: StoredNotifications = StoredNotifications(null, NotificationRecord()),
        val sent: List<SentNotification> = emptyList(),
    )

    private val settings = MutableStateFlow(settings)
    private val held = MutableStateFlow(Held())

    override fun observeSettings(): Flow<NotificationSettings> = settings

    override suspend fun writeSettings(settings: NotificationSettings) {
        this.settings.value = settings
    }

    override suspend fun read(): StoredNotifications = held.value.stored

    override fun observeSent(): Flow<List<SentNotification>> = held.map { it.sent }.distinctUntilChanged()

    override suspend fun write(
        record: NotificationRecord,
        sent: List<SentNotification>,
        keptFrom: Instant,
    ) {
        held.update { Held(it.stored.copy(record = record), (it.sent + sent).filter { one -> !one.postedAt.isBefore(keptFrom) }) }
    }

    override suspend fun clear(owner: SignInId?) {
        held.value = Held(StoredNotifications(owner, NotificationRecord()))
    }
}

// An alarm that is only remembered: what starts a run when its time comes is its user's.
class InMemoryNotificationAlarm : NotificationAlarm {
    private val set = MutableStateFlow<Instant?>(null)

    val at: StateFlow<Instant?> = set.asStateFlow()

    // Every time it was set to, in order.
    val sets = CopyOnWriteArrayList<Instant?>()

    override suspend fun set(at: Instant?) {
        sets += listOf(at)
        set.value = at
    }

    // It fired or the device lost it: none is left.
    fun gone() {
        set.value = null
    }
}

// The notifications as a device shows them, with every post and withdrawal it was asked for.
class InMemoryShownNotifications : ShownNotifications {
    data class Post(
        val announcement: Announcement,
        val alert: Boolean,
    )

    private val showing = MutableStateFlow(emptyMap<AnimeId, Announcement>())

    val notifications: StateFlow<Map<AnimeId, Announcement>> = showing.asStateFlow()

    val posts = CopyOnWriteArrayList<Post>()
    val withdrawals = CopyOnWriteArrayList<Set<AnimeId>>()

    // The device shows none of the app's notifications, as with the permission denied: a post is taken and nothing appears.
    @Volatile
    var blocked = false

    override suspend fun shown(): Map<AnimeId, Shown> = showing.value.mapValues { it.value.shown }

    override suspend fun post(
        announcement: Announcement,
        alert: Boolean,
    ): Boolean {
        posts += Post(announcement, alert)
        if (blocked) return false
        showing.update { it + (announcement.anime.id to announcement) }
        return true
    }

    override suspend fun withdraw(titles: Set<AnimeId>) {
        withdrawals += titles
        showing.update { it - titles }
    }

    // The person swipes the title's notification away.
    fun dismiss(id: AnimeId) {
        showing.update { it - id }
    }
}
