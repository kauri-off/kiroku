package io.github.kauri_off.kiroku.signin

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock

class FakeSignInApplication :
    Application(),
    AppGraphOwner {
    val test by lazy { TestGraph(Demo(Fixtures.reference, CoroutineScope(SupervisorJob() + Dispatchers.Main))) }
    override val graph by lazy { FakeSignInGraph(test) }
}

// The sign-in in the real activity, over demo data with a sign-in the test moves from step to step.
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeSignInApplication::class, qualifiers = "en-w411dp-h891dp")
class SignInRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as FakeSignInApplication
    private val signIn get() = app.graph.signIn
    private val kagerou = FixtureAnime.exactToWatch.titles.romaji

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    private fun click(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun step(step: SignInStep) {
        signIn.step.value = step
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

    // The List is alone on its stack: Back from it leaves the app.
    private fun assertTheListIsAlone() {
        shows("My list")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    private fun signedOut() {
        runBlocking { app.test.session.signOut() }
        compose.waitForIdle()
    }

    @Test
    fun `signed out, Sign in opens the screen, and a finished sign-in leaves the List alone on its stack`() {
        signedOut()
        click("Sign in")
        shows(SENTENCE)
        click(SIGN_IN)
        assertEquals(1, signIn.starts.size)

        step(SignInStep.Completing)
        shows("Signing in…")
        compose.onNodeWithText(SIGN_IN).assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, signIn.starts.size)

        step(SignInStep.SignedIn)
        assertEquals(listOf<SignInStep>(SignInStep.SignedIn), signIn.acknowledged.toList())
        assertTheListIsAlone()
    }

    @Test
    fun `the session banner leads to the sign-in screen, and a finished sign-in ends on the List`() {
        app.test.demo.server.sessionExpired.value = true
        compose.waitForIdle()
        click(kagerou)
        click("Sign in")
        shows(SENTENCE)
        step(SignInStep.SignedIn)
        assertTheListIsAlone()
    }

    @Test
    fun `a finished sign-in is acted on once, also across a recreated activity`() {
        signedOut()
        click("Sign in")
        step(SignInStep.SignedIn)
        signedOut()
        click("Sign in")
        recreate()
        shows(SENTENCE)
        compose.onNodeWithText(SIGN_IN).assertIsEnabled()
    }

    @Test
    fun `a redirect opens the sign-in screen once, also across a recreated activity, and shows its failure there`() {
        app.test.demo.server.sessionExpired.value = true
        compose.waitForIdle()
        click(kagerou)
        signIn.redirected("x")
        compose.waitForIdle()
        shows("Signing in…")
        back()
        shows(kagerou)
        recreate()
        compose.onNodeWithText("Signing in…").assertDoesNotExist()
        shows(kagerou)

        step(SignInStep.Failed(SignInFailure.Rejected))
        shows(kagerou)
        signIn.redirected("x")
        compose.waitForIdle()
        step(SignInStep.Failed(SignInFailure.Rejected))
        shows("Sign-in failed, try again")
        recreate()
        shows("Sign-in failed, try again")
        back()
        shows(kagerou)
        assertEquals(listOf<SignInStep>(SignInStep.Failed(SignInFailure.Rejected)), signIn.acknowledged.toList())
        assertEquals(SignInStep.None, signIn.step.value)
    }

    @Test
    fun `a start that has not opened the browser yet ends with the screen, as a recreated activity ends it`() {
        signedOut()
        click("Sign in")
        signIn.holdsStart = true
        click(SIGN_IN)
        assertEquals(0, signIn.cancelledStarts)
        recreate()
        assertEquals(1, signIn.cancelledStarts)
    }

    @Test
    fun `Browse without an account goes to Search and leaves no sign-in screen behind`() {
        signedOut()
        click("Sign in")
        click("Browse without an account")
        shows("Recent searches")
        back()
        shows("My list")
        back()
        assertTrue(compose.activity.isFinishing)
    }

    private companion object {
        const val SENTENCE = "Keep track of your list and know when new episodes air"
        const val SIGN_IN = "Sign in with MyAnimeList"
    }
}

// The screen's form for a build without a client id, over the real live graph of such a build.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SignInWithoutClientIdTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a build without a client id shows the screen's no-client-id form`() {
        val context = RuntimeEnvironment.getApplication()
        val graph = createGraph(context, "", Clock.systemUTC(), CoroutineScope(SupervisorJob() + Dispatchers.Main), other = null)
        assertTrue(graph is SignInOwner)
        compose.setContent { SignInRoute(graph, SnackbarHostState(), onBack = {}, onBrowse = {}, onSignedIn = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("This build has no MyAnimeList client id").assertExists()
        compose.onNodeWithText("Sign in with MyAnimeList").assertIsNotEnabled()
    }
}
