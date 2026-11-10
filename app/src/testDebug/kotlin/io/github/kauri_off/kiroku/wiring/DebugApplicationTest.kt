package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugApplicationTest {
    @Test
    fun `the application is in demo mode exactly when this build has no client id`() {
        val graph = RuntimeEnvironment.getApplication().appGraph
        assertEquals(BuildConfig.MAL_CLIENT_ID.isBlank(), graph is DemoGraph)
    }
}
