package io.github.kauri_off.kiroku.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
        compose.onNodeWithTag(ProfileTags.avatar, useUnmergedTree = true).assertExists()
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
        assertEquals(listOf(ProfileEvent.OpenSettings, ProfileEvent.StartOpenProfile()), events)
    }

    @Test
    fun anotherUserIsNamedInTheBarWithBackAndOffersTheirList() {
        show(fixtures.of(fixtures.otherName, fixtures.other))
        compose.onNodeWithText(fixtures.otherName).assertExists()
        compose.onNodeWithTag(ProfileTags.avatar, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Joined", substring = true, useUnmergedTree = true).assertDoesNotExist()
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
        compose.onAllNodesWithText("0", useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithText("0.0", useUnmergedTree = true).assertDoesNotExist()
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
    @Config(fontScale = 1.45f)
    fun theThreeNumbersShareOneRowJustBelowLargeText() {
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
        compose.onNodeWithText("Open another profile").performClick()
        assertEquals(listOf(ProfileEvent.SignIn, ProfileEvent.StartOpenProfile()), events)
    }

    @Test
    fun aPrivateListAndAnUnknownNameSayWhatTheyAreAndOfferAnotherName() {
        val cases =
            listOf(
                Triple(fixtures.privateName, DataError.PrivateList, "This list is private"),
                Triple(fixtures.unknownName, DataError.UnknownUser, "MyAnimeList has no user with this name"),
            )
        var state by mutableStateOf(fixtures.of(fixtures.privateName, ProfileContent.Failed(DataError.PrivateList)))
        compose.setContent {
            KirokuTheme(
                dark = false,
                dynamicColor = false,
            ) { ProfileScreen(state, { events += it }, SnackbarHostState()) }
        }
        for ((name, error, message) in cases) {
            state = fixtures.of(name, ProfileContent.Failed(error))
            compose.onNodeWithText(message).assertExists()
            compose.onNodeWithText("Retry").assertDoesNotExist()
            compose.onNodeWithText("Open another profile").performClick()
        }
        // The dialog starts empty after a private list and from the name that was not found.
        assertEquals(listOf(ProfileEvent.StartOpenProfile(), ProfileEvent.StartOpenProfile(fixtures.unknownName)), events)
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
        val line = "Loading 3,000\u00A0of\u00A012,400"
        compose.onNodeWithText(line).assertExists()
        assertEquals(1, compose.onAllNodes(hasText(line) or hasContentDescription(line)).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp-xhdpi", fontScale = 2f)
    fun bothProgressLinesWrapAfterTheVerbWithLargeText() {
        val lines = listOf(ProfileContent.ListLoading(3_000, 12_400), ProfileContent.ListLoading(1_200, null))
        var state by mutableStateOf(fixtures.of(fixtures.otherName, lines[0]))
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { ProfileScreen(state, {}, SnackbarHostState()) } }
        val verb = "Загружено "
        for (content in lines) {
            state = fixtures.of(fixtures.otherName, content)
            val results = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(verb, substring = true, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!
                .invoke(results)
            val layout = results.single()
            // Centred text is narrower than its paragraph, so every line is checked against the width it may take.
            val width = layout.layoutInput.constraints.maxWidth
            val inside = (0 until layout.lineCount).all { layout.getLineLeft(it) >= 0f && layout.getLineRight(it) <= width }
            assertTrue("${layout.layoutInput.text} runs past its width", inside)
            val breaks = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
            assertTrue("${layout.layoutInput.text} breaks at $breaks", breaks.all { it == verb.length })
        }
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
    fun theDialogScrollsBetweenItsTitleAndItsButtons() {
        show(fixtures.me().copy(openProfile = OpenProfile("", fixtures.recent)))
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Recent"))).assertExists()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Cancel"))).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-w891dp-h411dp-xhdpi")
    fun inAShortWindowTheWholeDialogScrolls() {
        show(fixtures.me().copy(openProfile = OpenProfile("", fixtures.recent)))
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Cancel")) and hasAnyDescendant(hasText("User name"))).assertExists()
    }

    @Test
    fun theDialogOpensTheNameInAPastedProfileLink() {
        show(fixtures.me().copy(openProfile = OpenProfile("https://myanimelist.net/profile/${fixtures.otherName}?view=all", emptyList())))
        compose.onNodeWithText("Open").performClick()
        assertEquals(listOf(ProfileEvent.OpenProfile(fixtures.otherName)), events)
    }

    @Test
    fun aLinkThatIsNotAProfileStaysInTheFieldAndSaysSo() {
        show(fixtures.me().copy(openProfile = OpenProfile("https://anilist.co/user/${fixtures.otherName}/", emptyList())))
        compose.onNodeWithText("Not a MyAnimeList profile link", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Open").assertIsNotEnabled()
    }

    @Test
    fun theDialogOpensTheNameInAPastedListLink() {
        show(fixtures.me().copy(openProfile = OpenProfile("myanimelist.net/animelist/${fixtures.otherName}", emptyList())))
        compose.onNodeWithText("Not a MyAnimeList profile link", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Open").performClick()
        assertEquals(listOf(ProfileEvent.OpenProfile(fixtures.otherName)), events)
    }

    @Test
    fun theDialogOpensTheTypedName() {
        show(fixtures.me().copy(openProfile = OpenProfile(" ${fixtures.otherName} ", emptyList())))
        compose.onNodeWithText("Recent").assertDoesNotExist()
        compose.onNodeWithText("Open").assertIsEnabled().performClick()
        assertEquals(listOf(ProfileEvent.OpenProfile(fixtures.otherName)), events)
    }
}
