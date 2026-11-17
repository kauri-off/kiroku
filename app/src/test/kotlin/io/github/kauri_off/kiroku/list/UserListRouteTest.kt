package io.github.kauri_off.kiroku.list

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.profile.OtherProfileKey
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Another user's list as the Profile's "View list" opens it, over demo mode.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class UserListRouteTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val fixtures = Fixtures.reference
    private lateinit var navigator: ShellNavigator

    private val requests get() = graph.demo.userLists.requests.size
    private val airingRequests get() = graph.demo.airingSource.requests.size

    private fun profile() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                navigator = rememberShellNavigator()
                AppRoute(graph, navigator, SnackbarHostState())
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.runOnUiThread { navigator.open(OtherProfileKey(fixtures.otherUserName)) }
        compose.waitForIdle()
    }

    private fun viewList() {
        compose.onNodeWithContentDescription("View list").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun stepperShows(count: Int) =
        compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched")).assert(hasText(count.toString()))

    @Test
    fun `opened after the profile, the list causes no request and asks AniList for nothing`() {
        profile()
        val read = requests
        val airing = airingRequests
        viewList()
        compose.onNodeWithText("${fixtures.otherUserName}'s list").assertExists()
        compose.onNodeWithText(FixtureAnime.longTitle.titles.romaji).assertExists()
        assertEquals(read, requests)
        assertEquals(airing, airingRequests)
    }

    // The other user watched 9 episodes of it, the person 12: the page is the person's from its first frame.
    @Test
    fun `a row opens its title with the person's own entry`() {
        profile()
        viewList()
        graph.readsHeld = true
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji).performClick()
        compose.waitForIdle()
        stepperShows(12)
        graph.readsHeld = false
        compose.waitForIdle()
        stepperShows(12)
    }

    // The demo's made-up titles have a page of their own, which offers to add them.
    @Test
    fun `a title only on the other user's list opens its page with nothing of the person's list`() {
        profile()
        viewList()
        val madeUp = fixtures.otherUserList.last { it.status == ListStatus.Watching }.anime
        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(madeUp.titles.romaji)
        compose.waitForIdle()
        compose.onNode(hasText(madeUp.titles.romaji) and hasClickAction() and !hasSetTextAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add to list").assertExists()
        compose.onNodeWithText(madeUp.titles.romaji).assertExists()
    }
}
