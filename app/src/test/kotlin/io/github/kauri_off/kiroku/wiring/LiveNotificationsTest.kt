package io.github.kauri_off.kiroku.wiring

import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Looper
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationStore
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

// The notifications of a live graph are built at their first use, and with nobody signed in they reach no host.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LiveNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    // The HTTP client asks whether it is on the main thread exactly when it is built.
    private class CountingDevice : Device {
        val clientBuilds = AtomicInteger()

        override fun isOnline() = true

        override fun observeOnline(): Flow<Boolean> = MutableStateFlow(true)

        override fun isMainThread(): Boolean {
            clientBuilds.incrementAndGet()
            return false
        }
    }

    private val device = CountingDevice()
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val built = CopyOnWriteArrayList<Thread>()

    private fun graph(
        scope: CoroutineScope,
        source: LiveSource = LiveSource.Account,
    ) = LiveGraph(
        LiveParts(context, source, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device),
        listStore = { InMemoryLocalList() },
        notificationStore = { parts, preferences ->
            built += Thread.currentThread()
            liveNotificationStore(parts, preferences)
        },
    )

    @Test
    fun `reading the session or the list builds nothing of notifications, which are built once, at their first use, off the main thread`() =
        runTest {
            val graph = graph(backgroundScope)

            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())
            // Naming them builds nothing either.
            val notifications = graph.notifications
            assertSame(notifications, graph.withoutScreen.notificationRuns)
            assertEquals(emptyList<Thread>(), built.toList())

            assertEquals(NotificationSettings(), notifications.observeSettings().first())
            assertEquals(1, built.size)
            assertNotSame(Looper.getMainLooper().thread, built.single())
            graph.notifications.run(RunTrigger.AppOpened)
            notifications.observeComing().first()
            assertEquals(1, built.size)
            assertEquals(emptyList<Throwable>(), unexpected.toList())
        }

    @Test
    fun `started signed out, a run by each trigger reaches the engine, and neither it nor a read of what it tells builds an HTTP client`() =
        runTest {
            val graph = graph(backgroundScope)

            for (trigger in RunTrigger.entries) {
                val report = graph.notifications.run(trigger)
                assertEquals(trigger, report.trigger)
                assertEquals("$trigger", RunEnd.SignedOut, report.end)
            }
            assertEquals(emptyList<Coming>(), graph.notifications.observeComing().first())
            assertEquals(emptyList<SentNotification>(), graph.notifications.observeSent().first())
            assertEquals(ListWork.SignedOut, graph.withoutScreen.syncList())

            assertEquals(0, device.clientBuilds.get())
            assertEquals(0, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
            assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
            assertEquals(emptyList<Throwable>(), unexpected.toList())
        }

    @Test
    fun `what was sent is read only after a run of the process, which the read asks for when none has ended`() =
        runTest {
            val graph = graph(backgroundScope)
            assertTrue(graph.notifications.observeComing().first() == null)

            assertEquals(emptyList<SentNotification>(), graph.notifications.observeSent().first())

            // The run has ended: what comes next is known, which it is not before one.
            assertEquals(emptyList<Coming>(), graph.notifications.observeComing().first())
            assertEquals(emptyList<Throwable>(), unexpected.toList())
        }

    private fun notifications(
        scope: CoroutineScope,
        store: () -> NotificationStore = { InMemoryNotificationStore() },
        renew: suspend () -> Unit,
    ): LiveNotifications {
        val demo = Demo(Fixtures.reference, scope)
        return LiveNotifications(
            LiveParts(context, LiveSource.Account, "0123abcd", demo.clock, scope, unexpected::add, device = device),
            demo.session.observeSession(),
            { demo.list },
            { demo.airing },
            { demo.connection },
            { EpisodeNotifications(context, LiveSource.Account.name, { TitleLanguage.Romaji }) },
            store,
            renew,
        )
    }

    // The renewal is the session's matter, and the opening is where it is made: a record that cannot be opened is another.
    @Test
    fun `the opening renews the tokens also when the notification engine cannot be built`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val renewals = AtomicInteger()
            val notifications = notifications(scope, store = { throw IllegalStateException(BROKEN) }) { renewals.incrementAndGet() }

            val run = async { runCatching { notifications.run(RunTrigger.AppOpened) } }

            assertEquals(BROKEN, withTimeout(WAIT) { run.await() }.exceptionOrNull()?.message)
            assertEquals(1, renewals.get())
            assertEquals(emptyList<Throwable>(), unexpected.toList())
            scope.coroutineContext.job.cancelAndJoin()
        }

    // MAL revokes the old tokens as it answers a renewal, and a broadcast's hold lets its process go before a slow answer.
    @Test
    fun `only the opening's run asks for the renewal of the tokens, beside itself, and a read of what was sent does not`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val renewals = AtomicInteger()
            val renewed = CompletableDeferred<Unit>()
            val notifications =
                notifications(scope) {
                    renewals.incrementAndGet()
                    renewed.await()
                }

            // The read's own run ends with nothing renewed, and the read waits for no renewal.
            withTimeout(WAIT) { notifications.observeSent().first() }
            for (trigger in RunTrigger.entries - RunTrigger.AppOpened) {
                assertEquals(trigger, withTimeout(WAIT) { notifications.run(trigger) }.trigger)
            }
            assertEquals(0, renewals.get())

            val run = async { notifications.run(RunTrigger.AppOpened) }
            withTimeout(WAIT) { while (renewals.get() == 0) delay(PAUSE) }
            // The engine's run does not wait for it: another run is made meanwhile.
            assertEquals(RunEnd.Done, withTimeout(WAIT) { notifications.run(RunTrigger.Changed) }.end)
            assertTrue(run.isActive)
            renewed.completeExceptionally(IllegalStateException(BROKEN))
            val report = withTimeout(WAIT) { run.await() }

            assertEquals(RunTrigger.AppOpened, report.trigger)
            assertEquals(RunEnd.Done, report.end)
            assertEquals(1, renewals.get())
            assertEquals(listOf(BROKEN), unexpected.map { it.message })
            scope.coroutineContext.job.cancelAndJoin()
        }

    // A receiver's run, the opening's run and a screen's read meet at a cold start.
    @Test
    fun `first uses made together build one engine`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val builds = AtomicInteger()
            val building = CountDownLatch(1)
            val graph =
                LiveGraph(
                    LiveParts(context, LiveSource.Account, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device),
                    listStore = { InMemoryLocalList() },
                    notificationStore = { parts, preferences ->
                        builds.incrementAndGet()
                        // Held until every first use has had the time to begin its own build.
                        building.await()
                        liveNotificationStore(parts, preferences)
                    },
                )
            val uses =
                listOf<suspend () -> Unit>(
                    { graph.notifications.run(RunTrigger.Alarm) },
                    { graph.notifications.run(RunTrigger.AppOpened) },
                    { graph.notifications.observeSettings().first() },
                    { graph.notifications.observeComing().first() },
                    { graph.notifications.observeSent().first() },
                ).map { use -> async(Dispatchers.IO) { use() } }
            withTimeout(WAIT) { while (builds.get() == 0) delay(PAUSE) }
            delay(MEET)
            building.countDown()
            withTimeout(WAIT) { uses.awaitAll() }

            assertEquals(1, builds.get())
            assertEquals(emptyList<Throwable>(), unexpected.toList())
            scope.coroutineContext.job.cancelAndJoin()
        }

    private companion object {
        const val WAIT = 20_000L
        const val BROKEN = "The renewal broke"
        const val PAUSE = 10L

        // Long enough for calls started together to reach a build that is held.
        const val MEET = 300L
    }
}
