package io.github.kauri_off.kiroku.design

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.RowTrailing
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Rows with one or two supporting lines keep one rhythm: the same height and "+1" in the same place.
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
}
