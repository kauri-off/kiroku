package io.github.kauri_off.kiroku.title

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class TitleScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf<TitleState>(TitleState.Loading)
    private val events = mutableListOf<TitleEvent>()
    private val snackbars = SnackbarHostState()
    private var started = false

    private fun show(shown: TitleState) {
        if (!started) {
            started = true
            compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { TitleInShell(state, { events += it }, snackbars) } }
        }
        compose.runOnIdle { state = shown }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose
            .onNode(
                hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).performScrollToNode(hasText(text))
    }

    private fun sends(
        event: TitleEvent,
        action: () -> Unit,
    ) {
        events.clear()
        action()
        compose.waitForIdle()
        assertEquals(listOf(event), events)
    }

    @Test
    fun theAiringBlockWritesCoresFigures() {
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        compose.onNodeWithText("14\u00A0of\u00A024\u00A0aired\u00A0· 2\u00A0to\u00A0watch").assertIsDisplayed()
        compose.onNodeWithText("Episode\u00A015 · Sat\u00A05:30", substring = true).assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.estimatedToWatch))
        compose.onNodeWithText("~10\u00A0of\u00A012\u00A0aired\u00A0· ~3\u00A0to\u00A0watch").assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.estimatedCaughtUp))
        compose.onNodeWithText("~5\u00A0of\u00A012\u00A0aired").assertIsDisplayed()
        show(TitleFixtures.firstEpisodeAired)
        compose.onNodeWithText("1\u00A0of\u00A024\u00A0aired\u00A0· 1\u00A0to\u00A0watch").assertIsDisplayed()
    }

    @Test
    fun theFactsLineLeavesOutASingleEpisode() {
        show(TitleFixtures.of(FixtureAnime.film))
        compose.onNodeWithText("Movie\u00A0· 2024").assertIsDisplayed()
    }

    @Test
    fun theTitleIsReadAsTheModelTitle() {
        show(TitleFixtures.of(FixtureAnime.exactCaughtUpLater))
        compose.onNodeWithText(FixtureAnime.exactCaughtUpLater.titles.romaji).assertIsDisplayed()
    }

    @Test
    fun theSynopsisHeaderStandsOnlyOverASynopsis() {
        val shown = TitleFixtures.of(FixtureAnime.exactToWatch)
        show(shown.copy(details = shown.details?.copy(synopsis = null)))
        scrollTo("Related")
        compose.onNodeWithText("Synopsis").assertDoesNotExist()
        compose.onNodeWithText("Drama", substring = true).assertExists()
    }

    @Test
    fun theTopBarSendsItsEvents() {
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        sends(TitleEvent.Back) { compose.onNodeWithContentDescription("Back").performClick() }
        compose.onNodeWithContentDescription("More options").performClick()
        sends(TitleEvent.OpenOnMal) { compose.onNodeWithText("Open on MyAnimeList").performClick() }
        compose.onNodeWithContentDescription("More options").performClick()
        sends(TitleEvent.Share) { compose.onNodeWithText("Share").performClick() }
    }

    @Test
    fun theListBlockSendsItsEvents() {
        show(TitleFixtures.of(FixtureAnime.notOnListAiring))
        sends(TitleEvent.AddToList) { compose.onNodeWithText("Add to list").performClick() }
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        sends(TitleEvent.Edit) { compose.onNodeWithText("Watching").performClick() }
        sends(TitleEvent.SetWatched(13)) { compose.onNodeWithContentDescription("Plus one episode").performClick() }
        sends(TitleEvent.SetWatched(11)) { compose.onNodeWithContentDescription("Minus one episode").performClick() }
        show(TitleFixtures.of(FixtureAnime.estimatedToWatch))
        sends(TitleEvent.Edit) { compose.onNodeWithText("Rate").performClick() }
    }

    @Test
    fun theLowerSectionsSendTheirEvents() {
        val shown = TitleFixtures.of(FixtureAnime.exactToWatch)
        show(shown)
        scrollTo("More")
        sends(TitleEvent.ExpandSynopsis) { compose.onNodeWithText("More").performClick() }
        val first = shown.related.first()
        scrollTo("See all")
        sends(TitleEvent.OpenRelated(first.related.anime.id)) { compose.onNode(hasText(first.title) and hasClickAction()).performClick() }
        sends(
            TitleEvent.SeeAllRelated,
        ) { compose.onNode(hasContentDescription("See all", substring = true) or hasText("See all")).performClick() }
        scrollTo("Recommendations")
        sends(TitleEvent.OpenDetails) { compose.onNodeWithText("Details").performClick() }
        sends(TitleEvent.OpenRecommendations) { compose.onNodeWithText("Recommendations").performClick() }
    }

    @Test
    fun retryIsAnEvent() {
        show(TitleState.Failed(DataError.NoConnection))
        sends(TitleEvent.Retry) { compose.onNodeWithText("Retry").performClick() }
    }

    // The contract with the view model: the screen reports how a snackbar ended, the state then drops it, and the same
    // snackbar set again is shown again.
    @Test
    fun aSnackbarReportsHowItEndedAndShowsAgainAfterNull() {
        val added = TitleFixtures.added
        show(added)
        sends(TitleEvent.SnackbarAction) { compose.onNodeWithText("Change").performClick() }
        show(added.copy(snackbar = null))
        show(added)
        compose.onNodeWithText("Added to Planned").assertIsDisplayed()
        sends(TitleEvent.SnackbarDismissed) { compose.runOnIdle { snackbars.currentSnackbarData?.dismiss() } }
    }
}
