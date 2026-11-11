package io.github.kauri_off.kiroku.routes

import androidx.activity.BackEventCompat
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The app's real entries over demo mode: what the owner does first on the device.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class AppRouteTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val snackbars = SnackbarHostState()
    private lateinit var navigator: ShellNavigator

    private val kagerou = FixtureAnime.exactToWatch

    @After
    fun stop() = scope.cancel()

    private fun app() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, snackbars)
            }
        }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    private fun showsSubstring(text: String) = compose.onNodeWithText(text, substring = true).assertExists()

    private fun hides(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performClick()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun values(anime: Anime): ListValues? =
        runBlocking {
            graph.list
                .observeEntry(anime.id)
                .first()
                ?.values
        }

    // The scrolling list of the screen on top; the one with [text] among its items.
    private fun scrollTo(text: String) =
        compose
            .onAllNodes(hasScrollAction() and hasScrollToNode())
            .filter(hasAnyDescendant(hasText("Episodes watched")) or hasAnyDescendant(hasText("Related")))[0]
            .performScrollToNode(hasText(text))

    private fun hasScrollToNode() = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)

    private fun openKagerou() {
        compose.onNodeWithText(kagerou.titles.romaji).performClick()
        compose.waitForIdle()
    }

    private fun plusOnFirstRow() = compose.onAllNodesWithContentDescription("Plus one episode")[0].performClick()

    @Test
    fun `the list opens on Watching with the demo titles`() {
        app()
        shows("My list")
        shows(kagerou.titles.romaji)
        shows("Watched 12\u00A0of\u00A024")
    }

    @Test
    fun `a row opens its title page and Back returns to the list`() {
        app()
        openKagerou()
        shows("Episodes watched")
        hides("My list")
        back()
        shows("My list")
    }

    @Test
    fun `plus one writes, says so and Undo puts the count back`() {
        app()
        plusOnFirstRow()
        compose.waitForIdle()
        shows("Watched 13\u00A0of\u00A024")
        shows("Episode\u00A013 watched")
        assertEquals(13, values(kagerou)?.watchedEpisodes)
        click("Undo")
        compose.waitForIdle()
        shows("Watched 12\u00A0of\u00A024")
        assertEquals(12, values(kagerou)?.watchedEpisodes)
    }

    @Test
    fun `a quickly typed count reaches the edit sheet keystroke by keystroke, and Save writes it`() {
        app()
        compose.onNodeWithText(kagerou.titles.romaji).performTouchInput { longClick() }
        compose.waitForIdle()
        shows("Save")
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched"))
        field.performClick()
        field.performTextClearance()
        field.performKeyInput {
            pressKey(Key.Two)
            pressKey(Key.Four)
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("24")).assertExists()
        back()
        click("Save")
        compose.waitForIdle()
        assertEquals(24, values(kagerou)?.watchedEpisodes)
        assertEquals(ListStatus.Completed, values(kagerou)?.status)
        hides(kagerou.titles.romaji)
    }

    @Test
    fun `the title page's status button opens the edit sheet, and a stepper change to the total says Moved to Completed`() {
        app()
        openKagerou()
        click("Watching")
        shows("Save")
        click("Completed")
        click("Save")
        compose.waitForIdle()
        assertEquals(ListStatus.Completed, values(kagerou)?.status)
        hides("Save")
        click("Completed")
        click("Watching")
        click("Save")
        compose.waitForIdle()
        assertEquals(ListStatus.Watching, values(kagerou)?.status)
        repeat(12) { compose.onNode(hasContentDescription("Plus one episode") and hasClickAction()).performClick() }
        compose.waitForIdle()
        shows("Moved to Completed")
        assertEquals(24, values(kagerou)?.watchedEpisodes)
        click("Undo")
        compose.waitForIdle()
        assertEquals(23, values(kagerou)?.watchedEpisodes)
        assertEquals(ListStatus.Watching, values(kagerou)?.status)
    }

    @Test
    fun `adding a title says Added to Planned, and Change opens its edit sheet`() {
        app()
        compose.runOnUiThread { navigator.open(TitleKey(FixtureAnime.notOnListAiring.id.value)) }
        compose.waitForIdle()
        click("Add to list")
        compose.waitForIdle()
        shows("Added to Planned")
        click("Change")
        compose.waitForIdle()
        shows("Save")
        assertEquals(ListStatus.Planned, values(FixtureAnime.notOnListAiring)?.status)
    }

    @Test
    fun `See all opens every related title, which opens its own page, and Details opens the details`() {
        app()
        openKagerou()
        scrollTo("See all")
        click("See all")
        compose.waitForIdle()
        shows("Related")
        shows("Prequel")
        compose.onNodeWithText(FixtureAnime.prequel.titles.romaji).performClick()
        compose.waitForIdle()
        shows("Episodes watched")
        back()
        back()
        scrollTo("Details")
        click("Details")
        compose.waitForIdle()
        shows("Details")
        showsSubstring("Studio Hanamizuki")
        back()
        scrollTo("Recommendations")
        click("Recommendations")
        compose.waitForIdle()
        shows("Not available in this build yet")
        back()
        back()
        shows("My list")
    }

    @Test
    fun `Search and Profile say they are not in this build`() {
        app()
        click("Search")
        shows("Not available in this build yet")
        click("Profile")
        shows("Not available in this build yet")
        back()
        shows("My list")
    }

    // S6 on the real screens: the List, Search, Profile, the title page and its sub-screens.
    @Test
    fun `every screen shows the session banner while the session is expired, and Sign in says it is not built`() {
        graph.demo.server.sessionExpired.value = true
        app()
        shows("Session expired")
        openKagerou()
        shows("Session expired")
        scrollTo("Details")
        click("Details")
        compose.waitForIdle()
        shows("Session expired")
        back()
        scrollTo("See all")
        click("See all")
        compose.waitForIdle()
        shows("Session expired")
        click("Sign in")
        compose.waitForIdle()
        shows("Not available in this build yet")
        back()
        back()
        back()
        listOf("Search", "Profile").forEach {
            click(it)
            shows("Session expired")
        }
    }

    @Test
    fun `no screen shows the banner while signed in`() {
        app()
        hides("Session expired")
        openKagerou()
        hides("Session expired")
        listOf("Search", "Profile").forEach {
            click(it)
            hides("Session expired")
        }
    }

    // W2 on the shell's host: the message outlives the screen that made the edit.
    @Test
    fun `a failed write says Could not save, and Retry writes the values shown before the failure`() {
        app()
        graph.demo.server.failingWrites.value = true
        plusOnFirstRow()
        compose.waitForIdle()
        openKagerou()
        shows("Could not save")
        assertEquals(12, values(kagerou)?.watchedEpisodes)
        graph.demo.server.failingWrites.value = false
        click("Retry")
        compose.waitForIdle()
        assertEquals(13, values(kagerou)?.watchedEpisodes)
    }

    @Test
    fun `the same title opened twice in one stack keeps two separate states`() {
        app()
        openKagerou()
        scrollTo("More")
        click("More")
        shows("Less")
        compose.runOnUiThread { navigator.open(TitleKey(kagerou.id.value)) }
        compose.waitForIdle()
        scrollTo("More")
        hides("Less")
        back()
        scrollTo("Less")
        shows("Less")
    }

    @Test
    fun `during a back gesture only the screen on top draws the snackbar`() {
        app()
        openKagerou()
        scope.launch { snackbars.showSnackbar("Message", duration = SnackbarDuration.Indefinite) }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        shows("My list")
        compose.onAllNodes(hasText("Message")).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
    }
}
