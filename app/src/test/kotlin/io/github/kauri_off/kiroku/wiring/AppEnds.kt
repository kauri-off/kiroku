package io.github.kauri_off.kiroku.wiring

import android.os.Looper
import io.github.kauri_off.kiroku.KirokuApplication
import io.github.kauri_off.kiroku.reports.reportLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

// Ends the test's app as its process would, so nothing of it acts on the next test's application.
class AppEnds : ExternalResource() {
    override fun after() {
        val app = RuntimeEnvironment.getApplication()
        (app as? KirokuApplication)?.let { runBlocking { withTimeout(WAIT) { it.endWork() } } }
        reportLog.forget()
        shadowOf(Looper.getMainLooper()).idle()
        val clockSet = shadowOf(app).registeredReceivers.filter { receiver -> CLOCK_SET.any(receiver.intentFilter::hasAction) }
        assertEquals(emptyList<Any>(), clockSet)
    }

    companion object {
        // Every rule of the test is inside this one, so the app ends after its activities.
        const val OUTERMOST = Int.MIN_VALUE

        private val WAIT = 10.seconds
    }
}
