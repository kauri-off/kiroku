package io.github.kauri_off.kiroku.scenario

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

// The panel switches the demo's scenarios by touch, keeps them across a rotation, and resets the demo.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ScenarioPanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ScenarioActivity>()

    private val context get() = RuntimeEnvironment.getApplication()
    private val demo get() = (context.appGraph as DemoGraph).demo
    private val scenarios get() = checkNotNull(context.scenarios)

    private fun demoMode() = assumeTrue(BuildConfig.MAL_CLIENT_ID.isBlank())

    @Test
    fun `a switch applies at once, survives a rotation, and reset turns it off`() {
        demoMode()
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
        compose.waitUntil(RESET_TIMEOUT) { scenarios.reset.value == ResetState.Idle }
        compose.onNodeWithText("Offline").assertIsOff()
        assertFalse(demo.server.offline.value || demo.server.refusingWrites.value || demo.store.unreadable.value)
    }

    @Test
    fun `while reset waits for a save on its way, the switches cannot be touched and the panel says why`() {
        demoMode()
        compose.onNodeWithText("Slow").performClick()
        runBlocking { demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)) }

        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Waiting for MyAnimeList to answer a save, then resetting").assertExists()
        compose.onNodeWithText("Offline").assertIsNotEnabled()
        compose.onNodeWithText("Refused").assertIsNotEnabled()
        compose.onNodeWithText("Reset").assertIsNotEnabled()

        compose.waitUntil(RESET_TIMEOUT) { scenarios.reset.value == ResetState.Idle }
        compose.onNodeWithText("Offline").assertIsEnabled()
        compose.onNodeWithText("Slow").assertIsOff()
    }

    @Test
    fun `the panel opens from the app, from the launcher's shortcut and from the system's quick settings, and nowhere else`() {
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

        val launcher = manager.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(R.xml.scenario_shortcuts, launcher.metaData.getInt("android.app.shortcuts"))
        val intents = shortcutIntents()
        assertEquals(
            listOf(context.packageName to MainActivity::class.java.name, context.packageName to ScenarioActivity::class.java.name),
            intents.map { it.target },
        )
    }

    // Otherwise a later tap on the icon starts a second app screen, without the first one's messages.
    @Test
    fun `the task the launcher's shortcut starts is the one the app's icon returns to`() {
        val icon = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val root = shortcutIntents().first()
        assertEquals(icon.action, root.action)
        assertEquals(icon.categories, root.categories)
        assertEquals(icon.component?.className, root.target.second)
    }

    private class ShortcutIntent(
        val target: Pair<String, String>,
        val action: String,
        val categories: MutableSet<String> = mutableSetOf(),
    )

    // Each intent of the one shortcut, in the order the launcher starts them.
    private fun shortcutIntents(): List<ShortcutIntent> {
        val android = "http://schemas.android.com/apk/res/android"
        val parser = context.resources.getXml(R.xml.scenario_shortcuts)
        val intents = mutableListOf<ShortcutIntent>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "intent" ->
                    intents +=
                        ShortcutIntent(
                            parser.getAttributeValue(android, "targetPackage") to parser.getAttributeValue(android, "targetClass"),
                            parser.getAttributeValue(android, "action"),
                        )
                "categories" -> intents.last().categories += parser.getAttributeValue(android, "name")
            }
        }
        return intents
    }

    private companion object {
        const val RESET_TIMEOUT = 15_000L
    }
}
