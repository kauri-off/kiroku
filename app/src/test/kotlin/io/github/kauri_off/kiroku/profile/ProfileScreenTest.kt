package io.github.kauri_off.kiroku.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import io.github.kauri_off.kiroku.core.error.DataError
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class ProfileScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val fixtures = ProfileFixtureStates
    private val events = mutableListOf<ProfileEvent>()

    private fun show(state: ProfileState) =
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { ProfileScreen(state, { events += it }, SnackbarHostState()) }
        }

    @Test
    fun theOwnProfileShowsItsBudget() {
        show(fixtures.me())
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("kiroku_demo").assertExists()
        compose.onNodeWithText("Joined\u00A02019").assertExists()
        for (caption in listOf("Completed", "Days watched", "Mean score")) {
            compose.onAllNodesWithText(caption, useUnmergedTree = true).onFirst().assertExists()
        }
        compose.onNodeWithText("24.4", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("7.43", useUnmergedTree = true).assertExists()
        for (status in listOf("Watching", "On hold", "Dropped", "Planned")) {
            compose.onNodeWithContentDescription(status, substring = true).assertExists()
        }
        for (row in listOf("Score distribution", "Genres", "Formats and years")) compose.onNodeWithContentDescription(row).assertExists()
        compose.onNodeWithContentDescription("Open another profile").performScrollTo().performClick()
        compose.onNodeWithContentDescription("View list").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertEquals(listOf(ProfileEvent.OpenSettings, ProfileEvent.StartOpenProfile), events)
    }

    @Test
    fun anotherUserIsNamedInTheBarWithBackAndOffersTheirList() {
        show(fixtures.of(fixtures.otherName, fixtures.other))
        compose.onNodeWithText(fixtures.otherName).assertExists()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open another profile").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Genres").performScrollTo().performClick()
        compose.onNodeWithContentDescription("View list").performScrollTo().performClick()
        assertEquals(listOf(ProfileEvent.Back, ProfileEvent.OpenStatistics(ProfileStatistics.Genres), ProfileEvent.ViewList), events)
    }

    @Test
    fun aListWithNothingOnItHasNoMeanScore() {
        show(fixtures.me(ProfileContent.Shown(fixtures.set.user, fixtures.nothing)))
        compose.onNodeWithText("0.0", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("No mean score", useUnmergedTree = true).assertExists()
    }

    // The first node with [text]: the summary's, above the status bar of the same name.
    private fun top(text: String) =
        compose
            .onAllNodesWithText(text, useUnmergedTree = true)
            .onFirst()
            .fetchSemanticsNode()
            .boundsInRoot

    private fun stacked(): Boolean = top("24.4").top >= top("Completed").bottom

    @Test
    fun theThreeNumbersShareOneRow() {
        show(fixtures.me())
        assertFalse(stacked())
        assertEquals(top("6").top, top("7.43").top)
    }

    @Test
    @Config(fontScale = 1.3f)
    fun theThreeNumbersShareOneRowBelowLargeText() {
        show(fixtures.me())
        assertFalse(stacked())
    }

    @Test
    @Config(fontScale = 1.5f)
    fun withLargeTextTheThreeNumbersAreStacked() {
        show(fixtures.me())
        assertTrue(stacked())
        assertTrue(top("7.43").top >= top("Days watched").bottom)
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp-xhdpi", fontScale = 1.3f)
    fun noCaptionInTheRowBreaksInsideAWord() {
        show(fixtures.me())
        for (caption in listOf("Просмотрено", "Дней просмотра", "Средняя оценка")) {
            val results = mutableListOf<TextLayoutResult>()
            compose
                .onAllNodesWithText(caption, useUnmergedTree = true)
                .onFirst()
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!
                .invoke(results)
            val layout = results.single()
            val breaks = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
            assertTrue("$caption breaks at $breaks", breaks.all { caption[it - 1] == ' ' || caption.getOrNull(it) == ' ' })
        }
    }

    @Test
    fun signedOutTheProfileOffersSignIn() {
        show(fixtures.me(ProfileContent.SignedOut))
        compose.onNodeWithText("Sign in with MyAnimeList to see your profile here").assertExists()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(listOf(ProfileEvent.SignIn), events)
    }

    @Test
    fun aPrivateListAndAnUnknownNameSayWhatTheyAreAndOfferAnotherName() {
        val cases =
            mapOf(DataError.PrivateList to "This list is private", DataError.UnknownUser to "MyAnimeList has no user with this name")
        var state by mutableStateOf(fixtures.of(fixtures.privateName, ProfileContent.Failed(DataError.PrivateList)))
        compose.setContent {
            KirokuTheme(
                dark = false,
                dynamicColor = false,
            ) { ProfileScreen(state, { events += it }, SnackbarHostState()) }
        }
        for ((error, message) in cases) {
            state = fixtures.of(fixtures.privateName, ProfileContent.Failed(error))
            compose.onNodeWithText(message).assertExists()
            compose.onNodeWithText("Retry").assertDoesNotExist()
            compose.onNodeWithText("Open another profile").performClick()
        }
        assertEquals(List(2) { ProfileEvent.StartOpenProfile }, events)
    }

    @Test
    fun aFailedLoadOffersRetry() {
        show(fixtures.of(fixtures.otherName, ProfileContent.Failed(DataError.NoConnection)))
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(ProfileEvent.Retry), events)
    }

    @Test
    fun aLongListLoadsWithItsProgressLine() {
        show(fixtures.of(fixtures.otherName, ProfileContent.ListLoading(3_000, 12_400)))
        compose.onNodeWithText("Loading\u00A03,000\u00A0of\u00A012,400").assertExists()
    }

    @Test
    fun theDialogOpensOnlyANameAndEachRecentName() {
        show(fixtures.me().copy(openProfile = OpenProfile(" ", fixtures.recent)))
        compose.onNodeWithText("Open").assertIsNotEnabled()
        compose.onNodeWithText(fixtures.recent[2]).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(ProfileEvent.OpenProfile(fixtures.recent[2]), ProfileEvent.DismissOpenProfile), events)
    }

    @Test
    fun theDialogOpensTheTypedName() {
        show(fixtures.me().copy(openProfile = OpenProfile(" ${fixtures.otherName} ", emptyList())))
        compose.onNodeWithText("Recent").assertDoesNotExist()
        compose.onNodeWithText("Open").assertIsEnabled().performClick()
        assertEquals(listOf(ProfileEvent.OpenProfile(fixtures.otherName)), events)
    }
}
