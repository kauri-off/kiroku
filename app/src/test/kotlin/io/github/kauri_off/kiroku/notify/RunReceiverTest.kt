package io.github.kauri_off.kiroku.notify

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

// What starts a run without a screen (N2, N8): the alarm, a restart, an update and a change of the exact-alarm access.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = RecordedRunsApplication::class)
class RunReceiverTest {
    private val application = RuntimeEnvironment.getApplication() as RecordedRunsApplication
    private val runs get() = application.runs

    private val moments =
        mapOf(
            Intent.ACTION_BOOT_COMPLETED to RunTrigger.DeviceRestarted,
            Intent.ACTION_MY_PACKAGE_REPLACED to RunTrigger.AppUpdated,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED to RunTrigger.AlarmAccessChanged,
        )

    @Test
    fun `each of the system's moments reaches the receiver the manifest declares and starts its one run`() {
        for ((action, trigger) in moments) {
            application.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(action, listOf(trigger), runs.started.toList())
            runs.started.clear()
        }
    }

    @Test
    fun `a broadcast is held until its run has ended`() {
        val receiver =
            shadowOf(application)
                .registeredReceivers
                .map { it.broadcastReceiver }
                .filterIsInstance<RunReceiver>()
                .single()
        val alarm = Intent(application, RunReceiver::class.java).setAction(RunReceiver.ALARM)
        for ((sent, trigger) in moments.mapKeys { Intent(it.key) } + (alarm to RunTrigger.Alarm)) {
            val until = CompletableDeferred<Unit>().also { runs.until = it }
            application.sendBroadcast(sent)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(sent.action, listOf(trigger), runs.started.toList())
            assertTrue("${sent.action} is held", shadowOf(receiver).wentAsync())
            val finished = shadowOf(shadowOf(receiver).originalPendingResult).future
            assertFalse("${sent.action} finished while its run goes on", finished.isDone)
            until.complete(Unit)
            assertTrue("${sent.action} finished once its run has ended", finished.isDone)
            runs.started.clear()
        }
    }

    @Test
    fun `a broadcast is let go before the system ends its receiver, a foreground one's at ten seconds and another's at a minute`() {
        val receiver =
            shadowOf(application)
                .registeredReceivers
                .map { it.broadcastReceiver }
                .filterIsInstance<RunReceiver>()
                .single()
        val access = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        for ((foreground, given) in listOf(true to 10.seconds, false to 60.seconds)) {
            runs.until = CompletableDeferred()
            application.sendBroadcast(Intent(access).addFlags(if (foreground) Intent.FLAG_RECEIVER_FOREGROUND else 0))
            shadowOf(Looper.getMainLooper()).idle()
            val finished = shadowOf(shadowOf(receiver).originalPendingResult).future
            if (!foreground) application.time.advanceTimeBy(10.seconds)
            assertFalse("foreground $foreground", finished.isDone)
            application.time.advanceTimeBy(given - 1.seconds - if (foreground) 0.seconds else 10.seconds)
            application.time.runCurrent()
            assertTrue("foreground $foreground: let go a second before the system ends it", finished.isDone)
        }
        assertEquals(listOf(RunTrigger.AlarmAccessChanged, RunTrigger.AlarmAccessChanged), runs.started.toList())
    }

    @Test
    fun `any other broadcast starts no run`() {
        for (action in listOf(null, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
            application.sendBroadcast(Intent(application, RunReceiver::class.java).setAction(action))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("$action", emptyList<RunTrigger>(), runs.started.toList())
        }
    }

    @Test
    fun `no other app can send to the receiver`() {
        val receiver =
            application.packageManager.getReceiverInfo(
                ComponentName(application, RunReceiver::class.java),
                PackageManager.GET_META_DATA,
            )
        assertFalse(receiver.exported)
        assertTrue(receiver.enabled)
    }
}
