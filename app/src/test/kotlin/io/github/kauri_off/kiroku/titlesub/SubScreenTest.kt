package io.github.kauri_off.kiroku.titlesub

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
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
class SubScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private fun show(content: @Composable () -> Unit) = compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { content() } }

    @Test
    fun aLongEnglishTitleLeavesTheJapaneseTitleAndSynonymsShown() {
        val english = "The Heat Haze Library Season 2: ".repeat(12).trim()
        val anime = FixtureAnime.exactToWatch.copy(titles = FixtureAnime.exactToWatch.titles.copy(english = english))
        show { DetailsScreen(detailsState(anime, FixtureDetails.full, Fixtures.reference.clock), onEvent = {}) }
        for (text in listOf("English title", "Japanese title", "陽炎図書館 第2期", "Synonyms", "Kagetosho 2", "Heat Haze Library II")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun theStatusLineStatesTheCountOnlyWhereItAddsSomething() {
        val lines = mutableListOf<String>()
        show {
            lines += statusLine(ListStatus.Watching, 5, 12)
            lines += statusLine(ListStatus.Watching, 5, null)
            lines += statusLine(ListStatus.Planned, 0, 12)
            lines += statusLine(ListStatus.Planned, 2, 12)
            lines += statusLine(ListStatus.Completed, 12, 12)
            lines += statusLine(ListStatus.OnHold, 3, 12)
        }
        compose.waitForIdle()
        assertEquals(
            listOf(
                "Watching\u00A0· 5\u00A0/\u00A012",
                "Watching\u00A0· 5",
                "Planned",
                "Planned\u00A0· 2\u00A0/\u00A012",
                "Completed",
                "On hold\u00A0· 3\u00A0/\u00A012",
            ),
            lines.take(6),
        )
    }

    @Test
    fun aRelatedRowShowsItsFactsAndStatusAndOpensTheTitle() {
        val events = mutableListOf<RelatedEvent>()
        show { RelatedScreen(SubScreenFixtures.related(FixtureAnime.premiereYear), onEvent = { events += it }) }
        compose.onNodeWithText("Prequel").assertIsDisplayed()
        compose.onNodeWithText("TV\u00A0· 2025", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Watching\u00A0· 12\u00A0/\u00A024", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji, useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf(RelatedEvent.Open(FixtureAnime.exactToWatch.id), RelatedEvent.Back), events)
    }

    @Test
    fun detailsWithNoFactShowTheEmptyState() {
        show { DetailsScreen(DetailsState.Shown(emptyList()), onEvent = {}) }
        compose.onNodeWithText("MyAnimeList gives no details for this title").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp-xhdpi")
    fun theBroadcastAndDatesFollowTheLocale() {
        show { DetailsScreen(SubScreenFixtures.details(FixtureAnime.exactToWatch), onEvent = {}) }
        compose.onNodeWithText("По субботам в\u00A017:30", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("окт.", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("23\u00A0мин", useUnmergedTree = true).assertExists()
    }
}
