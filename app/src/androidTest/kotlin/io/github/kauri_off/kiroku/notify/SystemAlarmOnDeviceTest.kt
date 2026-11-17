package io.github.kauri_off.kiroku.notify

import android.app.AlarmManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.AlarmManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

// The alarm on a device's own alarm service, read back from the system's dump of it (section 5.1, N8).
class SystemAlarmOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as RecordedRunsApplication
    private val alarm = SystemAlarm(application)

    // What the system holds of the app's alarm.
    private data class Pending(
        val at: Instant,
        val exact: Boolean,
        val whileIdle: Boolean,
        val wakes: Boolean,
    )

    @After
    fun none() = runBlocking { alarm.set(null) }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    // The pending alarms of this app: each starts on a line that ends with the package, and its values follow it.
    private fun pending(): List<Pending> {
        val lines = shell("dumpsys alarm").lines()
        return lines.indices
            .filter { ALARM.containsMatchIn(lines[it]) && lines[it].trimEnd().endsWith(" ${application.packageName}}") }
            .map { at ->
                val values =
                    (
                        listOf(
                            lines[at],
                        ) + lines.drop(at + 1).take(VALUES).takeWhile { !ALARM.containsMatchIn(it) }
                    ).joinToString(" ")
                val flags = checkNotNull(FLAGS.find(values)) { values }.groupValues[1].toInt(HEX)
                Pending(
                    at = Instant.ofEpochMilli(checkNotNull(WHEN.find(lines[at])) { lines[at] }.groupValues[1].toLong()),
                    exact = flags and STANDALONE != 0,
                    whileIdle = flags and WHILE_IDLE != 0,
                    wakes = "RTC_WAKEUP" in values,
                )
            }
    }

    private fun exactAllowed() =
        AlarmManagerCompat.canScheduleExactAlarms(checkNotNull(application.getSystemService(AlarmManager::class.java)))

    private fun next(): RunTrigger = runBlocking { withTimeout(FIRES_WITHIN) { application.started.receive() } }

    @Test
    fun theAlarmSetIsTheOneAskedForAndTakesThePlaceOfTheOneBefore() =
        runBlocking {
            val first = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            alarm.set(first)
            assertEquals(listOf(Pending(first, exact = exactAllowed(), whileIdle = true, wakes = true)), pending())
            val later = first.plus(Duration.ofDays(7))
            alarm.set(later)
            assertEquals(listOf(Pending(later, exact = exactAllowed(), whileIdle = true, wakes = true)), pending())
            alarm.set(null)
            assertEquals(emptyList<Pending>(), pending())
        }

    @Test
    fun theAlarmFiresAndStartsTheAlarmsRun() {
        val at = Instant.now().plusSeconds(SOON)
        runBlocking { alarm.set(at) }
        assertNull("a run before the alarm's time", application.started.tryReceive().getOrNull())
        assertEquals(RunTrigger.Alarm, next())
        assertFalse("fired before its time", Instant.now().isBefore(at))
        assertEquals(emptyList<Pending>(), pending())
    }

    @Test
    fun theAccessGivenStartsARunAndTheNextAlarmIsExact() {
        assumeTrue("the access exists from Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assumeTrue("the access is not given yet", !exactAllowed())
        val at = Instant.now().plus(Duration.ofDays(2))
        runBlocking { alarm.set(at) }
        assertFalse(pending().single().exact)
        shell("cmd appops set ${application.packageName} SCHEDULE_EXACT_ALARM allow")
        assertEquals(RunTrigger.AlarmAccessChanged, next())
        assertTrue(exactAllowed())
        runBlocking { alarm.set(at) }
        assertTrue(pending().single().exact)
    }

    private companion object {
        val ALARM = Regex("""Alarm\{""")
        val WHEN = Regex("""\b(?:origWhen|when) (\d+)""")
        val FLAGS = Regex("""flags=0x([0-9a-fA-F]+)""")
        const val VALUES = 6
        const val HEX = 16

        // The alarm service's own marks: an alarm with no window, and one that may fire while the device idles.
        const val STANDALONE = 0x1
        const val WHILE_IDLE = 0x4 or 0x20
        const val SOON = 8L
        val FIRES_WITHIN = 60.seconds
    }
}
