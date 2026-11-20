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
import io.github.kauri_off.kiroku.core.model.AnimeId
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

    private fun notifications(gap: Duration = Duration.ZERO) = EpisodeNotifications(app, SOURCE, { language }, gap)

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
        active()
            .singleOrNull {
                it.tag ==
                    EpisodeNotifications.tag(
                        SOURCE,
                        anime.id,
                    ) &&
                    it.id == EpisodeNotifications.EPISODE_ID
            }?.notification

    private fun summary(): Notification? =
        active()
            .singleOrNull {
                it.id == EpisodeNotifications.SUMMARY_ID &&
                    it.tag ==
                    EpisodeNotifications.summaryTag(
                        SOURCE,
                    )
            }?.notification

    // Each episode notification shown, by its title, and nothing else of the app's but the summary while one is.
    private fun assertShownExactly(vararg titles: Anime) {
        val episodes = active().filter { it.id == EpisodeNotifications.EPISODE_ID }.map { it.tag }.toSet()
        assertEquals(titles.map { EpisodeNotifications.tag(SOURCE, it.id) }.toSet(), episodes)
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
            system.notify(EpisodeNotifications.tag(SOURCE, kagerou.id), OTHER_ID, other)
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
            system.cancel(EpisodeNotifications.tag(SOURCE, kagerou.id), EpisodeNotifications.EPISODE_ID)
            assertEquals(setOf(shiokaze.id), shown.shown().keys)
            assertTrue(summary() != null)
            system.cancel(EpisodeNotifications.tag(SOURCE, shiokaze.id), EpisodeNotifications.EPISODE_ID)
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

            system.cancel(EpisodeNotifications.tag(SOURCE, kagerou.id), EpisodeNotifications.EPISODE_ID)
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
                assertEquals(EpisodeNotifications.group(SOURCE), notification.group)
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
            val first = announcement(kagerou, 5, toWatch = 3, marks = 3)
            val second = announcement(shiokaze, 8, toWatch = 1, marks = 8)
            shown.post(first, alert = true)
            shown.post(second, alert = true)
            val kagerouAction = episode(kagerou)!!.actions.single().actionIntent
            val shiokazeAction = episode(shiokaze)!!.actions.single().actionIntent
            assertNotEquals(kagerouAction, shiokazeAction)
            for ((intent, mark) in listOf(
                kagerouAction to MarkWatched(SOURCE, kagerou.id, 3, first.second),
                shiokazeAction to MarkWatched(SOURCE, shiokaze.id, 8, second.second),
            )) {
                val action = shadowOf(intent)
                assertTrue(action.isBroadcast)
                assertTrue(action.isImmutable)
                assertEquals(ComponentName(app, MarkWatchedReceiver::class.java), action.savedIntent.component)
                assertEquals(mark, action.savedIntent.markWatched())
            }
            // A replacement's action marks what the replacement says.
            val replaced = announcement(kagerou, 5, toWatch = 2, marks = 4)
            shown.post(replaced, alert = false)
            assertEquals(
                MarkWatched(SOURCE, kagerou.id, 4, replaced.second),
                shadowOf(episode(kagerou)!!.actions.single().actionIntent).savedIntent.markWatched(),
            )
            // The receiver is not exported: no other app can send it a broadcast.
            val receiver =
                app.packageManager.getReceiverInfo(
                    ComponentName(app, MarkWatchedReceiver::class.java),
                    PackageManager.GET_META_DATA,
                )
            assertFalse(receiver.exported)
        }

    // A debug or preview build's sources share the device, and a title can be on the lists of two of them.
    @Test
    fun `the notifications of one source are neither shown, replaced nor withdrawn as another's`() =
        runBlocking {
            val own = notifications()
            val other = EpisodeNotifications(app, "another", { language }, Duration.ZERO)
            val (anime, said) = announcement(kagerou, 13)
            val others = said.copy(episode = 20, marks = 20)
            own.post(anime, said, alert = true)
            other.post(anime, others, alert = true)

            assertEquals(mapOf(anime.id to said), own.shown())
            assertEquals(mapOf(anime.id to others), other.shown())
            assertEquals(2, active().count { it.id == EpisodeNotifications.EPISODE_ID })
            assertEquals(2, active().count { it.id == EpisodeNotifications.SUMMARY_ID })
            assertEquals(2, active().map { it.notification.group }.toSet().size)

            // What one source takes away is its own, its summary with it.
            other.withdraw(setOf(anime.id))
            assertEquals(emptyMap<AnimeId, Shown>(), other.shown())
            assertEquals(mapOf(anime.id to said), own.shown())
            assertShownExactly(kagerou)
        }

    @Test
    fun `the test notification goes through the channel of new episodes, and no source's run reads it, replaces it or withdraws it`() =
        runBlocking {
            val tests = EpisodeNotifications(app, "test", { language }, Duration.ZERO)
            val own = notifications()
            assertTrue(tests.test())
            val test = active().single()
            assertEquals(EpisodeNotifications.CHANNEL, test.notification.channelId)
            assertEquals(
                "Test notification",
                test.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE)
                    .toString(),
            )
            assertEquals(emptyMap<AnimeId, Shown>(), tests.shown())
            assertEquals(emptyMap<AnimeId, Shown>(), own.shown())

            own.post(announcement(kagerou, 13), alert = true)
            own.withdraw(setOf(kagerou.id, shiokaze.id))
            tests.withdraw(setOf(kagerou.id))
            assertEquals(listOf(test.key), active().map { it.key })
        }

    // N9: "Mark watched" that could not be saved says so in the title's notification, which the engine still takes for its own.
    @Test
    fun `could not save replaces a title's notification in place, keeping what the engine posted it with`() =
        runBlocking {
            val shown = notifications()
            val (anime, said) = announcement(kagerou, 5, toWatch = 3, marks = 3)
            shown.post(anime, said, alert = true)
            shown.post(announcement(shiokaze, 8), alert = true)
            val was = episode(kagerou)!!

            assertTrue(shown.couldNotSave(kagerou.id, 3, kagerou, said.copy(marks = 1)))

            val failed = episode(kagerou)!!
            assertEquals(kagerou.titles.romaji, failed.title)
            assertEquals("Could not save: episode\u00A03\u00A0watched", failed.text)
            assertEquals(said, shown.shown()[kagerou.id])
            assertShownExactly(kagerou, shiokaze)
            // In the stack where it was, at its time, silent, and with no action: a tap opens the title.
            assertEquals(was.`when`, failed.`when`)
            assertEquals(was.group, failed.group)
            assertEquals(EpisodeNotifications.CHANNEL, failed.channelId)
            assertTrue(failed.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertTrue(failed.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(0, failed.actions.orEmpty().size)
            assertEquals(kagerou.id, shadowOf(failed.contentIntent).savedIntent.tappedTitle())
            // The engine's next post for the title turns it back into an episode's notification.
            shown.post(anime, said.copy(marks = 4), alert = false)
            assertEquals("Episode\u00A05\u00A0aired · 3\u00A0to watch", episode(kagerou)!!.text)
        }

    // It replaces a notification the person has seen alert already: it makes no sound or vibration of its own.
    @Test
    fun `could not save in place of a shown notification does not alert again`() =
        runBlocking {
            val shown = notifications()
            val (anime, said) = announcement(kagerou, 5)
            shown.post(anime, said, alert = true)

            shown.couldNotSave(kagerou.id, 5, kagerou, said)

            assertTrue(episode(kagerou)!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    // The person had dismissed it: what the tapped notification said makes it the engine's again, which withdraws it with the rest.
    @Test
    fun `could not save for a title whose notification is gone says so with what the tapped one said, and goes as the engine's does`() =
        runBlocking {
            val shown = notifications()
            val (_, said) = announcement(gekkou, 2)

            assertTrue(shown.couldNotSave(gekkou.id, 2, gekkou, said))

            assertEquals(gekkou.titles.romaji, episode(gekkou)!!.title)
            assertEquals("Could not save: episode\u00A02\u00A0watched", episode(gekkou)!!.text)
            assertEquals(said.airedAt.toEpochMilli(), episode(gekkou)!!.`when`)
            assertEquals(mapOf(gekkou.id to said), shown.shown())
            assertShownExactly(gekkou)
            shown.withdraw(setOf(gekkou.id))
            assertShownExactly()
        }

    @Test
    fun `could not save without a title to name posts nothing`() =
        runBlocking {
            val shown = notifications()

            assertFalse(shown.couldNotSave(gekkou.id, 2, null, announcement(gekkou, 2).second))

            assertShownExactly()
        }

    @Test
    @Config(qualifiers = "ru")
    fun `could not save is Russian under a Russian locale`() =
        runBlocking {
            val shown = notifications()
            val (anime, said) = announcement(kagerou, 16)
            shown.post(anime, said, alert = true)
            shown.couldNotSave(kagerou.id, 16, null, said)
            assertEquals("Не удалось сохранить: серия\u00A016\u00A0просмотрена", episode(kagerou)!!.text)
            assertEquals(kagerou.titles.romaji, episode(kagerou)!!.title)
        }

    // A debug build's sources share the device: the action of each is its own pending intent, which names its source.
    @Test
    fun `two sources' actions for one title are two pending intents, each naming its source`() =
        runBlocking {
            val other = EpisodeNotifications(app, "another", { language }, Duration.ZERO)
            val own = announcement(kagerou, 5, marks = 4)
            val others = announcement(kagerou, 7, marks = 6)
            notifications().post(own, alert = true)
            other.post(others, alert = true)
            val actions =
                active()
                    .filter { it.id == EpisodeNotifications.EPISODE_ID }
                    .map {
                        shadowOf(
                            it.notification.actions
                                .single()
                                .actionIntent,
                        ).savedIntent.markWatched()
                    }.toSet()
            assertEquals(
                setOf(MarkWatched(SOURCE, kagerou.id, 4, own.second), MarkWatched("another", kagerou.id, 6, others.second)),
                actions,
            )
        }

    private companion object {
        const val SOURCE = "account"

        // A notification of another kind, as "Mark watched" will post.
        const val OTHER_ID = 3
    }
}
