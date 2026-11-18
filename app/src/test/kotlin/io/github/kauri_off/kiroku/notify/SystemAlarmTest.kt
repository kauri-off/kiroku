package io.github.kauri_off.kiroku.notify

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.util.TimeZone

// The alarm port on the system's alarm service (section 5.1, N8).
@RunWith(RobolectricTestRunner::class)
@Config(application = RecordedRunsApplication::class)
class SystemAlarmTest {
    private val application = RuntimeEnvironment.getApplication() as RecordedRunsApplication
    private val system = shadowOf(application.getSystemService(AlarmManager::class.java))
    private val alarm = SystemAlarm(application)
    private val zone = TimeZone.getDefault()

    @After
    fun restore() {
        TimeZone.setDefault(zone)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun set(at: Instant?) = runBlocking { alarm.set(at) }

    private fun only() = system.scheduledAlarms.single()

    @Test
    fun `the alarm set is the one asked for, wakes an idle device and takes the place of the one before`() {
        set(FIRST)
        assertEquals(FIRST.toEpochMilli(), only().triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, only().type)
        assertTrue("may fire while the device idles", only().isAllowWhileIdle)
        assertEquals("one-shot", 0, only().intervalMs)
        set(LATER)
        assertEquals(LATER.toEpochMilli(), only().triggerAtMs)
        set(FIRST)
        assertEquals(FIRST.toEpochMilli(), only().triggerAtMs)
        set(null)
        assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), system.scheduledAlarms)
        set(null)
        assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), system.scheduledAlarms)
    }

    @Test
    fun `the alarm is exact only with the access, as it is at each set`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        set(FIRST)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, only().windowLengthMs)
        assertTrue(only().isAllowWhileIdle)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        set(FIRST)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, only().windowLengthMs)
        assertTrue(only().isAllowWhileIdle)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        set(FIRST)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, only().windowLengthMs)
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12, which has no such access, the alarm is exact`() {
        set(FIRST)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, only().windowLengthMs)
        assertTrue(only().isAllowWhileIdle)
    }

    @Test
    fun `the alarm that fires starts the alarm's run`() {
        set(FIRST)
        system.fireAlarm(only())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(RunTrigger.Alarm), application.runs.started.toList())
    }

    @Test
    fun `a change of the time zone or of the clock sets no other alarm and starts no run`() {
        set(FIRST)
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        for (action in listOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED)) {
            assertEquals(action, emptyList<String>(), receiversOf(action))
            application.sendBroadcast(Intent(action))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(FIRST.toEpochMilli(), only().triggerAtMs)
        assertEquals(emptyList<RunTrigger>(), application.runs.started.toList())
    }

    // The app's own receivers that the manifest gives [action] to.
    private fun receiversOf(action: String): List<String> =
        application.packageManager
            .queryBroadcastReceivers(Intent(action).setPackage(application.packageName), 0)
            .map { it.activityInfo.name }
            .filter {
                it.startsWith(
                    RunReceiver::class.java.`package`!!
                        .name
                        .substringBeforeLast('.'),
                )
            }

    private companion object {
        val FIRST: Instant = Instant.parse("2031-04-05T15:30:00Z")
        val LATER: Instant = Instant.parse("2031-04-12T15:30:00Z")
    }
}
