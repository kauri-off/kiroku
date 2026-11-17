package io.github.kauri_off.kiroku.wiring

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
}
