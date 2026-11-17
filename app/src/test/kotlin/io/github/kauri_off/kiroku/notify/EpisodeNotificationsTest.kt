package io.github.kauri_off.kiroku.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Shown
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// On Android 11 and on a current one: how shown notifications are read back differs between them.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], qualifiers = "en")
class EpisodeNotificationsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val system = app.getSystemService(NotificationManager::class.java)
    private var airedAt = Instant.parse("2026-11-12T09:00:00.123456789Z")
    private var language = TitleLanguage.Romaji

    private fun notifications(gap: Duration = Duration.ZERO) = EpisodeNotifications(app, { language }, gap)

    private val kagerou = FixtureAnime.exactToWatch
    private val shiokaze = FixtureAnime.exactCaughtUp
    private val gekkou = FixtureAnime.exactOnBreak

    // What a title's notification says, of an episode that aired at [airedAt] and is notified half an hour later.
    private fun announcement(
        anime: Anime,
        episode: Int,
        toWatch: Int? = 1,
        marks: Int = episode,
    ) = anime to Shown(episode, toWatch, marks, airedAt, airedAt.plusSeconds(1800))

    private suspend fun EpisodeNotifications.post(
        notification: Pair<Anime, Shown>,
        alert: Boolean,
    ) = post(notification.first, notification.second, alert)

    private fun active(): List<StatusBarNotification> = system.activeNotifications.toList()

    private fun episode(anime: Anime): Notification? =
        active().singleOrNull { it.tag == EpisodeNotifications.tag(anime.id) && it.id == EpisodeNotifications.EPISODE_ID }?.notification

    private fun summary(): Notification? =
        active().singleOrNull { it.id == EpisodeNotifications.SUMMARY_ID && it.tag == EpisodeNotifications.SUMMARY_TAG }?.notification

    // Each episode notification shown, by its title, and nothing else of the app's but the summary while one is.
    private fun assertShownExactly(vararg titles: Anime) {
        val episodes = active().filter { it.id == EpisodeNotifications.EPISODE_ID }.map { it.tag }.toSet()
        assertEquals(titles.map { EpisodeNotifications.tag(it.id) }.toSet(), episodes)
        assertEquals(if (titles.isEmpty()) 0 else 1, active().count { it.id == EpisodeNotifications.SUMMARY_ID })
        assertEquals(active().size, episodes.size + if (titles.isEmpty()) 0 else 1)
        summary()?.let { summary ->
            assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            assertEquals("New episodes", summary.title)
        }
    }

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `after posts, a replacement and withdrawals the notifications shown are exactly those asked for, the summary only while one is`() =
        runBlocking {
            val shown = notifications()
            assertShownExactly()
            shown.post(announcement(kagerou, 3), alert = true)
            assertShownExactly(kagerou)
            val second = announcement(shiokaze, 5, toWatch = 2, marks = 4)
            shown.post(second, alert = true)
            val third = announcement(gekkou, 1)
            shown.post(third, alert = true)
            assertShownExactly(kagerou, shiokaze, gekkou)
            airedAt = airedAt.plusSeconds(7 * 24 * 3600)
            val replaced = announcement(kagerou, 4)
            shown.post(replaced, alert = true)
            assertShownExactly(kagerou, shiokaze, gekkou)
            // Given back exactly as posted, to the nanosecond of its times.
            assertEquals(listOf(replaced, second, third).toMap().mapKeys { it.key.id }, shown.shown())
            shown.withdraw(setOf(shiokaze.id, FixtureAnime.longTitle.id))
            assertShownExactly(kagerou, gekkou)
            shown.withdraw(setOf(FixtureAnime.longTitle.id))
            assertShownExactly(kagerou, gekkou)
            shown.withdraw(setOf(kagerou.id, gekkou.id))
            assertShownExactly()
            assertEquals(emptyMap<Any, Any>(), shown.shown())
        }

    @Test
    fun `to watch is read back as unknown when the announcement has none`() =
        runBlocking {
            val shown = notifications()
            val posted = announcement(kagerou, 7, toWatch = null, marks = 6)
            shown.post(posted, alert = true)
            assertEquals(mapOf(kagerou.id to posted.second), shown.shown())
        }

    @Test
    fun `only episode notifications are given back and withdrawn, never another the app shows`() =
        runBlocking {
            val shown = notifications()
            shown.channel()
            val other =
                Notification
                    .Builder(app, EpisodeNotifications.CHANNEL)
                    .setSmallIcon(io.github.kauri_off.kiroku.R.drawable.ic_notification)
                    .build()
            system.notify(EpisodeNotifications.tag(kagerou.id), OTHER_ID, other)
            system.notify(OTHER_ID, other)
            val posted = announcement(shiokaze, 5)
            shown.post(posted, alert = true)
            assertEquals(mapOf(shiokaze.id to posted.second), shown.shown())
            shown.withdraw(setOf(kagerou.id, shiokaze.id))
            assertEquals(emptyMap<Any, Any>(), shown.shown())
            assertEquals(2, active().count { it.id == OTHER_ID })
            assertNull(summary())
        }

    @Test
    fun `the summary goes with the last notification the person dismissed`() =
        runBlocking {
            val shown = notifications()
            shown.post(announcement(kagerou, 3), alert = true)
            shown.post(announcement(shiokaze, 5), alert = true)
            system.cancel(EpisodeNotifications.tag(kagerou.id), EpisodeNotifications.EPISODE_ID)
            assertEquals(setOf(shiokaze.id), shown.shown().keys)
            assertTrue(summary() != null)
            system.cancel(EpisodeNotifications.tag(shiokaze.id), EpisodeNotifications.EPISODE_ID)
            assertEquals(emptyMap<Any, Any>(), shown.shown())
            assertShownExactly()
        }

    @Test
    fun `a post alerts only when asked, shows when its episode aired, and a silent one brings back nothing dismissed`() =
        runBlocking {
            val shown = notifications()
            val first = airedAt
            shown.post(announcement(shiokaze, 5), alert = true)
            shown.post(announcement(kagerou, 3), alert = true)
            val alerted = episode(kagerou)!!
            assertEquals(0, alerted.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertEquals(first.toEpochMilli(), alerted.`when`)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, system.getNotificationChannel(EpisodeNotifications.CHANNEL).importance)

            val silent = announcement(kagerou, 3, toWatch = 2, marks = 2)
            shown.post(silent, alert = false)
            val replaced = episode(kagerou)!!
            assertTrue(replaced.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(first.toEpochMilli(), replaced.`when`)
            assertEquals(silent.second, shown.shown()[kagerou.id])

            airedAt = first.plusSeconds(7 * 24 * 3600)
            shown.post(announcement(kagerou, 4, toWatch = 3, marks = 2), alert = true)
            val again = episode(kagerou)!!
            assertEquals(0, again.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertEquals(airedAt.toEpochMilli(), again.`when`)

            system.cancel(EpisodeNotifications.tag(kagerou.id), EpisodeNotifications.EPISODE_ID)
            // Dismissed while another title's notification is still shown, it does not come back.
            shown.post(announcement(kagerou, 4, toWatch = 2, marks = 3), alert = false)
            assertNull(episode(kagerou))
            assertShownExactly(shiokaze)
        }

    @Test
    fun `every notification of the group alerts as itself, and its summary never`() =
        runBlocking {
            val shown = notifications()
            shown.post(announcement(kagerou, 3), alert = true)
            shown.post(announcement(shiokaze, 5), alert = true)
            for (notification in listOf(episode(kagerou)!!, episode(shiokaze)!!, summary()!!)) {
                assertEquals(EpisodeNotifications.GROUP, notification.group)
                assertEquals(EpisodeNotifications.CHANNEL, notification.channelId)
                assertEquals(Notification.GROUP_ALERT_CHILDREN, notification.groupAlertBehavior)
            }
            // A tapped notification goes; a tap on the summary takes none of the group with it.
            assertTrue(episode(kagerou)!!.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(0, summary()!!.flags and Notification.FLAG_AUTO_CANCEL)
            assertTrue(summary()!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    @Test
    fun `a post tells whether the device shows the app's episode notifications, and posts either way`() =
        runBlocking {
            val shown = notifications()
            assertTrue(shown.post(announcement(kagerou, 3), alert = true))
            shadowOf(system).setNotificationsEnabled(false)
            assertFalse(shown.post(announcement(shiokaze, 5), alert = true))
            assertTrue(episode(shiokaze) != null)
            shadowOf(system).setNotificationsEnabled(true)
            system.createNotificationChannel(NotificationChannel(EpisodeNotifications.CHANNEL, "off", NotificationManager.IMPORTANCE_NONE))
            assertFalse(shown.post(announcement(gekkou, 1), alert = true))
            assertFalse(shown.enabled())
        }

    @Test
    fun `posts are spaced so that the system drops no replacement`() =
        runBlocking {
            val gap = 120.milliseconds
            val shown = notifications(gap)
            val began = System.nanoTime()
            // Three notifications and the summary once: four posts, three gaps.
            shown.post(announcement(kagerou, 3), alert = true)
            shown.post(announcement(shiokaze, 5), alert = true)
            shown.post(announcement(gekkou, 1), alert = true)
            assertTrue((System.nanoTime() - began) >= (gap * 3).inWholeNanoseconds)
            assertShownExactly(kagerou, shiokaze, gekkou)
        }

    @Test
    fun `the text is the resources' in the language in effect at each post, with the title in the preferred language`() =
        runBlocking {
            val shown = notifications()
            shown.post(announcement(kagerou, 16), alert = true)
            assertEquals("Kagerou Toshokan 2nd Season", episode(kagerou)!!.title)
            assertEquals("Episode\u00A016\u00A0aired", episode(kagerou)!!.text)
            assertEquals("Mark ep.\u00A016\u00A0watched", episode(kagerou)!!.actions.single().title)
            assertEquals("New episodes", system.getNotificationChannel(EpisodeNotifications.CHANNEL).name)

            language = TitleLanguage.English
            shown.post(announcement(kagerou, 16, toWatch = 3, marks = 14), alert = false)
            assertEquals("The Heat Haze Library Season 2", episode(kagerou)!!.title)
            assertEquals("Episode\u00A016\u00A0aired · 3\u00A0to watch", episode(kagerou)!!.text)
            assertEquals("Mark ep.\u00A014\u00A0watched", episode(kagerou)!!.actions.single().title)

            RuntimeEnvironment.setQualifiers("ru")
            shown.post(announcement(kagerou, 16, toWatch = 2, marks = 15), alert = false)
            assertEquals("Вышла серия\u00A016 · к просмотру:\u00A02", episode(kagerou)!!.text)
            assertEquals("Отметить серию\u00A015", episode(kagerou)!!.actions.single().title)
            assertEquals("Новые серии", system.getNotificationChannel(EpisodeNotifications.CHANNEL).name)
            assertEquals("Новые серии", summary()!!.title)
        }

    @Test
    @Config(qualifiers = "ru")
    fun `under a Russian locale the text is Russian, with a count after a colon in place of a plural`() =
        runBlocking {
            val shown = notifications()
            shown.post(announcement(kagerou, 16), alert = true)
            assertEquals("Вышла серия\u00A016", episode(kagerou)!!.text)
            assertEquals("Отметить серию\u00A016", episode(kagerou)!!.actions.single().title)
            assertEquals("Новые серии", system.getNotificationChannel(EpisodeNotifications.CHANNEL).name)
            assertEquals("Новые серии", summary()!!.title)
            for ((toWatch, form) in listOf(2 to "2", 5 to "5", 21 to "21")) {
                shown.post(announcement(kagerou, 16, toWatch = toWatch), alert = false)
                assertEquals("Вышла серия\u00A016 · к просмотру:\u00A0$form", episode(kagerou)!!.text)
            }
        }

    @Test
    fun `a tap opens the title in the app, through an intent no other app can change`() =
        runBlocking {
            notifications().post(announcement(kagerou, 3), alert = true)
            val tap = shadowOf(episode(kagerou)!!.contentIntent)
            assertTrue(tap.isActivity)
            assertTrue(tap.isImmutable)
            assertEquals(ComponentName(app, MainActivity::class.java), tap.savedIntent.component)
            assertEquals(kagerou.id, tap.savedIntent.tappedTitle())
            val open = shadowOf(summary()!!.contentIntent)
            assertTrue(open.isActivity)
            assertTrue(open.isImmutable)
            assertNull(open.savedIntent.tappedTitle())
        }

    @Test
    fun `the action carries its title and the episode it marks, to this app's receiver alone`() =
        runBlocking {
            val shown = notifications()
            shown.post(announcement(kagerou, 5, toWatch = 3, marks = 3), alert = true)
            shown.post(announcement(shiokaze, 8, toWatch = 1, marks = 8), alert = true)
            val kagerouAction = episode(kagerou)!!.actions.single().actionIntent
            val shiokazeAction = episode(shiokaze)!!.actions.single().actionIntent
            assertNotEquals(kagerouAction, shiokazeAction)
            for ((intent, mark) in listOf(kagerouAction to MarkWatched(kagerou.id, 3), shiokazeAction to MarkWatched(shiokaze.id, 8))) {
                val action = shadowOf(intent)
                assertTrue(action.isBroadcast)
                assertTrue(action.isImmutable)
                assertEquals(ComponentName(app, MarkWatchedReceiver::class.java), action.savedIntent.component)
                assertEquals(mark, action.savedIntent.markWatched())
            }
            // A replacement's action marks what the replacement says.
            shown.post(announcement(kagerou, 5, toWatch = 2, marks = 4), alert = false)
            assertEquals(MarkWatched(kagerou.id, 4), shadowOf(episode(kagerou)!!.actions.single().actionIntent).savedIntent.markWatched())
            // The receiver is not exported: no other app can send it a broadcast.
            val receiver =
                app.packageManager.getReceiverInfo(
                    ComponentName(app, MarkWatchedReceiver::class.java),
                    PackageManager.GET_META_DATA,
                )
            assertFalse(receiver.exported)
        }

    private companion object {
        // A notification of another kind, as "Mark watched" will post.
        const val OTHER_ID = 3
    }
}
