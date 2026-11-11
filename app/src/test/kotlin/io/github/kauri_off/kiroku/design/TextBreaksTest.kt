package io.github.kauri_off.kiroku.design

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.FactRow
import io.github.kauri_off.kiroku.design.component.PosterCard
import io.github.kauri_off.kiroku.design.component.RowLine
import io.github.kauri_off.kiroku.design.component.shownTitle
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Where titles and multi-part values break: after a hyphen rather than inside a segment, and never with an
// ellipsis after a part shown whole.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextBreaksTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private var shown by mutableStateOf<Pair<Float, Int>?>(null)

    private val sizes = listOf(1f to 411, 1.3f to 360, 2f to 411, 2f to 320, 1.15f to 300)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val (scale, width) = shown ?: return@setContent
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    androidx.compose.foundation.layout
                        .Box(Modifier.width(width.dp)) { content() }
                }
            }
        }
    }

    private fun eachSize(check: (String) -> Unit) {
        for ((scale, width) in sizes) {
            compose.runOnIdle { shown = scale to width }
            compose.waitForIdle()
            check("at $scale on $width dp")
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun assertBreaksAfterHyphens(
        text: String,
        layout: TextLayoutResult,
        where: String,
    ) {
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("$text breaks inside a segment $where", text[end - 1] in BREAKS || text[end] == ' ')
        }
    }

    @Test
    fun aRowTitleBreaksAfterAHyphen() {
        show { AnimeRow(HYPHENATED, posterUrl = null, onClick = {}, lines = listOf(RowLine("12 / 24"))) }
        eachSize { where ->
            val layout = compose.onNodeWithText(shownTitle(HYPHENATED), useUnmergedTree = true).layout()
            assertTrue(layout.lineCount > 1)
            assertBreaksAfterHyphens(shownTitle(HYPHENATED), layout, where)
        }
    }

    @Test
    fun aCardTitleBreaksAfterAHyphen() {
        show { PosterCard(HYPHENATED, "Sequel", "Completed", posterUrl = null, onClick = {}) }
        eachSize { where ->
            val layout = compose.onNodeWithText(shownTitle(HYPHENATED), useUnmergedTree = true).layout()
            assertBreaksAfterHyphens(shownTitle(HYPHENATED), layout, where)
        }
    }

    @Test
    fun anEllipsisNeverFollowsAPartShownWhole() {
        show { FactRow("Alternative titles", TITLES) }
        eachSize { where ->
            var lines = 0
            for (title in TITLES) {
                val node = compose.onNodeWithText(title, useUnmergedTree = true)
                if (node.fetchSemanticsNodes().isEmpty()) continue
                val layout = node.layout()
                lines += layout.lineCount
                val last = layout.lineCount - 1
                if (layout.isLineEllipsized(last)) {
                    assertTrue("$title is whole but ends in an ellipsis $where", layout.getLineEnd(last, visibleEnd = true) < title.length)
                }
            }
            assertTrue("$lines lines $where", lines in 1..3)
            assertFalse(compose.onNodeWithText(TITLES.first(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }

    private fun SemanticsNodeInteraction.fetchSemanticsNodes() = runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())

    companion object {
        private const val HYPHENATED = "Tenkuu-Toshi-Rondenia-no-Kuuchuu-Teien-to-Mahoutsukai-tachi"
        private val BREAKS = setOf('​', ' ')
        private val TITLES =
            listOf(
                "I Was Reincarnated as the Vampire Goddess, So I'll Live Leisurely in a Corner of Another World",
                "転生したら吸血鬼の女神様だったので、異世界の片隅でのんびりと暮らします",
                "Vampire Goddess",
                "Kyuuketsuki Megami",
            )
    }
}
