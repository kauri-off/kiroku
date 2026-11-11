package io.github.kauri_off.kiroku.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import io.github.kauri_off.kiroku.design.component.EpisodeInput
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.episodeInput
import io.github.kauri_off.kiroku.design.component.filterEpisodeText
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
@Config(qualifiers = "en")
class EpisodeStepperTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val changes = mutableStateListOf<Int>()

    private lateinit var focus: FocusManager

    private val field get() = compose.onNodeWithContentDescription("Episodes watched")

    private fun stepper(
        watched: Int,
        total: Int?,
        enabled: Boolean = true,
    ) {
        compose.setContent {
            var value by remember { mutableIntStateOf(watched) }
            focus = LocalFocusManager.current
            KirokuTheme(dark = false, dynamicColor = false) {
                EpisodeStepper(value, total, onWatchedChange = {
                    changes += it
                    value = it
                }, enabled = enabled)
            }
        }
    }

    @Test
    fun confirmingIsTheOnlyEventOfTyping() {
        stepper(12, 24)
        field.performTextReplacement("2")
        field.performTextInput("0")
        assertEquals(emptyList<Int>(), changes)
        field.performImeAction()
        assertEquals(listOf(20), changes)
        compose.onNodeWithText("20", substring = true).assertExists()
    }

    @Test
    fun leavingTheFieldApplies() {
        stepper(12, 24)
        field.performTextReplacement("15")
        assertEquals(emptyList<Int>(), changes)
        compose.runOnIdle { focus.clearFocus() }
        compose.runOnIdle { assertEquals(listOf(15), changes) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("15")))
    }

    @Test
    fun aCountAboveTheTotalIsRefusedAndNotApplied() {
        stepper(12, 24)
        field.performTextReplacement("30")
        field.performImeAction()
        assertEquals(emptyList<Int>(), changes)
        compose.onNodeWithText("The title has 24 episodes").assertExists()
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field.performTextReplacement("24")
        field.performImeAction()
        assertEquals(listOf(24), changes)
        compose.onNodeWithText("The title has 24 episodes").assertDoesNotExist()
    }

    @Test
    fun anEmptyFieldKeepsTheCount() {
        stepper(12, 24)
        field.performTextClearance()
        field.performImeAction()
        assertEquals(emptyList<Int>(), changes)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("12")))
    }

    @Test
    fun buttonsStepByOneWithinTheTotal() {
        stepper(23, 24)
        val plus = compose.onNodeWithContentDescription("Plus one episode")
        plus.performClick()
        assertEquals(listOf(24), changes)
        plus.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Minus one episode").performClick()
        assertEquals(listOf(24, 23), changes)
    }

    @Test
    fun minusStopsAtZero() {
        stepper(0, 24)
        compose.onNodeWithContentDescription("Minus one episode").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Plus one episode").assertIsEnabled()
    }

    @Test
    fun anUnknownTotalHasNoUpperBound() {
        stepper(1093, null)
        compose.onNodeWithContentDescription("Plus one episode").performClick()
        field.performTextReplacement("2000")
        field.performImeAction()
        assertEquals(listOf(1094, 2000), changes)
    }

    @Test
    fun disabledTakesNoInput() {
        stepper(12, 24, enabled = false)
        compose.onNodeWithContentDescription("Minus one episode").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Plus one episode").assertIsNotEnabled()
        field.assertIsNotEnabled()
    }

    @Test
    fun inputRules() {
        assertEquals(EpisodeInput.Apply(13), episodeInput("13", 12, 24))
        assertEquals(EpisodeInput.Apply(7), episodeInput("007", 12, 24))
        assertEquals(EpisodeInput.Keep, episodeInput("12", 12, 24))
        assertEquals(EpisodeInput.Keep, episodeInput("", 12, 24))
        assertEquals(EpisodeInput.Apply(24), episodeInput("24", 12, 24))
        assertEquals(EpisodeInput.TooMany(24), episodeInput("25", 12, 24))
        assertEquals(EpisodeInput.Apply(5000), episodeInput("5000", 12, null))
        assertEquals("12", filterEpisodeText("1a-2", 24))
        assertEquals("99", filterEpisodeText("999", 24))
        assertEquals("1234", filterEpisodeText("123456", null))
    }
}
