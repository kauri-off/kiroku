package io.github.kauri_off.kiroku.settings

import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.notifysettings.NotifySettingsKey
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    @Config(sdk = [34])
    fun `settings store both choices and open Android app language settings for this package`() {
        restoration.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SettingsRoute(graph, ShellMessages(scope, graph.list), SnackbarHostState(), {}, {}, {})
            }
        }
        compose.onNodeWithText("Romaji").assertExists()
        compose.onNodeWithText("Show adult content in search").assertIsOff()
        compose.onNodeWithText("Title language").performClick()
        compose.onNodeWithText("Japanese").performClick()
        compose.onNodeWithText("Japanese").assertExists()
        compose.onNodeWithText("Show adult content in search").performClick()
        compose.onNodeWithText("Show adult content in search").assertIsOn()
        assertEquals(TitleLanguage.Japanese, runBlocking { graph.titleLanguage.first() })
        assertTrue(runBlocking { graph.showAdultInSearch.first() })

        compose.onNodeWithText("Language").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    fun `two switch taps before the first write returns are both applied`() {
        val release = CompletableDeferred<Unit>()
        var toggles = 0
        val source =
            object : AppGraph by graph {
                override val preferences =
                    object : PreferencesRepository by graph.preferences {
                        override suspend fun changeAdultInSearch(change: (Boolean) -> Boolean): Outcome<Unit> {
                            toggles++
                            release.await()
                            return graph.preferences.changeAdultInSearch(change)
                        }
                    }
            }
        restoration.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SettingsRoute(source, ShellMessages(scope, graph.list), SnackbarHostState(), {}, {}, {})
            }
        }
        val adult = compose.onNodeWithText("Show adult content in search")
        adult.assertIsOff()
        try {
            adult.performClick().assertIsOff()
            adult.performClick().assertIsOff()
            compose.waitForIdle()
            assertEquals(2, toggles)
        } finally {
            release.complete(Unit)
        }
        compose.waitForIdle()
        adult.assertIsOff()
        assertEquals(2L, runBlocking { graph.adultSearchChoice.first() }.revision)
    }

    @Test
    @Config(sdk = [32])
    fun `Android 12 shows the title language but offers no app language destination`() {
        restoration.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SettingsRoute(graph, ShellMessages(scope, graph.list), SnackbarHostState(), {}, {}, {})
            }
        }
        compose.onNodeWithText("Language").assertDoesNotExist()
        compose.onNodeWithText("Title language").assertExists()
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
