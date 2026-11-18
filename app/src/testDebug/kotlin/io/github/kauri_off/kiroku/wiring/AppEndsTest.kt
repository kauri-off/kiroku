package io.github.kauri_off.kiroku.wiring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kauri_off.kiroku.notify.TestScheduledWork
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

// The rule every test that starts the app ends it with: the app's work ends with the test, whoever still listens to it.
@RunWith(RobolectricTestRunner::class)
class AppEndsTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val source = StartsOn(DataSource.Demo)

    private val app get() = RuntimeEnvironment.getApplication()

    private fun clockSet() = shadowOf(app).registeredReceivers.count { receiver -> CLOCK_SET.any(receiver.intentFilter::hasAction) }

    @Test
    fun `the app's work ends with the test, also under a listener that outlives it`() {
        val listener = CoroutineScope(Dispatchers.Default)
        try {
            val test =
                object : Statement() {
                    override fun evaluate() {
                        listener.launch {
                            app.appGraph.airing
                                .observeAiring()
                                .collect {}
                        }
                        runBlocking { withTimeout(10.seconds) { while (clockSet() == 0) delay(10) } }
                    }
                }
            AppEnds().apply(test, Description.EMPTY).evaluate()
            assertEquals(0, clockSet())
        } finally {
            listener.cancel()
        }
    }

    @Test
    fun `a report the debug build makes loudly as the test ends fails that test, not the next`() {
        val test =
            object : Statement() {
                override fun evaluate() = reportUnexpected(IllegalArgumentException())
            }
        val thrown = assertThrows(IllegalStateException::class.java) { AppEnds().apply(test, Description.EMPTY).evaluate() }
        assertTrue(thrown.cause is IllegalArgumentException)
    }

    @Test
    fun `scheduled work the app set up for itself fails that test alone, and the next test finds none`() {
        val test =
            object : Statement() {
                override fun evaluate() {
                    WorkManager.getInstance(app)
                }
            }
        assertThrows(AssertionError::class.java) { AppEnds().apply(test, Description.EMPTY).evaluate() }
        assertFalse(WorkManager.isInitialized())
    }

    // Work that ends after its WorkManager is gone reads a store that is gone, and fails the next test that runs coroutines.
    @Test
    fun `work still running when the test ends has ended before its scheduled work goes`() {
        SlowWorker.ended = false
        val test =
            object : Statement() {
                override fun evaluate() {
                    TestScheduledWork.install(app)
                    WorkManager
                        .getInstance(app)
                        .enqueue(OneTimeWorkRequestBuilder<SlowWorker>().build())
                        .result
                        .get()
                }
            }
        AppEnds().apply(test, Description.EMPTY).evaluate()
        assertTrue(SlowWorker.ended)
        assertFalse(WorkManager.isInitialized())
    }
}

class SlowWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        delay(SLOW)
        ended = true
        return Result.success()
    }

    companion object {
        @Volatile
        var ended = false
        private const val SLOW = 300L
    }
}
