package io.github.kauri_off.kiroku.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A one-word section title or bar label stays on one line next to its button or value at font scale 2.0 on a
// 320 dp screen: the button or value moves under it instead.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp")
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
    fun statBarLabelIsWhole() {
        show { StatBar("Запланировано", 1_234_567, 3_000_000) }
        assertEquals(1, lines("Запланировано"))
    }
}
