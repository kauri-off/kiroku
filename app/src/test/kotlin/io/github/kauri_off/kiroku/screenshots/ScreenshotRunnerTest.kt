package io.github.kauri_off.kiroku.screenshots

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith

class ScreenshotRunnerTest {
    // What a test of the screenshot sandbox leaves on the main looper runs before the next test, also after a failure.
    @Test
    fun everyTestLeavesNothingOnTheMainLooperEvenWhenItFails() {
        System.setProperty(PROBING, "on")
        System.clearProperty(RAN)
        try {
            val result = JUnitCore().run(ScreenshotRunnerProbe::class.java)
            assertEquals(listOf("leavesWorkAndFails"), result.failures.map { it.description.methodName })
            assertEquals("2", System.getProperty(RAN))
        } finally {
            System.clearProperty(PROBING)
            System.clearProperty(RAN)
        }
    }
}

// Run only by the test above; on its own it posts nothing.
@RunWith(ScreenshotRunner::class)
class ScreenshotRunnerProbe {
    @Test
    fun leavesWork() = post()

    @Test
    fun leavesWorkAndFails() {
        post()
        check(System.getProperty(PROBING) == null) { "fails as planned" }
    }

    private fun post() {
        if (System.getProperty(PROBING) == null) return
        Handler(Looper.getMainLooper()).post { System.setProperty(RAN, ((System.getProperty(RAN)?.toInt() ?: 0) + 1).toString()) }
    }
}

private const val PROBING = "kiroku.screenshotRunnerProbe"
private const val RAN = "kiroku.screenshotRunnerProbe.ran"
