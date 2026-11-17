package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// What the store remembers of the list of one sign-in.
data class StoredNotifications(
    // Whose list the record is of: none before the first sign-in and after a sign-out.
    val owner: SignInId?,
    val record: NotificationRecord,
)

// A store of what notifications remember and of the person's choices for them. Each call is atomic, and only the engine writes.
interface NotificationStore {
    // The settings as they are for a new observer, then after every change; the defaults while none is stored. Equal values may repeat.
    fun observeSettings(): Flow<NotificationSettings>

    suspend fun writeSettings(settings: NotificationSettings)

    suspend fun read(): StoredNotifications

    // What was posted and is not forgotten, in the order written, after every change. A title is given as the device knows it.
    fun observeSent(): Flow<List<SentNotification>>

    // N1: stores the record, adds [sent] and forgets what was posted before [keptFrom], all or none.
    suspend fun write(
        record: NotificationRecord,
        sent: List<SentNotification>,
        keptFrom: Instant,
    )

    // S9: forgets the record and all that was posted; what is stored from now on is [owner]'s. The settings stay.
    suspend fun clear(owner: SignInId?)
}

// The one alarm (section 5.1): when its time comes it starts a run, in a process started for it too.
interface NotificationAlarm {
    // Leaves the alarm at [at] in place of the one before, or none.
    suspend fun set(at: Instant?)
}

// What a notification says beside its title.
data class Shown(
    val episode: Int,
    val toWatch: Int?,
    val marks: Int,
)

val Announcement.shown: Shown get() = Shown(episode, toWatch, marks)

// The episode notifications the device shows, one a title, each under a tag made from its title's id.
interface ShownNotifications {
    // Each title whose notification is shown now; one the person dismissed is not among them.
    suspend fun shown(): Map<AnimeId, Shown>

    // Shows the title's notification in place of the one before. It alerts only when [alert], however the one before read.
    suspend fun post(
        announcement: Announcement,
        alert: Boolean,
    )

    // Removes the notifications of [titles]; a title that has none is left alone.
    suspend fun withdraw(titles: Set<AnimeId>)
}
