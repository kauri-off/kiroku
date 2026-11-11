package io.github.kauri_off.kiroku.title

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
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
class TitleScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var state by mutableStateOf<TitleState>(TitleState.Loading)
    private val events = mutableListOf<TitleEvent>()
    private var started = false

    private fun show(shown: TitleState) {
        if (!started) {
            started = true
            compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { TitleScreen(state, onEvent = { events += it }) } }
        }
        compose.runOnIdle { state = shown }
        compose.waitForIdle()
    }

    // Every title of up to two lines has its header, list block and airing block in the first screen.
    @Test
    fun theFirstThreeBlocksAreAboveTheFold() {
        val titles: List<Anime> =
            listOf(
                FixtureAnime.exactToWatch,
                FixtureAnime.notOnListAiring,
                FixtureAnime.estimatedToWatch,
                FixtureAnime.estimatedCaughtUp,
                FixtureAnime.airedUnknown,
                FixtureAnime.premiereTimeSoon,
                FixtureAnime.premiereTimeLater,
                FixtureAnime.premiereUnannounced,
                FixtureAnime.exactCaughtUp,
            )
        for (anime in titles) {
            show(TitleFixtures.of(anime).copy(details = null))
            val root = compose.onRoot().getBoundsInRoot().bottom
            for (tag in listOf(TitleTags.header, TitleTags.list, TitleTags.airing)) {
                val bottom: Dp = compose.onNodeWithTag(tag).getBoundsInRoot().bottom
                assertTrue("${anime.titles.romaji}: $tag ends at $bottom, below $root", bottom <= root)
            }
        }
    }

    @Test
    fun theAiringBlockWritesCoresFigures() {
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        compose.onNodeWithText("14\u00A0of\u00A024\u00A0aired\u00A0· 2\u00A0to\u00A0watch").assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.estimatedToWatch))
        compose.onNodeWithText("~10\u00A0of\u00A012\u00A0aired\u00A0· ~3\u00A0to\u00A0watch").assertIsDisplayed()
        show(TitleFixtures.of(FixtureAnime.estimatedCaughtUp))
        compose.onNodeWithText("~5\u00A0of\u00A012\u00A0aired").assertIsDisplayed()
    }

    @Test
    fun theFactsLineLeavesOutASingleEpisode() {
        show(TitleFixtures.of(FixtureAnime.film))
        compose.onNodeWithText("Movie\u00A0· 2024").assertIsDisplayed()
    }

    @Test
    fun addAndStepAreEvents() {
        show(TitleFixtures.of(FixtureAnime.notOnListAiring))
        compose.onNodeWithText("Add to list").performClick()
        show(TitleFixtures.of(FixtureAnime.exactToWatch))
        compose.onNodeWithText("Watching").performClick()
        assertEquals(listOf(TitleEvent.AddToList, TitleEvent.Edit), events)
    }

    @Test
    fun theTitleIsReadAsTheModelTitle() {
        show(TitleFixtures.of(FixtureAnime.exactCaughtUpLater))
        compose.onNodeWithText(FixtureAnime.exactCaughtUpLater.titles.romaji).assertIsDisplayed()
    }
}
