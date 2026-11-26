package io.github.kauri_off.kiroku.wiring

import android.os.Looper
import androidx.work.WorkManager
import io.github.kauri_off.kiroku.KirokuApplication
import io.github.kauri_off.kiroku.notify.TestScheduledWork
import io.github.kauri_off.kiroku.reports.reportLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

// Ends the test's app as its process would, so nothing of it acts on the next test's application.
class AppEnds : ExternalResource() {
    override fun after() {
        val app = RuntimeEnvironment.getApplication()
        val looper = shadowOf(Looper.getMainLooper())
        if (app is KirokuApplication) {
            runBlocking {
                withTimeout(WAIT) {
                    val ending = launch(start = CoroutineStart.UNDISPATCHED) { app.endWork() }
                    while (!ending.isCompleted) {
                        looper.idle()
                        delay(1)
                    }
                }
            }
        }
        reportLog.forget()
        looper.idle()
        val clockSet = shadowOf(app).registeredReceivers.filter { receiver -> CLOCK_SET.any(receiver.intentFilter::hasAction) }
        assertEquals(emptyList<Any>(), clockSet)
        TestScheduledWork.end()
        val leaked = WorkManager.isInitialized()
        if (leaked) TestScheduledWork.forgetLeaked()
        assertFalse("the app set up scheduled work of its own: give the test TestScheduledWork", leaked)
    }

    companion object {
        // Every rule of the test is inside this one, so the app ends after its activities.
        const val OUTERMOST = Int.MIN_VALUE

        private val WAIT = 10.seconds
    }
}
