package io.github.kauri_off.kiroku.notifysettings

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationAlarm
import io.github.kauri_off.kiroku.core.notify.InMemoryShownNotifications
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    ) {
        var device: DeviceState? = null
        var tests = 0

        suspend fun coming(): List<Coming> = demo.notifications.observeComing().first()!!

        fun content() = model.state.value.content as NotifySettingsContent.Shown
    }

    private fun TestScope.app(): App {
        val clock = MovingClock(fixtures.now, FixtureTime.zone)
        val alarm = InMemoryNotificationAlarm()
        val shown = InMemoryShownNotifications()
        val scope = foregroundScope()
        val demo = Demo(fixtures, scope, clock = clock, alarm = alarm, shown = shown)
        val graph = TestGraph(demo)
        lateinit var app: App
        val model =
            NotifySettingsViewModel(
                graph.session,
                graph.notifications,
                graph.list,
                clock,
                graph.titleLanguage,
                graph.background,
                { app.device ?: allowed },
                {
                    app.tests++
                    true
                },
                ShellMessages(scope, graph.list),
            )
        app = App(demo, clock, alarm, shown, model)
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

    private companion object {
        const val MINUTE = 60L
    }
}
