package io.github.kauri_off.kiroku.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.notify.ShownNotifications
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

// Episode notifications on the system's own: one a title, grouped under a summary, worded in the language at each post.
class EpisodeNotifications(
    context: Context,
    private val language: () -> TitleLanguage,
    private val gap: Duration = ENQUEUE_GAP,
) : ShownNotifications {
    private val context = context.applicationContext
    private val system = this.context.getSystemService(NotificationManager::class.java)
    private val lock = Mutex()
    private var enqueuedAt: Long? = null

    override suspend fun shown(): Map<AnimeId, Shown> =
        lock.withLock {
            val shown = episodes()
            // A summary whose episodes the person dismissed goes with the last of them.
            if (shown.isEmpty()) system.cancel(SUMMARY_TAG, SUMMARY_ID)
            shown.mapNotNull { (title, posted) -> posted.shown()?.let { title to it } }.toMap()
        }

    override suspend fun post(
        anime: Anime,
        notification: Shown,
        alert: Boolean,
    ): Boolean =
        lock.withLock {
            val shown = episodes()
            val was = shown[anime.id]
            // A silent replacement does not bring back a notification the person dismissed since the engine looked.
            val posting = alert || was != null
            if (posting) {
                channel()
                enqueue { system.notify(tag(anime.id), EPISODE_ID, episode(anime, notification, alert)) }
            }
            summary(shown.isNotEmpty() || posting)
            enabled()
        }

    override suspend fun withdraw(titles: Set<AnimeId>) =
        lock.withLock {
            val shown = episodes()
            val gone = titles.filter { it in shown }
            if (gone.isEmpty()) return@withLock
            gone.forEach { system.cancel(tag(it), EPISODE_ID) }
            summary(shown.size > gone.size)
        }

    // Whether the device shows the app's episode notifications at all: neither the app's nor the channel's are turned off.
    fun enabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            system.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    // The channel of new episodes, named in the language in effect; the system keeps what the person set on it.
    fun channel() =
        system.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notify_new_episodes), NotificationManager.IMPORTANCE_DEFAULT),
        )

    private fun episodes(): Map<AnimeId, StatusBarNotification> =
        system.activeNotifications
            .filter { it.id == EPISODE_ID }
            .mapNotNull { posted -> posted.tag?.let(::titleOf)?.let { it to posted } }
            .toMap()

    private fun episode(
        anime: Anime,
        notification: Shown,
        alert: Boolean,
    ): Notification {
        val (episode, toWatch, marks, airedAt, notifyAt) = notification
        val text =
            if (toWatch != null && toWatch > 1) {
                context.getString(R.string.notify_episode_aired_to_watch, episode, toWatch)
            } else {
                context.getString(R.string.notify_episode_aired, episode)
            }
        val extras =
            Bundle().apply {
                putInt(EPISODE, episode)
                toWatch?.let { putInt(TO_WATCH, it) }
                putInt(MARKS, marks)
                putString(AIRED_AT, airedAt.toString())
                putString(NOTIFY_AT, notifyAt.toString())
            }
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(anime.titles.preferred(language()))
            .setContentText(text)
            // The time the episode aired, which a silent replacement keeps, so the stack does not reorder.
            .setWhen(airedAt.toEpochMilli())
            .setOnlyAlertOnce(!alert)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(context.tap(anime.id))
            .addAction(0, context.getString(R.string.notify_mark_watched, marks), context.markWatched(MarkWatched(anime.id, marks)))
            .addExtras(extras)
            .build()
    }

    // The group's summary while one of its notifications is [shown]; it holds no count, so only a new language posts it again.
    private suspend fun summary(shown: Boolean) {
        val title = context.getString(R.string.notify_new_episodes)
        val current = system.activeNotifications.firstOrNull { it.id == SUMMARY_ID && it.tag == SUMMARY_TAG }
        when {
            !shown -> system.cancel(SUMMARY_TAG, SUMMARY_ID)
            current
                ?.notification
                ?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString() == title -> Unit
            else -> {
                val summary =
                    NotificationCompat
                        .Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setGroup(GROUP)
                        .setGroupSummary(true)
                        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(context.open())
                        .build()
                enqueue { system.notify(SUMMARY_TAG, SUMMARY_ID, summary) }
            }
        }
    }

    // The system drops a replacement when the app posts more than five a second, so posts are spaced.
    private suspend fun enqueue(post: () -> Unit) {
        val since = enqueuedAt?.let { (System.nanoTime() - it).nanoseconds }
        if (since != null && since < gap) delay(gap - since)
        post()
        enqueuedAt = System.nanoTime()
    }

    // What the notification was posted with; null for one without it, which no post of this class makes.
    private fun StatusBarNotification.shown(): Shown? {
        val extras = notification.extras
        val toWatch = if (extras.containsKey(TO_WATCH)) extras.getInt(TO_WATCH) else null
        val airedAt = extras.getString(AIRED_AT)?.let(Instant::parse) ?: return null
        val notifyAt = extras.getString(NOTIFY_AT)?.let(Instant::parse) ?: return null
        return Shown(extras.getInt(EPISODE), toWatch, extras.getInt(MARKS), airedAt, notifyAt)
    }

    companion object {
        val CHANNEL = notShown("episodes")
        val GROUP = notShown("episodes")
        val ENQUEUE_GAP = 250.milliseconds

        // Every episode notification has this id under its title's tag; the summary has its own.
        const val EPISODE_ID = 1
        const val SUMMARY_ID = 2
        val SUMMARY_TAG = notShown("summary")

        fun tag(title: AnimeId): String = TAG_PREFIX + title.value

        fun titleOf(tag: String): AnimeId? =
            tag
                .removePrefix(TAG_PREFIX)
                .takeIf { it != tag }
                ?.toLongOrNull()
                ?.let(::AnimeId)

        private val TAG_PREFIX = notShown("episode:")
        private val EPISODE = notShown("kiroku.episode")
        private val TO_WATCH = notShown("kiroku.toWatch")
        private val MARKS = notShown("kiroku.marks")
        private val AIRED_AT = notShown("kiroku.airedAt")
        private val NOTIFY_AT = notShown("kiroku.notifyAt")
    }
}
