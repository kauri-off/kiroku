package io.github.kauri_off.kiroku.scenario

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The marker is over the app exactly while a scenario is on; with all off the app's views are those of a release build.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ScenarioOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val scenarios get() = checkNotNull(RuntimeEnvironment.getApplication().scenarios)

    @Before
    fun demoMode() = assumeTrue(BuildConfig.MAL_CLIENT_ID.isBlank())

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun `the marker shows while a scenario is on, is spoken with their names, and opens the panel`() {
        settle()
        assertNull(ScenarioOverlay.marker(compose.activity))

        scenarios.set(Scenario(offline = true, slow = true))
        settle()
        assertNotNull(ScenarioOverlay.marker(compose.activity))
        compose.onNodeWithContentDescription("Simulated: Offline\u00A0· Slow").performClick()
        assertEquals(ScenarioActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)

        compose.activityRule.scenario.recreate()
        settle()
        assertNotNull(ScenarioOverlay.marker(compose.activity))

        scenarios.set(Scenario())
        settle()
        assertNull(ScenarioOverlay.marker(compose.activity))
    }
}
