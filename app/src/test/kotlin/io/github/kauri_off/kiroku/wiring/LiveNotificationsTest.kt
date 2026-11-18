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
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.session.HeldBack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
            assertSame(notifications, graph.notificationRuns)
            assertEquals(emptyList<Thread>(), built.toList())

            assertEquals(NotificationSettings(), notifications.observeSettings().first())
            assertEquals(1, built.size)
            assertNotSame(Looper.getMainLooper().thread, built.single())
            graph.notificationRuns.run(RunTrigger.AppOpened)
            notifications.observeComing().first()
            assertEquals(1, built.size)
            assertEquals(emptyList<Throwable>(), unexpected.toList())
        }

    @Test
    fun `started signed out, a run by each trigger reaches the engine, and neither it nor a read of what it tells builds an HTTP client`() =
        runTest {
            val graph = graph(backgroundScope)

            for (trigger in RunTrigger.entries) {
                val report = graph.notificationRuns.run(trigger)
                assertEquals(trigger, report.trigger)
                assertEquals("$trigger", RunEnd.SignedOut, report.end)
            }
            assertEquals(emptyList<Coming>(), graph.notifications.observeComing().first())
            assertEquals(emptyList<SentNotification>(), graph.notifications.observeSent().first())
            assertEquals(HeldBack.SignedOut, graph.withoutScreen { it.sync() })

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

    // The alarm's promise is the notification: MAL's answer to a renewal may take as long as a request may.
    @Test
    fun `a run does not wait for the renewal of the tokens, returns once both have ended, and ends as it would when the renewal fails`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val demo = Demo(Fixtures.reference, scope)
            val renewals = AtomicInteger()
            val renewed = CompletableDeferred<Unit>()
            val notifications =
                LiveNotifications(
                    LiveParts(context, LiveSource.Account, "0123abcd", demo.clock, scope, unexpected::add, device = device),
                    demo.session.observeSession(),
                    { demo.list },
                    { demo.airing },
                    { demo.connection },
                    { TitleLanguage.Romaji },
                    { InMemoryNotificationStore() },
                    renew = {
                        renewals.incrementAndGet()
                        renewed.await()
                    },
                )

            val run = async { notifications.run(RunTrigger.Alarm) }
            // The engine's run has ended: what comes next is known, which it is not before one.
            assertTrue(withTimeout(WAIT) { notifications.observeComing().first { it != null } }!!.isNotEmpty())
            assertEquals(1, renewals.get())
            assertTrue(run.isActive)
            renewed.completeExceptionally(IllegalStateException(BROKEN))
            val report = withTimeout(WAIT) { run.await() }

            assertEquals(RunTrigger.Alarm, report.trigger)
            assertEquals(RunEnd.Done, report.end)
            assertEquals(listOf(BROKEN), unexpected.map { it.message })
            scope.coroutineContext.job.cancelAndJoin()
        }

    // A test ends its app's scope while work of that scope may still be asking for an area.
    @Test
    fun `an area first asked for once the source's scope has ended ends its caller as cancelled, and nothing is reported`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val graph = graph(scope)
            scope.coroutineContext.job.cancelAndJoin()

            for (ask in listOf<suspend () -> Unit>(
                { graph.notifications.observeSettings().first() },
                { graph.airing.observeSchedules().first() },
            )) {
                val asked = async { ask() }
                asked.join()
                assertTrue("${asked.getCompletionExceptionOrNull()}", asked.getCompletionExceptionOrNull() is CancellationException)
            }
            assertEquals(emptyList<Throwable>(), unexpected.toList())
        }

    private companion object {
        const val WAIT = 20_000L
        const val BROKEN = "The renewal broke"
    }
}
