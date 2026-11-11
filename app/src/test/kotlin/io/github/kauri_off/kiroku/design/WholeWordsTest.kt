package io.github.kauri_off.kiroku.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// At font scale 2.0 on a 320 dp screen: a one-word section title or bar label stays on one line, its button or value
// moving under it and still ending at the content edge, and the episode stepper keeps both buttons whole.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w320dp-h640dp")
class WholeWordsTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private fun show(content: @Composable () -> Unit) =
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                KirokuTheme(dark = false, dynamicColor = false, content = content)
            }
        }

    private fun lines(text: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.single().lineCount
    }

    @Test
    fun sectionTitleIsWhole() {
        show { SectionHeader("Рекомендации", onSeeAll = {}) }
        assertEquals(1, lines("Рекомендации"))
        compose.onNodeWithText("See all").assertExists()
    }

    @Test
    fun statBarLabelIsWholeAndItsValueEndsAtTheEdge() {
        show {
            Column {
                StatBar("Запланировано", 1_234_567, 3_000_000)
                StatBar("Смотрю", 1_234, 3_000_000)
            }
        }
        assertEquals(1, lines("Запланировано"))
        val stacked = compose.onNodeWithText("1,234,567 · 41%", useUnmergedTree = true).getBoundsInRoot()
        val inline = compose.onNodeWithText("1,234 · <1%", useUnmergedTree = true).getBoundsInRoot()
        val label = compose.onNodeWithText("Запланировано", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(stacked.top >= label.bottom)
        assertEquals(inline.right, stacked.right)
    }

    @Test
    fun stackedSectionTitleKeepsClearOfTheEdge() {
        show { SectionHeader("Рекомендации пользователей", onSeeAll = {}) }
        val title = compose.onNodeWithText("Рекомендации пользователей", useUnmergedTree = true).getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("${title.right} of ${root.right}", title.right <= root.right - Spacing.l)
    }

    @Test
    fun sectionHeaderTakesAnUnboundedWidth() {
        show { Row(Modifier.horizontalScroll(rememberScrollState())) { SectionHeader("Рекомендации", onSeeAll = {}) } }
        assertEquals(1, lines("Рекомендации"))
    }

    @Test
    fun stepperKeepsBothButtons() {
        show { EpisodeStepper(1088, 1122, onWatchedChange = {}, modifier = Modifier.padding(horizontal = Spacing.l)) }
        val root = compose.onRoot().getBoundsInRoot()
        for (label in listOf("Minus one episode", "Plus one episode")) {
            val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            val width = with(compose.density) { node.layoutInfo.width.toDp() }
            assertTrue("$label is $width wide", width >= 48.dp)
            assertTrue("$label ends at ${node.boundsInRoot.right}", node.boundsInRoot.right <= root.right.value * compose.density.density)
        }
    }
}
