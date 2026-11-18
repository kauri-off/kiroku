package io.github.kauri_off.kiroku.notify

import android.app.Activity
import android.app.AlarmManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.InitializationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.kauri_off.kiroku.core.notify.Coming
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// The application itself on the demo: what its start leaves alone, and the run its opening starts.
@RunWith(RobolectricTestRunner::class)
class RunsOfTheAppTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val source = StartsOn(DataSource.Demo)

    private val application get() = RuntimeEnvironment.getApplication()
    private val demo get() = (application.appGraph as DemoGraph).demo

    private fun alarms() = shadowOf(application.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test
    fun `the app's opening starts a run of its graph's engine, and the demo's alarm is not the system's`() {
        runBlocking { assertNull("a run before the app is opened", demo.notifications.observeComing().first()) }
        // A device runs the start-up provider, which tells the app's lifecycle, before the application; a test has to ask for it.
        Robolectric.setupContentProvider(InitializationProvider::class.java, application.packageName + STARTUP)
        val opened = Robolectric.buildActivity(Activity::class.java).setup()
        val coming = MutableStateFlow<List<Coming>?>(null)
        val read = CoroutineScope(Dispatchers.Default).launch { coming.value = demo.notifications.observeComing().first { it != null } }
        val limit = TimeSource.Monotonic.markNow() + WITHIN
        while (coming.value == null && limit.hasNotPassedNow()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(PAUSE)
        }
        read.cancel()
        // The app's lifecycle is the test process's: left in the foreground, it would open the app of every later test at its start.
        opened.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(LEFT)
        assertEquals(Lifecycle.State.CREATED, ProcessLifecycleOwner.get().lifecycle.currentState)
        assertTrue(checkNotNull(coming.value) { "no run has ended" }.isNotEmpty())
        // The demo is gone with its process, and so is its alarm: none is left to start a process whose demo has nothing due.
        assertEquals(0, alarms().size)
    }

    @Test
    fun `scheduled work is set up at its first use and not when the process starts`() {
        assertFalse(WorkManager.isInitialized())
        assertNotNull((application as Configuration.Provider).workManagerConfiguration)
    }

    private companion object {
        val WITHIN = 20.seconds
        const val PAUSE = 20L
        val LEFT: Duration = Duration.ofSeconds(2)
        const val STARTUP = ".androidx-startup"
    }
}
