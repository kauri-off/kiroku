package io.github.kauri_off.kiroku.title

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.rememberHostShowing
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

    private var state by mutableStateOf<TitleState>(TitleState.Loading(Fixtures.reference.session))
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
        compose.onNodeWithText("14\u00A0aired\u00A0· 2\u00A0to\u00A0watch").assertIsDisplayed()
        compose.onNodeWithText("Episode\u00A015 ·\u00A0Sat\u00A05:30", substring = true).assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.estimatedToWatch))
        compose.onNodeWithText("~10\u00A0aired\u00A0· ~3\u00A0to\u00A0watch").assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.estimatedCaughtUp))
        compose.onNodeWithText("~5\u00A0aired").assertIsDisplayed()
        show(TitleFixtures.firstEpisodeAired)
        compose.onNodeWithText("1\u00A0aired\u00A0· 1\u00A0to\u00A0watch").assertIsDisplayed()
    }

    @Test
    fun theTotalIsWrittenInTheHeaderOnlyForATitleNotOnTheList() {
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        compose.onNodeWithText("TV\u00A0· 2025").assertIsDisplayed()
        compose.onNodeWithText("episodes", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Episodes watched").assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.notOnListAiring))
        compose.onNodeWithText("episodes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Episodes watched").assertDoesNotExist()
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

    // W9: plus stays enabled at the total, and a count above the total is shown as it is.
    @Test
    fun theStepperHasNoCeiling() {
        show(TitleFixtures.completed)
        compose.onNode(hasSetTextAction() and hasText("12 /\u00A012", substring = true)).assertExists()
        sends(TitleEvent.StepWatched(1)) { compose.onNodeWithContentDescription("Plus one episode").assertIsEnabled().performClick() }
        show(TitleFixtures.aboveTotal)
        compose.onNode(hasSetTextAction() and hasText("14 /\u00A012")).assertIsDisplayed()
        compose.onNodeWithText("Completed").assertIsDisplayed()
        sends(TitleEvent.StepWatched(1)) { compose.onNodeWithContentDescription("Plus one episode").performClick() }
    }

    @Test
    fun theSynopsisHeaderStandsOnlyOverASynopsis() {
        val anime = FixtureAnime.exactToWatch
        show(TitleFixtures.of(anime, details = Fixtures.reference.details[anime.id]?.copy(synopsis = null)))
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
        sends(TitleEvent.StepWatched(1)) { compose.onNodeWithContentDescription("Plus one episode").performClick() }
        sends(TitleEvent.StepWatched(-1)) { compose.onNodeWithContentDescription("Minus one episode").performClick() }
        show(TitleFixtures.of(FixtureAnime.estimatedToWatch))
        sends(TitleEvent.Edit) { compose.onNodeWithText("Rate").performClick() }
    }

    @Test
    fun theLowerSectionsSendTheirEvents() {
        val shown = TitleFixtures.of(FixtureAnime.exactToWatch)
        show(shown)
        scrollTo("More")
        sends(TitleEvent.ExpandSynopsis) { compose.onNodeWithText("More").performClick() }
        show(shown.copy(moment = TitleMoment(synopsisExpanded = true)))
        scrollTo("Less")
        sends(TitleEvent.CollapseSynopsis) { compose.onNodeWithText("Less").performClick() }
        show(shown)
        val sequel = checkNotNull(shown.page.sequel)
        scrollTo(sequel.title)
        sends(TitleEvent.OpenRelated(sequel.related.anime.id)) { compose.onNode(hasText(sequel.title) and hasClickAction()).performClick() }
        val first = shown.page.related.first()
        scrollTo("Statistics")
        sends(TitleEvent.OpenRelated(first.related.anime.id)) { compose.onNode(hasText(first.title) and hasClickAction()).performClick() }
        sends(
            TitleEvent.SeeAllRelated,
        ) { compose.onNode(hasContentDescription("See all", substring = true) or hasText("See all")).performClick() }
        scrollTo("Recommendations")
        sends(TitleEvent.OpenDetails) { compose.onNodeWithText("Details").performClick() }
        sends(TitleEvent.OpenRecommendations) { compose.onNodeWithText("Recommendations").performClick() }
    }

    // Review M1: with no related title for the strip, the section is "See all" alone.
    @Test
    fun seeAllIsReachableWhenNoRelatedTitleFitsTheStrip() {
        show(TitleFixtures.relatedOutsideTheStrip)
        scrollTo("Related")
        compose.onNodeWithText("Related").assertIsDisplayed()
        sends(TitleEvent.SeeAllRelated) { compose.onNode(hasText("See all") and hasClickAction()).performClick() }
    }

    @Test
    fun theSessionBannerShowsInEveryStateOnlyForAnExpiredSessionAndSendsSignIn() {
        val page = TitleFixtures.page(FixtureAnime.exactToWatch)
        val states: (Session) -> List<TitleState> =
            { listOf(TitleState.Loading(it), TitleState.Failed(DataError.NoConnection, it), TitleState.Shown(page, it)) }
        for (shown in states(Fixtures.reference.session)) {
            show(shown)
            compose.onNodeWithText("Session expired").assertDoesNotExist()
        }
        for (shown in states(Session.Expired(Fixtures.reference.session.signIn))) {
            show(shown)
            compose.onNodeWithText("Session expired").assertIsDisplayed()
            sends(TitleEvent.SignIn) { compose.onNodeWithText("Sign in").performClick() }
        }
    }

    @Test
    fun retryIsAnEvent() {
        show(TitleState.Failed(DataError.NoConnection, Fixtures.reference.session))
        sends(TitleEvent.Retry) { compose.onNodeWithText("Retry").performClick() }
    }

    // The view model holds the wait back for its first second; the screen honours that.
    @Test
    fun theDetailsIndicatorStaysHiddenUntilTheWaitIsShown() {
        val waiting = TitleFixtures.of(FixtureAnime.exactToWatch, details = null)
        show(waiting.copy(detailsWait = DetailsWait.Brief))
        compose.onNodeWithContentDescription("Loading details").assertDoesNotExist()
        show(waiting.copy(detailsWait = DetailsWait.Loading))
        compose.onNodeWithContentDescription("Loading details").assertIsDisplayed()
    }

    // Section 7.2: where the details would be, the cause and Retry, under the summary that stays.
    @Test
    fun detailsThatCouldNotLoadSayWhyUnderTheSummaryAndOfferRetry() {
        show(TitleFixtures.of(FixtureAnime.exactToWatch, details = null).copy(detailsWait = DetailsWait.Failed(DataError.NoConnection)))
        compose.onNodeWithText("Episodes watched").assertIsDisplayed()
        compose.onNodeWithText("No connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading details").assertDoesNotExist()
        sends(TitleEvent.Retry) { compose.onNodeWithText("Retry").performClick() }
    }

    @Test
    fun aShownPageCanBePulledToRefresh() {
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        sends(TitleEvent.Refresh) { compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) } }
    }

    // ARCHITECTURE 6.2: the screen itself draws the host the shell hands it.
    @Test
    fun theScreenDrawsTheSnackbarHostItIsGiven() {
        compose.setContent {
            KirokuTheme(
                dark = false,
                dynamicColor = false,
            ) { TitleScreen(TitleFixtures.added, {}, rememberHostShowing(TitleFixtures.addedMessage)) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Added to Planned").assertIsDisplayed()
        compose.onNodeWithText("Change").assertIsDisplayed()
    }
}
