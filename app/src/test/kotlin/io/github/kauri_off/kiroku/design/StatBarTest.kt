package io.github.kauri_off.kiroku.design

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatBarTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private fun bar() =
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { StatBar("Completed", 1_234_567, 2_743_482) }
        }

    @Test
    @Config(qualifiers = "en")
    fun isReadAsOneSentence() {
        bar()
        compose.onNodeWithContentDescription("Completed, 1,234,567, 45\u00A0percent").assertExists()
    }

    @Test
    @Config(qualifiers = "ru")
    fun isReadAsOneSentenceInRussian() {
        bar()
        compose.onNodeWithContentDescription("Completed, 1\u00A0234\u00A0567, 45\u00A0процентов").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun aCountThatRoundsToNothingIsUnderOnePercent() {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { StatBar("Completed", 1_208, 290_652) } }
        compose.onNodeWithContentDescription("Completed, 1,208, less than\u00A01\u00A0percent").assertExists()
        compose.onNodeWithText("1,208 · <1%", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun anEmptyCountIsZeroPercent() {
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { StatBar("Dropped", 0, 290_652) } }
        compose.onNodeWithText("0 · 0%", useUnmergedTree = true).assertExists()
    }
}
