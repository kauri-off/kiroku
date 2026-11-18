package io.github.kauri_off.kiroku.settings

import android.app.NotificationManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsKey
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class SettingsRouteTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private lateinit var navigator: ShellNavigator
    private val app = RuntimeEnvironment.getApplication()

    private val restoration = StateRestorationTester(compose)

    @Test
    fun `the Profile's settings lead to the notification settings, and Back returns the same way to the Profile`() {
        restoration.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, SnackbarHostState())
            }
        }
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Notifications").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Destination.Profile.root, SettingsKey, NotifySettingsKey), navigator.stackOf(Destination.Profile))
        compose.onNodeWithText("Send a test notification", useUnmergedTree = true).assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Destination.Profile.root, SettingsKey), navigator.stackOf(Destination.Profile))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Destination.Profile.root), navigator.stackOf(Destination.Profile))
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    // A rotation: the composition goes and comes back from its saved state, with the view models kept.
    @Test
    fun `across a rotation the notification settings stay open, with the setting as stored and the device read again`() {
        restoration.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, SnackbarHostState())
            }
        }
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Notifications").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Also for planned titles").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Also for planned titles").assertIsOn()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(listOf(Destination.Profile.root, SettingsKey, NotifySettingsKey), navigator.stackOf(Destination.Profile))
        compose.onNodeWithText("Also for planned titles").assertIsOn()
        compose.onNodeWithText("Blocked in Android settings").assertExists()
        assertTrue(runBlocking { graph.notifications.observeSettings().first() }.alsoPlanned)
    }
}
