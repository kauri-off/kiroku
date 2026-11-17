package io.github.kauri_off.kiroku.link

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.link.Link
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The system handed the app a link to a profile before its screen was made.
class StartedByProfileLinkApplication : LinkGraphApplication() {
    override fun onCreate() {
        super.onCreate()
        links.opened(Link.Profile(Fixtures.reference.otherUserName))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = LinkGraphApplication::class, qualifiers = "en-w411dp-h891dp")
class ProfileLinkRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as LinkGraphApplication
    private val set = Fixtures.reference
    private val other = set.otherUserName
    private val ownName = set.user.name

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    private fun hides(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    private fun click(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun link(name: String) {
        app.links.opened(Link.Profile(name))
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun recreate() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    // The profile of [name] is on top: its name is the bar's title, and neither the List nor the own profile shows.
    private fun onProfileOf(name: String) {
        shows(name)
        hides("My list")
        hides(ownName)
    }

    // Back shows the own Profile, then the List, and Back from the List leaves the app.
    private fun assertTheOwnProfileAndTheListAreBeneath(ownProfile: String = ownName) {
        back()
        shows(ownProfile)
        hides(other)
        back()
        shows("My list")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `signed in, a link opens its profile over the own Profile with the List beneath`() {
        shows("My list")
        link(other)
        onProfileOf(other)
        shows("Completed")
        assertTheOwnProfileAndTheListAreBeneath()
    }

    @Test
    fun `signed out, a link opens its profile`() {
        runBlocking { app.test.session.signOut() }
        compose.waitForIdle()
        link(other)
        onProfileOf(other)
        shows("Completed")
        assertTheOwnProfileAndTheListAreBeneath(ownProfile = "Sign in with MyAnimeList to see your profile here")
    }

    @Test
    fun `a link to the private user's profile says the list is private`() {
        link(set.privateUserName)
        onProfileOf(set.privateUserName)
        shows("This list is private")
        assertTheOwnProfileAndTheListAreBeneath()
    }

    @Test
    fun `with a title over the List and Search shown, a link opens its profile with the List alone beneath`() {
        app.links.opened(Link.Title(FixtureAnime.exactToWatch.id))
        compose.waitForIdle()
        click("Search")
        link(other)
        onProfileOf(other)
        assertTheOwnProfileAndTheListAreBeneath()
    }

    @Test
    fun `over another profile and the open dialog, a link opens its profile over the own Profile alone`() {
        click("Profile")
        click("Open another profile")
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
        link(set.privateUserName)
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        link(other)
        onProfileOf(other)
        assertTheOwnProfileAndTheListAreBeneath()
    }

    // A configuration change keeps the open dialog and its text; only a link that leaves the screen closes it.
    @Test
    fun `the open dialog and its text survive a recreated activity`() {
        click("Profile")
        click("Open another profile")
        compose.onNode(hasSetTextAction()).performTextReplacement("sample_vie")
        recreate()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasSetTextAction() and hasText("sample_vie")).assertExists()
    }

    @Test
    fun `a title's link after a profile's opens the title over the List alone`() {
        link(other)
        app.links.opened(Link.Title(FixtureAnime.exactToWatch.id))
        compose.waitForIdle()
        hides(other)
        back()
        shows("My list")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `a link is acted on once, also across a recreated activity`() {
        link(other)
        recreate()
        onProfileOf(other)
        back()
        shows(ownName)
        recreate()
        shows(ownName)
        hides(other)
    }

    @Test
    @Config(application = StartedByProfileLinkApplication::class)
    fun `a link that starts the app opens on its profile with the own Profile and the List beneath`() {
        onProfileOf(other)
        // The List is made only when Back shows it: the start never shows it first.
        assertEquals(0, app.test.wholeListReads)
        assertTheOwnProfileAndTheListAreBeneath()
    }
}
