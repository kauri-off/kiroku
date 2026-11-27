package io.github.kauri_off.kiroku.settings

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.model.StartupTab
import io.github.kauri_off.kiroku.core.model.ThemeMode
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.link.AppLinks
import io.github.kauri_off.kiroku.link.LinksOwner
import io.github.kauri_off.kiroku.notify.tapIntent
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class StartupPreferencesApplication :
    Application(),
    AppGraphOwner,
    LinksOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val ready = MutableStateFlow(false)
    val test = TestGraph(Demo(Fixtures.reference, scope))
    override val links = AppLinks()
    override val graph =
        object : AppGraph by test {
            override val preferences =
                object : PreferencesRepository by test.preferences {
                    override fun observeDisplayPreferences() =
                        flow {
                            ready.first { it }
                            emitAll(test.preferences.observeDisplayPreferences())
                        }
                }
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = StartupPreferencesApplication::class, sdk = [34], qualifiers = "en-w411dp-h891dp-notnight")
class StartupPreferencesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as StartupPreferencesApplication
    private val title = FixtureAnime.exactToWatch

    @After
    fun close() {
        app.scope.cancel()
    }

    @Test
    fun `a cold launch waits for the preferred tab and Back still goes to List`() {
        runBlocking { app.graph.preferences.setStartupTab(StartupTab.Search) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("My list").assertDoesNotExist()
            assertEquals(0, app.test.wholeListReads)
            app.ready.value = true
            tab("Search").assertIsSelected()
            compose.onNodeWithText("My list").assertDoesNotExist()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            tab("List").assertIsSelected()
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                assertTrue(it.isFinishing)
            }
        }
    }

    @Test
    fun `a settings save takes effect on the next cold launch and recreation restores the active stack`() {
        app.ready.value = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tab("List").assertIsSelected()
            tab("Profile").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Startup tab").performClick()
            compose.onNode(hasText("Search") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("Startup tab").assertExists()
            scenario.recreate()
            compose.onNodeWithText("Startup tab").assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            tab("Search").assertIsSelected()
        }
        runBlocking { app.graph.preferences.setStartupTab(StartupTab.Profile) }
        ActivityScenario.launch(MainActivity::class.java).use {
            tab("Profile").assertIsSelected()
        }
    }

    @Test
    fun `a notification at launch and a link while waiting each open over List`() {
        runBlocking { app.graph.preferences.setStartupTab(StartupTab.Profile) }
        ActivityScenario.launch<MainActivity>(tapIntent(app, title.id)).use { scenario ->
            assertTitleShown()
            assertEquals(0, app.test.wholeListReads)
            app.ready.value = true
            assertTitleShown()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            tab("List").assertIsSelected()
        }
        app.ready.value = false
        val previousReads = app.test.wholeListReads
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tab("Profile").assertDoesNotExist()
            app.links.opened(Link.Title(title.id))
            assertTitleShown()
            assertEquals(previousReads, app.test.wholeListReads)
            app.ready.value = true
            assertTitleShown()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            tab("List").assertIsSelected()
        }
    }

    @Test
    fun `the stored theme updates both system bar appearances in place`() {
        runBlocking { app.graph.preferences.setTheme(ThemeMode.PureBlack) }
        app.ready.value = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tab("List").assertIsSelected()
            assertLightBars(scenario, lightIcons = true)
            runBlocking { app.graph.preferences.setTheme(ThemeMode.Light) }
            compose.waitForIdle()
            assertLightBars(scenario, lightIcons = false)
            tab("List").assertIsSelected()
        }
    }

    private fun assertLightBars(
        scenario: ActivityScenario<MainActivity>,
        lightIcons: Boolean,
    ) {
        scenario.onActivity {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            assertEquals(!lightIcons, bars.isAppearanceLightStatusBars)
            assertEquals(!lightIcons, bars.isAppearanceLightNavigationBars)
        }
    }

    private fun assertTitleShown() {
        compose.onNode(isHeading() and hasText(title.titles.romaji)).assertExists()
        compose.onNodeWithText("My list").assertDoesNotExist()
    }

    private fun tab(name: String) = compose.onNode(hasText(name) and hasClickAction())
}
