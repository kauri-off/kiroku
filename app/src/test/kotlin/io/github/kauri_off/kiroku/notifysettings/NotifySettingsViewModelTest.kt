package io.github.kauri_off.kiroku.notifysettings

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationAlarm
import io.github.kauri_off.kiroku.core.notify.InMemoryShownNotifications
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.repository.NotificationsRepository
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.toJavaDuration

// The settings over the real engine, with the device's alarm and notifications in memory.
@OptIn(ExperimentalCoroutinesApi::class)
class NotifySettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val fixtures = Fixtures.reference
    private val allowed = DeviceState(NotificationAccess.Allowed, ExactAlarms.Allowed, BatteryUse.Unrestricted)

    private class App(
        val demo: Demo,
        val clock: MovingClock,
        val alarm: InMemoryNotificationAlarm,
        val shown: InMemoryShownNotifications,
        val model: NotifySettingsViewModel,
        val messages: ShellMessages,
    ) {
        var device: DeviceState? = null
        var tests = 0

        suspend fun coming(): List<Coming> = demo.notifications.observeComing().first()!!

        fun content() = model.state.value.content as NotifySettingsContent.Shown
    }

    private fun TestScope.app(
        notifications: (NotificationsRepository) -> NotificationsRepository = { it },
        checks: Flow<Unit> = emptyFlow(),
        testShows: Boolean = true,
    ): App {
        val clock = MovingClock(fixtures.now, FixtureTime.zone)
        val alarm = InMemoryNotificationAlarm()
        val shown = InMemoryShownNotifications()
        val scope = foregroundScope()
        val demo = Demo(fixtures, scope, clock = clock, alarm = alarm, shown = shown)
        val graph = TestGraph(demo)
        lateinit var app: App
        val messages = ShellMessages(scope, graph.list)
        val model =
            NotifySettingsViewModel(
                graph.session,
                notifications(graph.notifications),
                graph.list,
                clock,
                graph.titleLanguage,
                graph.background,
                { app.device ?: allowed },
                checks,
                {
                    app.tests++
                    testShows
                },
                messages,
            )
        app = App(demo, clock, alarm, shown, model, messages)
        showing(model.state)
        return app
    }

    @Test
    fun `turning notifications off withdraws what is posted and clears the alarm, and turning them on announces nothing from before`() =
        runTest(dispatcher) {
            val app = app()
            app.model.readDevice()
            app.demo.notifications.run(RunTrigger.AppOpened)
            val first = app.coming().first()
            app.clock.instant = first.notifyAt.plusSeconds(MINUTE)
            app.demo.notifications.run(RunTrigger.AppOpened)
            advanceUntilIdle()
            assertTrue(first.anime.id in app.shown.notifications.value)
            val later = app.coming().first()

            app.model.onEvent(NotifySettingsEvent.SetOn(false))
            advanceUntilIdle()
            assertFalse(app.content().settings.on)
            assertEquals(emptyMap<Any, Any>(), app.shown.notifications.value)
            assertNull(app.alarm.at.value)

            app.clock.instant = later.notifyAt.plusSeconds(MINUTE)
            val posts = app.shown.posts.size
            app.model.onEvent(NotifySettingsEvent.SetOn(true))
            advanceUntilIdle()
            assertTrue(app.content().settings.on)
            assertEquals(posts, app.shown.posts.size)
            assertTrue(
                app.alarm.at.value!!
                    .isAfter(app.clock.instant),
            )
        }

    @Test
    fun `a changed delay moves the alarm to the delayed notification time`() =
        runTest(dispatcher) {
            val app = app()
            app.model.readDevice()
            app.demo.notifications.run(RunTrigger.AppOpened)
            val before = app.alarm.at.value
            assertEquals(app.coming().first().notifyAt, before)

            app.model.onEvent(NotifySettingsEvent.SetDelay(NotificationDelay.Hour))
            advanceUntilIdle()
            val coming = app.coming().first()
            assertEquals(coming.airsAt.plus(NotificationDelay.Hour.duration.toJavaDuration()), coming.notifyAt)
            assertEquals(coming.notifyAt, app.alarm.at.value)
            assertNotEquals(before, app.alarm.at.value)
            assertEquals(NotificationDelay.Hour, app.content().settings.delay)
            assertEquals(
                coming.notifyAt,
                app
                    .content()
                    .record!!
                    .upcoming!!
                    .first()
                    .notifyAt,
            )
        }

    @Test
    fun `what was sent is shown latest first, and one the device did not show is told so`() =
        runTest(dispatcher) {
            val app = app()
            app.model.readDevice()
            app.demo.notifications.run(RunTrigger.AppOpened)
            val first = app.coming().first()
            app.shown.blocked = true
            app.clock.instant = first.notifyAt.plusSeconds(MINUTE)
            app.demo.notifications.run(RunTrigger.AppOpened)
            advanceUntilIdle()
            val sent = app.content().record!!.sent
            assertEquals(first.anime.id, sent.first().id)
            assertEquals(first.episode, sent.first().episode)
            assertFalse(sent.first().shown)
        }

    @Test
    fun `the device's states are those read when the screen was last shown`() =
        runTest(dispatcher) {
            val app = app()
            advanceUntilIdle()
            assertEquals(NotifySettingsContent.Loading, app.model.state.value.content)

            app.device = allowed.copy(notifications = NotificationAccess.Blocked)
            app.model.readDevice()
            advanceUntilIdle()
            assertEquals(NotificationAccess.Blocked, app.content().device.notifications)

            app.device = allowed
            advanceUntilIdle()
            assertEquals(NotificationAccess.Blocked, app.content().device.notifications)
            app.model.readDevice()
            advanceUntilIdle()
            assertEquals(allowed, app.content().device)
        }

    @Test
    fun `the test notification is posted apart from the engine, which records nothing of it`() =
        runTest(dispatcher) {
            val app = app()
            app.model.readDevice()
            app.demo.notifications.run(RunTrigger.AppOpened)
            advanceUntilIdle()
            val posts = app.shown.posts.size
            app.model.onEvent(NotifySettingsEvent.SendTest)
            advanceUntilIdle()
            assertEquals(1, app.tests)
            assertEquals(posts, app.shown.posts.size)
            assertEquals(
                emptyList<Any>(),
                app.demo.notifications
                    .observeSent()
                    .first(),
            )
        }

    @Test
    fun `signed out the record is not shown and the settings still are`() =
        runTest(dispatcher) {
            val app = app()
            app.model.readDevice()
            app.demo.session.signOut()
            advanceUntilIdle()
            assertNull(app.content().record)
            app.model.onEvent(NotifySettingsEvent.SetAlsoPlanned(true))
            advanceUntilIdle()
            assertTrue(app.content().settings.alsoPlanned)
        }

    @Test
    fun `a change the store refuses says so, and the settings stay as stored`() =
        runTest(dispatcher) {
            val app =
                app(notifications = { engine ->
                    object : NotificationsRepository by engine {
                        override suspend fun changeSettings(change: (NotificationSettings) -> NotificationSettings) =
                            Outcome.Failure(DataError.StorageError)
                    }
                })
            app.model.readDevice()
            advanceUntilIdle()
            app.model.onEvent(NotifySettingsEvent.SetOn(false))
            advanceUntilIdle()
            assertEquals(
                ShellMessage(ShellMessage.Text.CouldNotSave),
                app.messages.current.value
                    ?.message,
            )
            assertTrue(app.content().settings.on)
        }

    @Test
    fun `an expired session shows its banner over the settings and the record`() =
        runTest(dispatcher) {
            val app = app()
            app.model.readDevice()
            advanceUntilIdle()
            assertFalse(app.model.state.value.sessionExpired)
            app.demo.server.sessionExpired.value = true
            advanceUntilIdle()
            assertTrue(app.model.state.value.sessionExpired)
            assertNotNull(app.content().record)
        }

    @Test
    fun `settings that cannot be read are the error state, and Retry reads them again`() =
        runTest(dispatcher) {
            var failing = true
            val app =
                app(notifications = { engine ->
                    object : NotificationsRepository by engine {
                        override fun observeSettings() =
                            flow {
                                if (failing) throw DataException(DataError.StorageError)
                                emitAll(engine.observeSettings())
                            }
                    }
                })
            app.model.readDevice()
            advanceUntilIdle()
            assertEquals(NotifySettingsContent.Failed(DataError.StorageError), app.model.state.value.content)
            failing = false
            advanceUntilIdle()
            assertEquals(NotifySettingsContent.Failed(DataError.StorageError), app.model.state.value.content)
            app.model.onEvent(NotifySettingsEvent.Retry)
            advanceUntilIdle()
            assertTrue(app.content().settings.on)
        }

    @Test
    fun `the device is read again at each check while the screen stays shown, and the latest read is the one shown`() =
        runTest(dispatcher) {
            val checks = MutableSharedFlow<Unit>()
            val app = app(checks = checks)
            app.model.readDevice()
            advanceUntilIdle()
            assertEquals(allowed, app.content().device)

            app.device = allowed.copy(notifications = NotificationAccess.ChannelOff)
            checks.emit(Unit)
            advanceUntilIdle()
            assertEquals(NotificationAccess.ChannelOff, app.content().device.notifications)

            app.device = allowed
            app.model.readDevice()
            advanceUntilIdle()
            assertEquals(allowed, app.content().device)
        }

    @Test
    fun `a test notification the device does not show is told so, until a read finds the app's notifications allowed, and then for good`() =
        runTest(dispatcher) {
            val checks = MutableSharedFlow<Unit>()
            val app = app(checks = checks, testShows = false)
            app.device = allowed.copy(notifications = NotificationAccess.Blocked)
            app.model.readDevice()
            advanceUntilIdle()
            assertFalse(app.content().testNotShown)
            app.model.onEvent(NotifySettingsEvent.SendTest)
            advanceUntilIdle()
            assertTrue(app.content().testNotShown)

            // Only the category turned off: still not shown.
            app.device = allowed.copy(notifications = NotificationAccess.ChannelOff)
            checks.emit(Unit)
            advanceUntilIdle()
            assertTrue(app.content().testNotShown)

            app.device = allowed
            checks.emit(Unit)
            advanceUntilIdle()
            assertFalse(app.content().testNotShown)

            // Blocked again with no new tap, read by a check and by a return to the screen.
            app.device = allowed.copy(notifications = NotificationAccess.Blocked)
            checks.emit(Unit)
            advanceUntilIdle()
            assertFalse(app.content().testNotShown)
            app.model.readDevice()
            advanceUntilIdle()
            assertFalse(app.content().testNotShown)

            app.model.onEvent(NotifySettingsEvent.SendTest)
            advanceUntilIdle()
            assertTrue(app.content().testNotShown)
        }

    private companion object {
        const val MINUTE = 60L
    }
}
