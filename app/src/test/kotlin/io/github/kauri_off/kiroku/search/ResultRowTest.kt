package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.LARGE_TEXT
import io.github.kauri_off.kiroku.design.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The row of ARCHITECTURE 9.6: the facts line, the list status line where the title is on the list, the MAL score.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ResultRowTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val tv = FixtureAnime.exactToWatch

    private fun values(
        status: ListStatus,
        watched: Int,
    ) = ListValues(status, watched, null, null, null)

    private fun row(
        anime: Anime,
        entry: ListValues?,
        rank: Int? = null,
    ) = resultRow(anime, entry, TitleLanguage.Romaji, rank)

    @Test
    fun `the episode count is in the facts line unless the status line states the total or it is one`() {
        assertEquals(24, row(tv, null).facts.episodes)
        assertNull(row(tv, values(ListStatus.Watching, 12)).facts.episodes)
        assertEquals(24, row(tv, values(ListStatus.Planned, 0)).facts.episodes)
        assertNull(row(tv, values(ListStatus.Planned, 3)).facts.episodes)
        assertEquals(24, row(tv, values(ListStatus.Completed, 24)).facts.episodes)
        assertNull(row(FixtureAnime.film, null).facts.episodes)
        assertNull(row(FixtureAnime.unknownTotal, values(ListStatus.Watching, 5)).facts.episodes)
    }

    @Test
    fun `a row shows the facts, the status line where it says something, the score and a rank only when given`() {
        val rows =
            listOf(
                row(tv, values(ListStatus.Watching, 12)),
                row(FixtureAnime.premiereYear, values(ListStatus.Planned, 0)),
                row(FixtureAnime.prequel, values(ListStatus.Completed, 24)),
                row(FixtureAnime.notOnListAiring, null, rank = 7),
                row(FixtureAnime.unknownTotal, values(ListStatus.Watching, 1118)),
            )
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { Column { rows.forEach { ResultAnimeRow(it, onClick = {}) } } }
        }
        val texts =
            listOf(
                "TV · 2025",
                "Watching · 12 / 24",
                "Planned",
                "TV · 2023 · 24 episodes",
                "Completed",
                "7. ${FixtureAnime.notOnListAiring.titles.romaji}",
                "TV · 2025 · 12 episodes",
                "Watching · watched 1118",
            )
        for (text in texts) compose.onNode(hasPlainText(text), useUnmergedTree = true).assertExists()
        compose.onNode(hasPlainText("Completed · 24 / 24"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasPlainText("Planned · 0"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(tv.titles.romaji, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("MAL score 8.71", useUnmergedTree = true).assertExists()
    }
}

// A text node whose text reads [text] with its no-break spaces as spaces.
fun hasPlainText(text: String) =
    SemanticsMatcher("text is '$text'") { node ->
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .any { it.text.replace('\u00A0', ' ') == text }
    }

// With large text the score gives the title room: the number stands alone, without its star.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class MalScoreWidthTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private fun extra(fontScale: Float): Dp {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    Column {
                        AnimeRow("One", null, {}, trailing = RowTrailing.MalScore("8.71"))
                        Text("8.71", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("number"))
                    }
                }
            }
        }
        val score = compose.onNodeWithContentDescription("MAL score 8.71", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val number = compose.onNodeWithTag("number").getUnclippedBoundsInRoot()
        return (score.right - score.left) - (number.right - number.left)
    }

    @Test
    fun `the star goes with large text and stays below it`() {
        assertTrue(extra(LARGE_TEXT) <= 1.dp)
    }

    @Test
    fun `below large text the star is beside the score`() {
        assertTrue(extra(1f) > Spacing.s)
    }
}
