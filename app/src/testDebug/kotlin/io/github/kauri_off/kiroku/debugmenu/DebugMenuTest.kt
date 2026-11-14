package io.github.kauri_off.kiroku.debugmenu

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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.scenario.ResetState
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.ScenarioTile
import io.github.kauri_off.kiroku.scenario.StoreFault
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.scenario.scenarios
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.SourceChoice
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.source.runningSource
import io.github.kauri_off.kiroku.wiring.DemoGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

// The menu switches the demo's scenarios by touch, keeps them across a rotation, resets the demo, chooses the source, names the build and opens the gallery.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class DebugMenuTest {
    @get:Rule(order = 0)
    val source = StartsOn(DataSource.Demo)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<DebugMenuActivity>()

    private val context get() = RuntimeEnvironment.getApplication()
    private val demo get() = (context.appGraph as DemoGraph).demo
    private val scenarios get() = checkNotNull(context.scenarios)

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

        compose.onNodeWithText("Reset").performScrollTo().performClick()
        compose.waitUntil(RESET_TIMEOUT) { scenarios.reset.value == ResetState.Idle }
        compose.onNodeWithText("Offline").performScrollTo().assertIsOff()
        assertFalse(demo.server.offline.value || demo.server.refusingWrites.value || demo.store.unreadable.value)
    }

    @Test
    fun `while reset waits for a save on its way, the switches cannot be touched and the panel says why`() {
        compose.onNodeWithText("Slow").performClick()
        runBlocking { demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch)) }

        compose.onNodeWithText("Reset").performScrollTo().performClick()
        compose.onNodeWithText("Waiting for MyAnimeList to answer a save, then resetting").assertExists()
        compose.onNodeWithText("Offline").assertIsNotEnabled()
        compose.onNodeWithText("Refused").assertIsNotEnabled()
        compose.onNodeWithText("Reset").assertIsNotEnabled()

        compose.waitUntil(RESET_TIMEOUT) { scenarios.reset.value == ResetState.Idle }
        compose.onNodeWithText("Offline").assertIsEnabled()
        compose.onNodeWithText("Slow").assertIsOff()
    }

    @Test
    fun `a source chosen by touch is kept for the next start, across a rotation, while the app stays on the one it started on`() {
        compose.onNodeWithText("Restart").assertDoesNotExist()
        compose.onNodeWithText("MyAnimeList").performScrollTo().performClick()
        assertEquals(DataSource.Account, SourceChoice(context).chosen())
        compose.onNodeWithText("MyAnimeList").assertIsOn()
        compose.onNodeWithText("Still on demo data until the app starts again").assertExists()
        compose.onNodeWithText("Restart").assertExists()
        assertEquals(DataSource.Demo, context.runningSource)
        compose.onNodeWithText("Offline").performScrollTo().assertIsEnabled()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("MyAnimeList").performScrollTo().assertIsOn()
        compose.onNodeWithText("Restart").assertExists()

        compose.onNodeWithText("Demo").performClick()
        assertEquals(DataSource.Demo, SourceChoice(context).chosen())
        compose.onNodeWithText("Restart").assertDoesNotExist()
    }

    @Test
    fun `the build section names the build type, the version, and the commit and build time the package names`() {
        val build = BuildInfo.read()
        compose.onNodeWithText(BuildConfig.BUILD_TYPE).performScrollTo().assertExists()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
        val commit = build.commit
        when {
            commit == null -> compose.onNodeWithText("Not known").assertExists()
            commit.dirty -> compose.onNodeWithText("${commit.hash}, with uncommitted changes").assertExists()
            else -> compose.onNodeWithText(commit.hash).assertExists()
        }
    }

    @Test
    fun `the gallery opens from the menu`() {
        compose.onNodeWithText("Components").performScrollTo().performClick()
        assertEquals(GalleryActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)
    }

    @Test
    fun `the back arrow closes the menu`() {
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `the menu opens from the app, from the launcher's shortcut and from the system's quick settings, and nowhere else`() {
        val manager = context.packageManager
        val menu = manager.getActivityInfo(compose.activity.componentName, 0)
        assertFalse(menu.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, menu.launchMode)
        assertFalse(manager.getActivityInfo(ComponentName(context, GalleryActivity::class.java), 0).exported)
        val tiles =
            manager.queryIntentServices(
                Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
                PackageManager.GET_META_DATA,
            )
        val tile = tiles.single().serviceInfo
        assertEquals(ScenarioTile::class.java.name, tile.name)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)

        val launcher = manager.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(R.xml.debug_menu_shortcuts_debug, launcher.metaData.getInt("android.app.shortcuts"))
        val intents = shortcutIntents()
        assertEquals(
            listOf(context.packageName to MainActivity::class.java.name, context.packageName to DebugMenuActivity::class.java.name),
            intents.map { it.target },
        )
    }

    // The preview build shares the debug source set; each build type's shortcut targets its own package.
    @Test
    fun `the preview build's shortcut targets the preview package, as the debug build's targets the debug package`() {
        val base = context.packageName.removeSuffix(".debug")
        val preview = shortcutIntents(R.xml.debug_menu_shortcuts_preview)
        assertEquals(listOf("$base.preview", "$base.preview"), preview.map { it.target.first })
        assertEquals(shortcutIntents().map { it.target.second }, preview.map { it.target.second })
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
    private fun shortcutIntents(shortcuts: Int = R.xml.debug_menu_shortcuts_debug): List<ShortcutIntent> {
        val android = "http://schemas.android.com/apk/res/android"
        val parser = context.resources.getXml(shortcuts)
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
