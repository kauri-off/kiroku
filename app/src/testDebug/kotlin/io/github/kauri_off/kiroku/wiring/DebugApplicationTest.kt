package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import io.github.kauri_off.kiroku.source.runningSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The application itself, which builds its graph when it is first asked for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugApplicationTest {
    private val application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the application starts on the demo when the demo was chosen`() {
        assertTrue(SourceChoice(application).choose(DataSource.Demo))
        assertTrue(application.appGraph is DemoGraph)
        assertEquals(DataSource.Demo, application.runningSource)
    }

    @Test
    fun `the application starts on the real account when that was chosen, and a later choice leaves it there`() {
        assertTrue(SourceChoice(application).choose(DataSource.Account))
        assertTrue(application.appGraph is LiveGraph)
        assertTrue(SourceChoice(application).choose(DataSource.Demo))
        assertTrue(application.appGraph is LiveGraph)
        assertEquals(DataSource.Account, application.runningSource)
    }
}
