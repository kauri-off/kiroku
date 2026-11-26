package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
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
@Config(qualifiers = "en-w411dp-h891dp")
class MangaEditSheetTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<MangaEditSheetEvent>()
    private var state by mutableStateOf(MangaEditFixtures.existing)

    private val chapters get() = countField("Chapters read")
    private val volumes get() = countField("Volumes read")

    private fun countField(label: String) =
        compose.onNode(
            hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
        )

    private fun SemanticsNodeInteraction.enterCount(text: String) {
        performTextReplacement(text)
        performImeAction()
    }

    private val score get() = compose.onNode(hasText("Your score") and hasClickAction())
    private val save get() = compose.onNodeWithText("Save")

    private fun sheet(initial: MangaEditSheetState = MangaEditFixtures.existing) {
        state = initial
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                MangaEditSheet(state) { event ->
                    events += event
                    state =
                        when (event) {
                            is MangaEditSheetEvent.Save, MangaEditSheetEvent.Retry ->
                                state.copy(submission = MangaEditSubmission.Saving)
                            else -> state.after(event)
                        }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun unknownStatusAndCountsStillAllowAnExplicitPartialEdit() {
        sheet(MangaEditFixtures.unknown)
        compose.onNodeWithText("Current status: Rereading").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(5).assertAll(isOff())
        score.assertTextContains("Unknown")
        save.assertIsNotEnabled()
        chapters.enterCount("0")
        save.performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 0)), events.last())
    }

    @Test
    fun changingStatusCountsAndClearingScoreAreSavedTogether() {
        sheet()
        chapters.enterCount("28")
        volumes.enterCount("3")
        compose.onNodeWithText("On hold").performClick()
        score.performClick()
        compose.onNodeWithText("Not scored").performClick()
        score.assertTextContains("Not scored")
        save.performClick()
        assertEquals(
            MangaEditSheetEvent.Save(MangaEditChanges(MangaEditStatus.OnHold, chaptersRead = 28, volumesRead = 3, score = 0)),
            events.last(),
        )
    }

    @Test
    fun invalidInputShowsAnErrorAndDisablesSave() {
        sheet()
        chapters.enterCount("2147483648")
        compose.onNodeWithText("Enter a valid whole number").assertIsDisplayed()
        save.assertIsNotEnabled()
        chapters.enterCount("13")
        compose.onNodeWithText("Enter a valid whole number").assertDoesNotExist()
        save.performClick()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 13)), events.last())
    }

    @Test
    fun pendingShowsTheDraftAndSuppressesNewWrites() {
        sheet(MangaEditFixtures.saving)
        chapters.assertTextContains("13").assertIsNotEnabled()
        volumes.assertTextContains("3").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).assertCountEquals(5).assertAll(isNotEnabled())
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        save.assertDoesNotExist()
        compose.onNodeWithText("Remove from list").assertDoesNotExist()
        assertEquals(emptyList<MangaEditSheetEvent>(), events)
    }

    @Test
    fun retryUsesItsOwnEventAndKeepsTheSubmittedDraft() {
        sheet(MangaEditFixtures.failed)
        compose.onNodeWithText("Could not save").assertIsDisplayed()
        chapters.assertTextContains("13").assertIsNotEnabled()
        save.assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        assertEquals(listOf(MangaEditSheetEvent.Retry), events)
        assertEquals(MangaEditFixtures.saving, state)
    }

    @Test
    fun failureWithoutRetryHasNoWriteAction() {
        sheet(MangaEditFixtures.failed.copy(submission = MangaEditSubmission.Failed(canRetry = false)))
        compose.onNodeWithText("Could not save").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        save.assertDoesNotExist()
    }

    @Test
    fun dismissalAsksBeforeDiscardingAnUnsubmittedEdit() {
        sheet(MangaEditFixtures.changed)
        val dragHandle =
            compose.onNodeWithContentDescription(
                compose.activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description),
            )
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        chapters.assertTextContains("13")
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                MangaEditSheetEvent.DismissAsked,
                MangaEditSheetEvent.KeepEditing,
                MangaEditSheetEvent.DismissAsked,
                MangaEditSheetEvent.Discard,
                MangaEditSheetEvent.Dismissed,
            ),
            events,
        )
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp", fontScale = 2f)
    fun saveStaysInViewAtLargeText() {
        sheet(MangaEditFixtures.changed)
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
    }
}
