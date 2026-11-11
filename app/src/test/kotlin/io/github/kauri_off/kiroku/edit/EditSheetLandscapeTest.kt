package io.github.kauri_off.kiroku.edit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

// A phone on its side: every control of the sheet can be scrolled to, and Save stays on screen without scrolling.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w891dp-h411dp-land")
class EditSheetLandscapeTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val today = LocalDate.of(2026, 1, 14)
    private val saved =
        ListValues(ListStatus.Watching, 12, score = 8, startDate = PartialDate.Day(LocalDate.of(2025, 10, 12)), finishDate = null)

    @Test
    fun `every control can be reached and Save stays on screen`() {
        val state = EditSheetState.of("Kagerou Toshokan 2nd Season", saved, total = 24, malEpisodes = 24, today)
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { EditSheet(state) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsDisplayed()
        val scroll =
            compose.onNode(
                hasScrollAction() and
                    androidx.compose.ui.test
                        .hasAnyDescendant(hasText("Dropped")),
            )
        for (text in listOf("Dropped", "Start date", "Finish date")) {
            scroll.performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsDisplayed()
        }
        val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        scroll.performScrollToNode(slider)
        compose.onNode(slider).assertIsDisplayed()
        scroll.performScrollToNode(hasContentDescription("Plus one episode"))
        compose.onNodeWithContentDescription("Plus one episode").assertIsDisplayed()
        compose.onNodeWithText("Drag handle").assertDoesNotExist()
    }
}
