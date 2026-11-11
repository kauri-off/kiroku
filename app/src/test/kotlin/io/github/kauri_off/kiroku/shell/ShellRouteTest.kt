package io.github.kauri_off.kiroku.shell

import androidx.activity.BackEventCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class ShellRouteTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Serializable
    private data class Page(
        val n: Int,
    ) : NavKey

    private val entries: ShellEntries = { navigator, _ ->
        entry<ListRoot> { Root("List root", navigator, Page(1)) }
        entry<SearchRoot> { Root("Search root", navigator, Page(2)) }
        entry<ProfileRoot> { Root("Profile root", navigator, Page(3)) }
        entry<Page> { page -> Counter("Page ${page.n}") }
    }

    @Composable
    private fun Root(
        name: String,
        navigator: ShellNavigator,
        next: NavKey,
    ) = Column {
        Text(name)
        Button(onClick = { navigator.open(next) }) { Text("Open") }
    }

    @Composable
    private fun Counter(name: String) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        Button(onClick = { taps++ }) { Text("$name, $taps taps") }
    }

    private fun shell(entries: ShellEntries = this.entries) =
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { ShellRoute(entries = entries) } }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    private fun hides(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    @Test
    fun tappingTheCurrentDestinationAgainPopsItToItsRoot() {
        shell()
        compose.onNodeWithText("Open").performClick()
        shows("Page 1, 0 taps")
        tab("List").assertIsSelected().performClick()
        shows("List root")
    }

    @Test
    fun backFromTheSearchRootGoesToList() {
        shell()
        tab("Search").performClick()
        shows("Search root")
        back()
        shows("List root")
        tab("List").assertIsSelected()
    }

    @Test
    fun backFromTheSearchRootPreviewsTheListAndACancelledGestureRestoresSearch() {
        shell()
        compose.onNodeWithText("Open").performClick()
        tab("Search").performClick()
        hides("Page 1, 0 taps")
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        shows("Page 1, 0 taps")
        shows("Search root")
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        hides("Page 1, 0 taps")
        shows("Search root")
        tab("Search").assertIsSelected()
    }

    @Test
    fun backFromTheListRootLeavesTheApp() {
        shell()
        assertFalse("before", compose.activity.isFinishing)
        tab("Profile").performClick()
        shows("Profile root")
        back()
        assertFalse("after", compose.activity.isFinishing)
        shows("List root")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun backInsideADestinationPopsIt() {
        shell()
        tab("Search").performClick()
        compose.onNodeWithText("Open").performClick()
        shows("Page 2, 0 taps")
        back()
        shows("Search root")
    }

    @Test
    fun eachDestinationKeepsItsStackAndItsScreensState() {
        shell()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Page 1, 0 taps").performClick()
        tab("Search").performClick()
        compose.onNodeWithText("Open").performClick()
        shows("Page 2, 0 taps")
        tab("List").performClick()
        shows("Page 1, 1 taps")
        tab("Search").performClick()
        shows("Page 2, 0 taps")
    }

    @Test
    fun theStacksAndTheScreensStateSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KirokuTheme(dark = false, dynamicColor = false) { ShellRoute(entries = entries) } }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Page 1, 0 taps").performClick()
        tab("Search").performClick()
        restoration.emulateSavedInstanceStateRestore()
        shows("Search root")
        tab("List").performClick()
        shows("Page 1, 1 taps")
    }

    // S6: every signed-in screen shows "Session expired · Sign in", and Sign in reaches the route.
    @Test
    fun everyDestinationShowsTheSessionBannerWhileTheSessionIsExpired() {
        var signIns = 0
        shell(placeholderEntries(sessionExpired = true, onSignIn = { signIns++ }))
        Destination.entries.forEachIndexed { index, destination ->
            tab(compose.activity.getString(destination.label)).performClick()
            shows("Session expired")
            compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
            assertEquals(destination.name, index + 1, signIns)
        }
    }

    @Test
    fun noDestinationShowsTheBannerWhileSignedIn() {
        shell(placeholderEntries(sessionExpired = false))
        Destination.entries.forEach { destination ->
            tab(compose.activity.getString(destination.label)).performClick()
            hides("Session expired")
        }
    }
}
