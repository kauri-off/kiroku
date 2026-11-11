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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import io.github.kauri_off.kiroku.design.component.EpisodeStepper
import io.github.kauri_off.kiroku.design.component.episodeText
import io.github.kauri_off.kiroku.design.component.typedCount
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
    fun aCountAboveTheTotalIsAppliedLikeAnyOther() {
        stepper(12, 24)
        field.performTextReplacement("30")
        field.performImeAction()
        assertEquals(listOf(30), changes)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("30")))
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
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
    fun buttonsStepByOneAndPlusPassesTheTotal() {
        stepper(23, 24)
        val plus = compose.onNodeWithContentDescription("Plus one episode")
        plus.performClick()
        plus.assertIsEnabled()
        plus.performClick()
        assertEquals(listOf(24, 25), changes)
        compose.onNodeWithContentDescription("Minus one episode").performClick()
        assertEquals(listOf(24, 25, 24), changes)
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
    fun aButtonStepsFromTheTypedCount() {
        stepper(12, 24)
        field.performTextReplacement("20")
        compose.onNodeWithContentDescription("Plus one episode").performClick()
        assertEquals(listOf(21), changes)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("21")))
        field.performTextReplacement("5")
        compose.onNodeWithContentDescription("Minus one episode").performClick()
        assertEquals(listOf(21, 4), changes)
    }

    @Test
    fun aTypedCountAboveTheTotalStepsLikeAnyOther() {
        stepper(12, 24)
        field.performTextReplacement("30")
        compose.onNodeWithContentDescription("Plus one episode").performClick()
        assertEquals(listOf(31), changes)
        field.performTextReplacement("26")
        compose.onNodeWithContentDescription("Minus one episode").performClick()
        assertEquals(listOf(31, 25), changes)
    }

    @Test
    fun aTypedCountAtTheTotalLeavesBothButtonsEnabled() {
        stepper(12, 24)
        field.performTextReplacement("24")
        compose.onNodeWithContentDescription("Plus one episode").assertIsEnabled()
        compose.onNodeWithContentDescription("Minus one episode").performClick()
        assertEquals(listOf(23), changes)
    }

    @Test
    fun aCountChangedFromOutsideDropsAnUnconfirmedOne() {
        var given by mutableIntStateOf(12)
        compose.setContent {
            focus = LocalFocusManager.current
            KirokuTheme(dark = false, dynamicColor = false) {
                EpisodeStepper(given, 24, onWatchedChange = { changes += it }, typed = "30")
            }
        }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("30")))
        compose.runOnIdle { given = 13 }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("13")))
        assertEquals(emptyList<Int>(), changes)
    }

    @Test
    fun aCountChangedFromOutsideKeepsTheOneBeingTyped() {
        var given by mutableIntStateOf(12)
        compose.setContent {
            focus = LocalFocusManager.current
            KirokuTheme(dark = false, dynamicColor = false) {
                EpisodeStepper(given, 24, onWatchedChange = { changes += it })
            }
        }
        field.performTextReplacement("20")
        compose.runOnIdle { given = 13 }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("20")))
        field.performImeAction()
        assertEquals(listOf(20), changes)
    }

    @Test
    fun anUnconfirmedCountSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                EpisodeStepper(12, 24, onWatchedChange = { changes += it })
            }
        }
        field.performTextReplacement("30")
        restoration.emulateSavedInstanceStateRestore()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("30")))
    }

    @Test
    fun theFieldShowsTheCountItIsGivenOnceTheTypedOneIsApplied() {
        compose.setContent {
            focus = LocalFocusManager.current
            KirokuTheme(dark = false, dynamicColor = false) {
                EpisodeStepper(12, 24, onWatchedChange = { changes += it })
            }
        }
        field.performTextReplacement("15")
        field.performImeAction()
        assertEquals(listOf(15), changes)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("12")))
    }

    @Test
    fun aPasteOfTooManyDigitsIsNotTakenNorCut() {
        stepper(12, 24)
        field.performTextReplacement("123")
        field.performTextReplacement("123456")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("123")))
        field.performImeAction()
        assertEquals(listOf(123), changes)
    }

    @Test
    fun inputRules() {
        assertEquals(13, typedCount("13", 12))
        assertEquals(7, typedCount("007", 12))
        assertEquals(null, typedCount("12", 12))
        assertEquals(null, typedCount("", 12))
        assertEquals(25, typedCount("25", 12))
        assertEquals(99_999, typedCount("99999", 12))
        assertEquals("12", episodeText(" 12 "))
        assertEquals(null, episodeText("1a-2"))
        assertEquals("99999", episodeText("99999"))
        assertEquals(null, episodeText("123456"))
    }
}
