package io.github.kauri_off.kiroku.design

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Rows of one form keep one rhythm whatever their lines: the same height, and "+1" in the same place.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class AnimeRowTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun oneAndTwoSupportingLinesGiveTheSameRow() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                Column {
                    AnimeRow("One", null, {}, Modifier.testTag("one"), listOf(RowLine("12 / 24")), RowTrailing.PlusOne {})
                    AnimeRow(
                        "Two",
                        null,
                        {},
                        Modifier.testTag("two"),
                        listOf(RowLine("12 / 24 · 14 aired"), RowLine("2 to watch", highlighted = true)),
                        RowTrailing.PlusOne {},
                    )
                }
            }
        }
        val one = compose.onNodeWithTag("one").getUnclippedBoundsInRoot()
        val two = compose.onNodeWithTag("two").getUnclippedBoundsInRoot()
        val buttons = compose.onAllNodesWithContentDescription("Plus one episode")
        assertEquals(one.bottom - one.top, two.bottom - two.top)
        assertEquals(
            buttons[0].getUnclippedBoundsInRoot().top - one.top,
            buttons[1].getUnclippedBoundsInRoot().top - two.top,
        )
    }

    @Test
    fun `a row with plus one is as tall as its poster, with its title level with the poster's top`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                Column {
                    Poster("poster", PosterSize.Row, Modifier.testTag("poster"))
                    AnimeRow("One", "poster", {}, Modifier.testTag("one"), listOf(RowLine("12 / 24")), RowTrailing.PlusOne {})
                    AnimeRow(
                        "Two",
                        "poster",
                        {},
                        Modifier.testTag("two"),
                        listOf(RowLine("12 / 24"), RowLine("2 to watch")),
                        RowTrailing.PlusOne {},
                    )
                }
            }
        }
        val poster = compose.onNodeWithTag("poster").getUnclippedBoundsInRoot()
        assertEquals(Sizes.posterRow, poster.right - poster.left)
        assertEquals(Sizes.posterRowHeight, poster.bottom - poster.top)
        for (tag in listOf("one", "two")) {
            val row = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertEquals(Sizes.posterRowHeight + TopAlignedPadding * 2, row.bottom - row.top)
            val heading = compose.onNodeWithText(if (tag == "one") "One" else "Two", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(row.top + TopAlignedPadding, heading.top)
        }
    }

    @Test
    fun `a row without plus one centres its text on the poster, at one height for one and two lines`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                Column {
                    AnimeRow("One", "poster", {}, Modifier.testTag("one"), listOf(RowLine("Score 7")))
                    AnimeRow("Two", "poster", {}, Modifier.testTag("two"), listOf(RowLine("TV · 2019"), RowLine("Watching")))
                    AnimeRow("Three", "poster", {}, Modifier.testTag("three"), listOf(RowLine("Movie · 2020")), RowTrailing.Value("8.1"))
                }
            }
        }
        val texts = mapOf("one" to ("One" to "Score 7"), "two" to ("Two" to "Watching"), "three" to ("Three" to "Movie · 2020"))
        for ((tag, text) in texts) {
            val row = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertEquals(Sizes.posterRowHeight + CentredPadding * 2, row.bottom - row.top)
            val top = compose.onNodeWithText(text.first, useUnmergedTree = true).getUnclippedBoundsInRoot().top
            val bottom = compose.onNodeWithText(text.second, useUnmergedTree = true).getUnclippedBoundsInRoot().bottom
            val offCentre = (top + bottom) / 2 - (row.top + row.bottom) / 2
            assertTrue("$tag is ${offCentre.value} dp off centre", offCentre in (-1).dp..1.dp)
        }
    }

    private companion object {
        val TopAlignedPadding = 12.dp
        val CentredPadding = 8.dp
    }
}

// At large text a row is as tall as its text and the list item's padding, with no empty line under one supporting line.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp", fontScale = 2.0f)
class AnimeRowLargeTextTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a two-line title with one supporting line leaves no empty line below it`() {
        val title = "Shiokaze Rhapsody: Hatsuyume, Kagami no Kuni no Alice-tachi"
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                AnimeRow(title, null, {}, Modifier.testTag("row"), listOf(RowLine("Score 7")), RowTrailing.PlusOne(prominent = false) {})
            }
        }
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val line = compose.onNodeWithText("Score 7", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the title wraps", heading.bottom - heading.top > line.bottom - line.top)
        val padding = ListItemTop * 2
        assertTrue("the text is taller than the poster", line.bottom - heading.top > Sizes.posterRowHeight)
        assertTrue("$row, $heading, $line", row.bottom - row.top <= line.bottom - heading.top + padding + 1.dp)
    }

    @Test
    fun `the row poster narrows with the font scale, not below its narrowest width`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                Poster("poster", PosterSize.Row, Modifier.testTag("poster"))
            }
        }
        val poster = compose.onNodeWithTag("poster").getUnclippedBoundsInRoot()
        assertEquals(Sizes.posterRowNarrowest, poster.right - poster.left)
        val height = Sizes.posterRowNarrowest * (Sizes.posterRowHeight / Sizes.posterRow)
        assertTrue("${poster.bottom - poster.top}", poster.bottom - poster.top in (height - 1.dp)..(height + 1.dp))
    }

    private companion object {
        val ListItemTop = 12.dp
    }
}

// Between font scale 1.0 and the narrowest width, the row poster's width is in inverse proportion to the font scale.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp", fontScale = 1.3f)
class AnimeRowMidTextTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `the row poster narrows in inverse proportion to the font scale`() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                Poster("poster", PosterSize.Row, Modifier.testTag("poster"))
            }
        }
        val poster = compose.onNodeWithTag("poster").getUnclippedBoundsInRoot()
        val width = poster.right - poster.left
        assertTrue("$width", width in (Sizes.posterRow / 1.3f - 1.dp)..(Sizes.posterRow / 1.3f + 1.dp))
        assertTrue("$width", width > Sizes.posterRowNarrowest)
    }
}
