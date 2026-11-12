package io.github.kauri_off.kiroku.scenario

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The panel switches the demo's scenarios by touch, keeps them across a rotation, and resets the demo.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ScenarioPanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ScenarioActivity>()

    private val demo get() = (RuntimeEnvironment.getApplication().appGraph as DemoGraph).demo
    private val scenarios get() = checkNotNull(RuntimeEnvironment.getApplication().scenarios)

    @Before
    fun demoMode() = assumeTrue(BuildConfig.MAL_CLIENT_ID.isBlank())

    @Test
    fun `a switch applies at once, survives a rotation, and reset turns it off`() {
        compose.onNodeWithText("Offline").performClick()
        assertTrue(demo.server.offline.value)
        compose.onNodeWithText("Refused").performClick()
        assertTrue(demo.server.refusingWrites.value)
        compose.onNodeWithText("Unreadable").performClick()
        assertTrue(demo.store.unreadable.value)

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Offline").assertIsOn()
        assertEquals(Scenario(offline = true, writes = WriteFault.Refused, store = StoreFault.Unreadable), scenarios.scenario.value)

        compose.onNodeWithText("Reset").performClick()
        compose.waitUntil(RESET_TIMEOUT) { !scenarios.resetting.value }
        compose.onNodeWithText("Offline").assertIsOff()
        assertFalse(demo.server.offline.value || demo.server.refusingWrites.value || demo.store.unreadable.value)
    }

    @Test
    fun `the panel opens only from the app and the system's quick settings`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.packageManager
        val panel = manager.getActivityInfo(compose.activity.componentName, 0)
        assertFalse(panel.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, panel.launchMode)
        val tiles =
            manager.queryIntentServices(
                Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
                PackageManager.GET_META_DATA,
            )
        val tile = tiles.single().serviceInfo
        assertEquals(ScenarioTile::class.java.name, tile.name)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
    }

    private companion object {
        const val RESET_TIMEOUT = 5_000L
    }
}
