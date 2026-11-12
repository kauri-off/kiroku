package io.github.kauri_off.kiroku.signin

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class SignInScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<SignInEvent>()

    private fun show(state: SignInState) =
        compose.setContent {
            KirokuTheme(
                dark = false,
                dynamicColor = false,
            ) { SignInScreen(state, onEvent = { events += it }, snackbars = SnackbarHostState()) }
        }

    private fun texts(): List<String> =
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .sorted()

    @Test
    fun theScreenShowsItsBudgetAndNothingElse() {
        show(SignInState.Ready)
        assertEquals(listOf(APP, SENTENCE, SIGN_IN, BROWSE, NOTICE).sorted(), texts())
    }

    @Test
    fun bothButtonsSendTheirEvents() {
        show(SignInState.Ready)
        compose.onNodeWithText(SIGN_IN).assertIsEnabled().performClick()
        compose.onNodeWithText(BROWSE).performClick()
        assertEquals(listOf(SignInEvent.SignIn, SignInEvent.Browse), events)
    }

    @Test
    fun withoutAClientIdTheSentenceIsReplacedAndSignInIsDisabled() {
        show(SignInState.NoClientId)
        assertEquals(listOf(APP, NO_CLIENT_ID, SIGN_IN, BROWSE, NOTICE).sorted(), texts())
        compose.onNodeWithText(SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithText(BROWSE).assertIsEnabled()
    }

    @Test
    fun aFailedSignInSaysSoInPlaceOfTheSentenceAndCanBeTriedAgain() {
        show(SignInState.Failed)
        assertEquals(listOf(APP, FAILED, SIGN_IN, BROWSE, NOTICE).sorted(), texts())
        compose.onNodeWithText(SIGN_IN).assertIsEnabled().performClick()
        assertEquals(listOf(SignInEvent.SignIn), events)
    }

    @Test
    fun aSignInBeingCompletedShowsTheSharedLoadingState() {
        show(SignInState.Completing)
        compose.onNodeWithContentDescription("Loading").assertExists()
        assertEquals(listOf(APP), texts())
        compose.onAllNodesWithText(SIGN_IN).assertCountEquals(0)
    }

    private companion object {
        const val APP = "Kiroku"
        const val SENTENCE = "Keep track of your list and know when new episodes air"
        const val NO_CLIENT_ID = "This build has no MyAnimeList client id"
        const val FAILED = "Sign-in failed, try again"
        const val SIGN_IN = "Sign in with MyAnimeList"
        const val BROWSE = "Browse without an account"
        const val NOTICE = "Unofficial. Not affiliated with MyAnimeList."
    }
}
