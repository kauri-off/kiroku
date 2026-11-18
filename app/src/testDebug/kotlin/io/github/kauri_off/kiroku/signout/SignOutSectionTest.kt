package io.github.kauri_off.kiroku.signout

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.signin.FakeSignInApplication
import io.github.kauri_off.kiroku.signin.NoSignInApplication
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SignOutSectionTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val graph get() = RuntimeEnvironment.getApplication().appGraph

    private fun menu() {
        compose.setContent { Column { SignOutRoute() } }
        compose.waitForIdle()
    }

    @Test
    @Config(application = FakeSignInApplication::class)
    fun `the account signs out from the menu, which then offers no sign-out`() {
        menu()
        compose.onNodeWithText("Signed in to MyAnimeList").assertExists()
        compose.onNodeWithText("Sign out").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        assertEquals(Session.SignedOut, runBlocking { graph.session.observeSession().first() })
    }

    @Test
    fun `Sign out is offered once the stored sign-in has been read and names one`() {
        val state = mutableStateOf(SignOutState.Account(null))
        compose.setContent { Column { SignOutSection(state.value, onEvent = {}) } }
        compose.onNodeWithText("The stored sign-in has not been read yet").assertExists()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        state.value = SignOutState.Account(Session.Expired(SignInId("a")))
        compose.onNodeWithText("Session expired").assertExists()
        compose.onNodeWithText("Sign out").assertExists()
    }

    @Test
    @Config(application = NoSignInApplication::class)
    fun `demo data has no account to sign out of`() {
        menu()
        compose.onNodeWithText("Demo data has no MyAnimeList account").assertExists()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
    }
}
