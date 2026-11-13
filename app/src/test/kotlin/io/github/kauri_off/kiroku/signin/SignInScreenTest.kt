package io.github.kauri_off.kiroku.signin

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
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
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class SignInScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<SignInEvent>()

    private fun show(state: SignInState) =
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                SignInScreen(state, onEvent = { events += it }, snackbars = SnackbarHostState())
            }
        }

    // Every text and content description on the screen, and the number of controls.
    private fun shown(): Pair<List<String>, Int> {
        val nodes =
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) or
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Role),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        val labels =
            nodes.flatMap { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
        return labels.sorted() to nodes.count { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
    }

    private fun expected(sentence: String) = listOf(APP, BACK, sentence, SIGN_IN, BROWSE, NOTICE).sorted() to CONTROLS

    private fun liveRegion(text: String) =
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.LiveRegion)

    private fun mark() = compose.onNodeWithTag(SignInTags.mark, useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun theScreenShowsItsBudgetAndNothingElse() {
        show(SignInState.Ready)
        assertEquals(expected(SENTENCE), shown())
        assertEquals(null, liveRegion(SENTENCE))
        val mark = mark()
        assertTrue(mark.layoutInfo.isPlaced)
        assertEquals(null, mark.config.getOrNull(SemanticsProperties.ContentDescription))
        val sentence = compose.onNodeWithText(SENTENCE, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(mark.boundsInRoot.bottom <= sentence.boundsInRoot.top)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h320dp-xhdpi", fontScale = 2f)
    fun theMarkIsTheFirstToGiveWayWhenTheWindowHasNoRoomForIt() {
        show(SignInState.Ready)
        assertFalse(mark().layoutInfo.isPlaced)
        assertEquals(expected(SENTENCE), shown())
    }

    @Test
    fun everyControlSendsItsEvent() {
        show(SignInState.Ready)
        compose.onNodeWithText(SIGN_IN).assertIsEnabled().performClick()
        compose.onNodeWithText(BROWSE).performClick()
        compose.onNodeWithContentDescription(BACK).performClick()
        assertEquals(listOf(SignInEvent.SignIn, SignInEvent.Browse, SignInEvent.Back), events)
    }

    @Test
    fun withoutAClientIdTheSentenceIsReplacedAndOnlyBrowsingIsLeft() {
        show(SignInState.NoClientId)
        assertEquals(expected(NO_CLIENT_ID), shown())
        assertEquals(null, liveRegion(NO_CLIENT_ID))
        compose.onNodeWithText(SIGN_IN).assertIsNotEnabled().performClick()
        compose.onNodeWithText(BROWSE).assertIsEnabled().performClick()
        assertEquals(listOf(SignInEvent.Browse), events)
    }

    @Test
    fun aSignInBeingCompletedSaysSoAndLeavesBrowsingOpen() {
        show(SignInState.Completing)
        assertEquals(expected(SIGNING_IN), shown())
        assertEquals(LiveRegionMode.Polite, liveRegion(SIGNING_IN))
        compose.onNodeWithText(SIGN_IN).assertIsNotEnabled().performClick()
        compose.onNodeWithText(BROWSE).assertIsEnabled().performClick()
        assertEquals(listOf(SignInEvent.Browse), events)
    }

    @Test
    fun aFailedSignInIsAnnouncedInPlaceOfTheSentenceAndCanBeTriedAgain() {
        show(SignInState.Failed)
        assertEquals(expected(FAILED), shown())
        assertEquals(LiveRegionMode.Polite, liveRegion(FAILED))
        compose.onNodeWithText(SIGN_IN).assertIsEnabled().performClick()
        compose.onNodeWithText(BROWSE).assertIsEnabled().performClick()
        assertEquals(listOf(SignInEvent.SignIn, SignInEvent.Browse), events)
    }

    private companion object {
        const val APP = "Kiroku"
        const val BACK = "Back"
        const val SENTENCE = "Keep track of your list and know when new episodes air"
        const val NO_CLIENT_ID = "This build has no MyAnimeList client id"
        const val SIGNING_IN = "Signing in…"
        const val FAILED = "Sign-in failed, try again"
        const val SIGN_IN = "Sign in with MyAnimeList"
        const val BROWSE = "Browse without an account"
        const val NOTICE = "Unofficial. Not affiliated with MyAnimeList."

        // Back, sign in and browse.
        const val CONTROLS = 3
    }
}
