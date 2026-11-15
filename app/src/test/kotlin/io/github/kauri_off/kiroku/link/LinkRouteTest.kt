package io.github.kauri_off.kiroku.link

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.preferences.StoredNotice
import io.github.kauri_off.kiroku.signin.FakeSignInGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.io.File

// Demo data, a sign-in the test moves, and the real notice over a file, whose need for the person's consent the test sets.
open class LinkGraphApplication :
    Application(),
    AppGraphOwner,
    LinksOwner {
    val test by lazy { TestGraph(Demo(Fixtures.reference, CoroutineScope(SupervisorJob() + Dispatchers.Main))) }
    override val graph by lazy { FakeSignInGraph(test) }

    @Volatile var consentNeeded = false

    override val links by lazy {
        val stored = StoredNotice(File(noBackupFilesDir, "links.preferences_pb"), CoroutineScope(SupervisorJob()), {})
        AppLinks(DeviceLinkNotice(stored) { consentNeeded })
    }
}

// The system handed the app a link before its screen was made.
class StartedByLinkApplication : LinkGraphApplication() {
    override fun onCreate() {
        super.onCreate()
        consentNeeded = true
        links.opened(FixtureAnime.exactToWatch.id)
    }
}

// The system does not open MAL's links in the app until the person allows it.
class ConsentNeededApplication : LinkGraphApplication() {
    override fun onCreate() {
        super.onCreate()
        consentNeeded = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = LinkGraphApplication::class, qualifiers = "en-w411dp-h891dp")
class LinkRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as LinkGraphApplication
    private val kagerou = FixtureAnime.exactToWatch
    private val third = FixtureAnime.premiereYear

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    private fun hides(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    private fun click(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun link(anime: Anime) {
        app.links.opened(anime.id)
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

    private fun signedOut() {
        runBlocking { app.test.session.signOut() }
        compose.waitForIdle()
    }

    // The title page of [anime] is on top: its header is shown, and only the page has the stepper's label.
    private fun onPageOf(anime: Anime) {
        compose.onAllNodesWithText(anime.titles.romaji).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        hides("My list")
    }

    // Back shows the List, and Back from it leaves the app: the List is alone beneath the page.
    private fun assertTheListIsBeneath() {
        back()
        shows("My list")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    private fun noticeShown(): Boolean = compose.onAllNodesWithText(NOTICE).fetchSemanticsNodes().isNotEmpty()

    private fun due(): Boolean = runBlocking { app.links.notice.due() }

    @Test
    fun `signed in, a link opens its title over the List`() {
        shows("My list")
        link(kagerou)
        onPageOf(kagerou)
        shows("Episodes watched")
        assertTheListIsBeneath()
    }

    @Test
    fun `signed out, a link opens its title over the List`() {
        signedOut()
        link(kagerou)
        onPageOf(kagerou)
        back()
        shows("My list")
        shows("Sign in")
    }

    @Test
    fun `with another destination and a page of its own shown, a link opens its title over the List`() {
        link(third)
        click("Search")
        link(kagerou)
        onPageOf(kagerou)
        assertTheListIsBeneath()
    }

    @Test
    fun `over an open edit sheet, a link opens its title`() {
        compose.onNodeWithText(kagerou.titles.romaji).performTouchInput { longClick() }
        compose.waitForIdle()
        shows("Save")
        link(third)
        onPageOf(third)
        hides("Save")
        back()
        shows("My list")
    }

    @Test
    fun `over the open search view, a link opens its title`() {
        click("Search")
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
        link(kagerou)
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        onPageOf(kagerou)
        assertTheListIsBeneath()
    }

    @Test
    fun `two links in a row open the second over the List alone`() {
        link(kagerou)
        link(kagerou)
        onPageOf(kagerou)
        link(third)
        onPageOf(third)
        assertTheListIsBeneath()
    }

    @Test
    fun `while a sign-in waits for its redirect, a link opens its title, and the sign-in still ends on the List`() {
        signedOut()
        click("Sign in")
        click(SIGN_IN)
        assertEquals(1, app.graph.signIn.starts.size)
        link(kagerou)
        onPageOf(kagerou)
        app.graph.signIn.redirected("x")
        compose.waitForIdle()
        shows("Signing in…")
        app.graph.signIn.step.value = SignInStep.SignedIn
        compose.waitForIdle()
        shows("My list")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `a link is acted on once, also across a recreated activity`() {
        link(kagerou)
        recreate()
        onPageOf(kagerou)
        back()
        shows("My list")
        recreate()
        shows("My list")
        hides("Episodes watched")
    }

    @Test
    @Config(application = StartedByLinkApplication::class)
    fun `a link that starts the app opens on its title with the List beneath, and no notice over it`() {
        onPageOf(kagerou)
        // The List is made only when Back shows it: the start never shows it first.
        assertEquals(0, app.test.wholeListReads)
        assertTrue(due())
        assertFalse(noticeShown())
        assertTheListIsBeneath()
    }

    @Test
    @Config(application = ConsentNeededApplication::class)
    fun `the notice shows once over the List at a start, and never again`() {
        compose.waitUntil(WAIT) { noticeShown() }
        shows(SENTENCE)
        compose.waitUntil(WAIT) { !due() }
        back()
        shows("My list")
        recreate()
        compose.waitForIdle()
        assertFalse(noticeShown())
        back()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `the notice is not shown while the links open in the app`() {
        shows("My list")
        assertFalse(due())
        recreate()
        assertFalse(noticeShown())
    }

    @Test
    @Config(application = ConsentNeededApplication::class)
    fun `Open settings leads to the system's setting of the app's links and leaves the List on screen`() {
        compose.waitUntil(WAIT) { noticeShown() }
        click("Open settings")
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertFalse(noticeShown())
        shows("My list")
    }

    private companion object {
        const val NOTICE = "Links to MyAnimeList"
        const val SENTENCE =
            "Links to titles on myanimelist.net open in the browser until you allow this app to open them in Android's settings"
        const val SIGN_IN = "Sign in with MyAnimeList"
        const val WAIT = 5_000L
    }
}
