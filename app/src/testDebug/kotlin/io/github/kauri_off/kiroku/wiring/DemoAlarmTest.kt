package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.notify.InMemoryShownNotifications
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The demo announces while its process lives: its alarm is a wait in that process, by the demo's own time.
@OptIn(ExperimentalCoroutinesApi::class)
class DemoAlarmTest {
    @Test
    fun `when the demo's time reaches what comes next, the alarm's run posts it, and the alarm waits for the one after`() =
        runTest {
            val unexpected = CopyOnWriteArrayList<Throwable>()
            val began = Fixtures.reference.clock.instant()
            val clock =
                object : Clock() {
                    override fun instant(): Instant = began.plusMillis(currentTime)

                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this
                }
            val shown = InMemoryShownNotifications()
            lateinit var demo: Demo
            val alarm = DemoAlarm(backgroundScope) { demo }
            demo = Demo(Fixtures.reference, backgroundScope, clock = clock, unexpected = unexpected::add, alarm = alarm, shown = shown)
            demo.notifications.run(RunTrigger.AppOpened)
            val first = checkNotNull(demo.notifications.observeComing().first()).minBy { it.notifyAt }
            assertTrue(first.notifyAt.isAfter(clock.instant()))

            // A second before its time nothing is posted; at its time it is, by nobody's asking.
            advanceTimeBy(
                java.time.Duration
                    .between(clock.instant(), first.notifyAt)
                    .toMillis()
                    .milliseconds - 1.seconds,
            )
            runCurrent()
            assertEquals(emptyList<Any>(), shown.posts.toList())
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(
                shown.posts.any { it.alert && it.announcement.anime.id == first.anime.id && it.announcement.episode == first.episode },
            )

            // The run left the wait for what comes after it.
            val posted = shown.posts.size
            val second = checkNotNull(demo.notifications.observeComing().first()).minBy { it.notifyAt }
            assertTrue(second.notifyAt.isAfter(first.notifyAt))
            advanceTimeBy(
                java.time.Duration
                    .between(clock.instant(), second.notifyAt)
                    .toMillis()
                    .milliseconds + 1.seconds,
            )
            runCurrent()
            assertTrue(shown.posts.drop(posted).any { it.alert && it.announcement.anime.id == second.anime.id })
            assertEquals(emptyList<Throwable>(), unexpected.toList())
        }
}
